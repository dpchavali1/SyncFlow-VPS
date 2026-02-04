package com.phoneintegration.app

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.google.firebase.messaging.FirebaseMessaging
import com.phoneintegration.app.models.SyncFlowCall
import com.phoneintegration.app.models.SyncFlowDevice
import com.phoneintegration.app.webrtc.SyncFlowCallManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * Foreground service that manages SyncFlow-to-SyncFlow calls.
 * Listens for incoming calls, manages call lifecycle, and handles notifications.
 */
class SyncFlowCallService : Service() {

    companion object {
        private const val TAG = "SyncFlowCallService"
        private const val NOTIFICATION_CHANNEL_ID = "syncflow_calls"
        private const val NOTIFICATION_CHANNEL_ID_SERVICE = "syncflow_background"
        private const val NOTIFICATION_ID_SERVICE = 2001
        private const val NOTIFICATION_ID_INCOMING_CALL = 2002
        private const val NOTIFICATION_ID_FCM_CALL = 3001

        // Idle timeout - stop service after this period of inactivity (no active calls)
        private const val IDLE_TIMEOUT_MS = 60_000L // 60 seconds

        const val ACTION_START_SERVICE = "com.phoneintegration.app.START_SYNCFLOW_SERVICE"
        const val ACTION_STOP_SERVICE = "com.phoneintegration.app.STOP_SYNCFLOW_SERVICE"
        const val ACTION_ANSWER_CALL = "com.phoneintegration.app.ANSWER_SYNCFLOW_CALL"
        const val ACTION_REJECT_CALL = "com.phoneintegration.app.REJECT_SYNCFLOW_CALL"
        const val ACTION_END_CALL = "com.phoneintegration.app.END_SYNCFLOW_CALL"
        const val ACTION_START_CALL = "com.phoneintegration.app.START_SYNCFLOW_CALL"
        const val ACTION_INCOMING_USER_CALL = "com.phoneintegration.app.INCOMING_USER_CALL"
        const val ACTION_DISMISS_CALL_NOTIFICATION = "com.phoneintegration.app.DISMISS_CALL_NOTIFICATION"

        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_CALLEE_DEVICE_ID = "callee_device_id"
        const val EXTRA_CALLEE_NAME = "callee_name"
        const val EXTRA_IS_VIDEO = "is_video"
        const val EXTRA_WITH_VIDEO = "with_video"
        const val EXTRA_CALLER_NAME = "caller_name"
        const val EXTRA_CALLER_PHONE = "caller_phone"

        // Singleton reference for call manager
        private var _callManager: SyncFlowCallManager? = null

        // Observable state for pending incoming call (null = no incoming call)
        private val _pendingIncomingCallFlow = kotlinx.coroutines.flow.MutableStateFlow<SyncFlowCall?>(null)
        val pendingIncomingCallFlow: kotlinx.coroutines.flow.StateFlow<SyncFlowCall?> = _pendingIncomingCallFlow

        fun getCallManager(): SyncFlowCallManager? = _callManager

        // Called when incoming call should be dismissed (answered elsewhere, ended, etc.)
        fun dismissIncomingCall() {
            Log.d(TAG, "📞 dismissIncomingCall() called - setting flow to null")
            _pendingIncomingCallFlow.value = null
        }

        /**
         * Start the service. This now only starts the Firebase listener for incoming calls
         * instead of running a permanent foreground service. The service will stop itself
         * when idle to save battery.
         */
        fun startService(context: Context) {
            val intent = Intent(context, SyncFlowCallService::class.java).apply {
                action = ACTION_START_SERVICE
            }
            // On Android O+, we need to start as foreground, but we'll stop ourselves quickly
            // when there's no active call
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, SyncFlowCallService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            context.startService(intent)
        }

        fun startCall(
            context: Context,
            calleeDeviceId: String,
            calleeName: String,
            isVideo: Boolean
        ) {
            val intent = Intent(context, SyncFlowCallService::class.java).apply {
                action = ACTION_START_CALL
                putExtra(EXTRA_CALLEE_DEVICE_ID, calleeDeviceId)
                putExtra(EXTRA_CALLEE_NAME, calleeName)
                putExtra(EXTRA_IS_VIDEO, isVideo)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val database = FirebaseDatabase.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private var incomingCallsListener: ChildEventListener? = null
    private var incomingUserCallsListener: ChildEventListener? = null
    private var incomingUserCallsDebugListener: ValueEventListener? = null
    private var deviceStatusRef: DatabaseReference? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var pendingIncomingCall: SyncFlowCall? = null
    private var pendingIncomingCallStatusRef: DatabaseReference? = null
    private var pendingIncomingCallStatusListener: ValueEventListener? = null
    private var pendingPermissionCallId: String? = null
    private var pendingPermissionWithVideo: Boolean = false
    private var pendingPermissionIsUserCall: Boolean = false
    private var pendingPermissionCallerName: String? = null
    private var idleTimeoutJob: Job? = null
    private var isCallActive = false
    private var authStateListener: FirebaseAuth.AuthStateListener? = null
    private var lastAuthUid: String? = null

    // Ringtone for incoming calls
    private var ringtonePlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SyncFlowCallService created")

        createNotificationChannel()

        // Initialize call manager
        _callManager = SyncFlowCallManager(applicationContext)
        _callManager?.initialize()

        // Start listening for call state changes
        observeCallState()

        // Ensure listeners start once auth is available
        authStateListener = FirebaseAuth.AuthStateListener { auth ->
            val uid = auth.currentUser?.uid
            if (uid != null && uid != lastAuthUid) {
                lastAuthUid = uid
                Log.d(TAG, "Auth ready in service, starting call listeners for userId=$uid")
                startListeningForCalls()
                registerFcmToken()
            }
        }
        authStateListener?.let { auth.addAuthStateListener(it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SERVICE -> {
                startForegroundNotification()
                startListeningForCalls()
                updateDeviceOnlineStatus(true)
                registerFcmToken()
                startIdleTimeout() // Start idle timeout to auto-stop when no calls
                Log.d(TAG, "Service started (on-demand via FCM), listening for incoming calls")
            }
            ACTION_STOP_SERVICE -> {
                updateDeviceOnlineStatus(false)
                stopListeningForCalls()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_ANSWER_CALL -> {
                val callId = intent.getStringExtra(EXTRA_CALL_ID)
                val withVideo = intent.getBooleanExtra(EXTRA_WITH_VIDEO, false)
                if (callId != null) {
                    isCallActive = true
                    cancelIdleTimeout() // Keep service alive during call
                    answerCall(callId, withVideo)
                }
            }
            ACTION_REJECT_CALL -> {
                val callId = intent.getStringExtra(EXTRA_CALL_ID)
                if (callId != null) {
                    rejectCall(callId)
                    startIdleTimeout() // May stop service after timeout
                }
            }
            ACTION_END_CALL -> {
                isCallActive = false
                endCall()
                startIdleTimeout() // May stop service after timeout
            }
            ACTION_START_CALL -> {
                val calleeDeviceId = intent.getStringExtra(EXTRA_CALLEE_DEVICE_ID)
                val calleeName = intent.getStringExtra(EXTRA_CALLEE_NAME)
                val isVideo = intent.getBooleanExtra(EXTRA_IS_VIDEO, false)
                if (calleeDeviceId != null && calleeName != null) {
                    isCallActive = true
                    cancelIdleTimeout() // Keep service alive during call
                    startCall(calleeDeviceId, calleeName, isVideo)
                }
            }
            ACTION_INCOMING_USER_CALL -> {
                // Handle incoming user call directly from FCM (bypasses Firebase listeners which are offline)
                val callId = intent.getStringExtra(EXTRA_CALL_ID) ?: return START_NOT_STICKY
                val callerName = intent.getStringExtra(EXTRA_CALLER_NAME) ?: "Unknown"
                val callerPhone = intent.getStringExtra(EXTRA_CALLER_PHONE) ?: ""
                val isVideo = intent.getBooleanExtra(EXTRA_IS_VIDEO, false)

                Log.d(TAG, "Incoming user call from FCM: callId=$callId, caller=$callerName")

                val userId = auth.currentUser?.uid ?: return START_NOT_STICKY

                // Create the pending incoming call directly from FCM data
                val call = com.phoneintegration.app.models.SyncFlowCall(
                    id = callId,
                    callerId = "", // Will be populated when answering
                    callerName = callerName,
                    callerPlatform = "android",
                    calleeId = userId,
                    calleeName = "",
                    calleePlatform = "android",
                    callType = if (isVideo) com.phoneintegration.app.models.SyncFlowCall.CallType.VIDEO
                              else com.phoneintegration.app.models.SyncFlowCall.CallType.AUDIO,
                    status = com.phoneintegration.app.models.SyncFlowCall.CallStatus.RINGING,
                    startedAt = System.currentTimeMillis(),
                    isUserCall = true,
                    callerPhone = callerPhone
                )

                cancelIdleTimeout() // Keep service alive while call is ringing
                pendingIncomingCall = call
                _pendingIncomingCallFlow.value = call  // Update flow for MainActivity
                showIncomingCallNotification(call)
                launchIncomingCallActivity(call)
            }
            ACTION_DISMISS_CALL_NOTIFICATION -> {
                // Call was answered on another device (Mac/Web) or ended
                val callId = intent.getStringExtra(EXTRA_CALL_ID)
                Log.d(TAG, "📞 Dismiss call notification received for callId: $callId")

                // Only dismiss if this is for the current pending call
                if (callId == null || pendingIncomingCall?.id == callId) {
                    dismissIncomingCallNotification()
                    pendingIncomingCall = null
                    startIdleTimeout()

                    // Update the static flow so MainActivity can observe
                    _pendingIncomingCallFlow.value = null
                    Log.d(TAG, "📞 Updated pendingIncomingCallFlow to null")

                    // Also send broadcast as backup
                    val dismissIntent = Intent("com.phoneintegration.app.DISMISS_INCOMING_CALL").apply {
                        putExtra("call_id", callId)
                        putExtra("reason", "answered_elsewhere")
                    }
                    sendBroadcast(dismissIntent)
                    Log.d(TAG, "📞 Sent dismiss broadcast to MainActivity")
                }
            }
        }

        return START_NOT_STICKY // Don't restart automatically - rely on FCM
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "SyncFlowCallService destroyed")

        cancelIdleTimeout()
        updateDeviceOnlineStatus(false)
        stopListeningForCalls()
        authStateListener?.let { auth.removeAuthStateListener(it) }
        authStateListener = null
        _callManager?.release()
        _callManager = null
        serviceScope.cancel()
    }

    private fun startIdleTimeout() {
        // Don't start idle timeout if there's an active call or pending incoming call
        if (isCallActive || pendingIncomingCall != null) {
            Log.d(TAG, "Not starting idle timeout - call active or pending")
            return
        }

        idleTimeoutJob?.cancel()
        idleTimeoutJob = serviceScope.launch {
            Log.d(TAG, "Starting idle timeout (${IDLE_TIMEOUT_MS}ms)")
            delay(IDLE_TIMEOUT_MS)

            // Double-check before stopping
            if (!isCallActive && pendingIncomingCall == null) {
                Log.d(TAG, "Idle timeout reached, stopping service")
                updateDeviceOnlineStatus(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun cancelIdleTimeout() {
        idleTimeoutJob?.cancel()
        idleTimeoutJob = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Channel for incoming calls (high priority)
            val callChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "SyncFlow Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "SyncFlow audio/video call notifications"
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            // Channel for background service (minimal/silent)
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID_SERVICE,
                "SyncFlow Background",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Keeps SyncFlow ready to receive calls"
                setShowBadge(false)
                setSound(null, null)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(callChannel)
            notificationManager.createNotificationChannel(serviceChannel)
        }
    }

    private fun startForegroundNotification() {
        // On Android 14+ starting foreground while app is backgrounded will crash.
        val inForeground = androidx.lifecycle.ProcessLifecycleOwner.get()
            .lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        if (!inForeground) {
            Log.w(TAG, "Skipping startForegroundNotification because app is backgrounded")
            return
        }

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID_SERVICE)
            .setContentTitle("SyncFlow")
            .setContentText("Processing call...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setOngoing(true)
            .build()

        // On Android 14+, we need to specify the foreground service type
        // Start with dataSync type (doesn't require special permissions)
        // We'll upgrade to microphone|camera when actually in a call with permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID_SERVICE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID_SERVICE, notification)
        }
    }

    /**
     * Upgrade foreground service to use microphone/camera when starting a call.
     * This should only be called when we have the required permissions.
     */
    private fun upgradeToCallForegroundType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val hasMicPermission = ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            val hasCameraPermission = ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED

            if (hasMicPermission) {
                val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                    .setContentTitle("SyncFlow")
                    .setContentText("Call in progress")
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setOngoing(true)
                    .build()

                var serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (hasCameraPermission) {
                    serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                }

                try {
                    startForeground(NOTIFICATION_ID_SERVICE, notification, serviceType)
                } catch (e: SecurityException) {
                    Log.e(TAG, "Failed to upgrade foreground service type: ${e.message}")
                }
            }
        }
    }

    private fun startListeningForCalls() {
        val userId = auth.currentUser?.uid ?: run {
            Log.w(TAG, "Cannot start call listeners - user not authenticated yet")
            return
        }
        Log.d(TAG, "Starting to listen for incoming SyncFlow calls")
        stopListeningForCalls()

        // Listen for device-to-device calls (existing path)
        val callsRef = database.reference
            .child("users")
            .child(userId)
            .child("syncflow_calls")

        incomingCallsListener = callsRef.orderByChild("status").equalTo("ringing")
            .addChildEventListener(object : ChildEventListener {
                override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                    val callData = snapshot.value as? Map<String, Any?> ?: return
                    val call = SyncFlowCall.fromMap(snapshot.key ?: "", callData)

                    // Only handle incoming calls (where we are the callee on Android)
                    if (call.calleePlatform == "android" && call.isRinging) {
                        Log.d(TAG, "Incoming SyncFlow call from ${call.callerName}")
                        cancelIdleTimeout() // Keep service alive while call is ringing
                        pendingIncomingCall = call
                        startPendingCallStatusListener(call)
                        showIncomingCallNotification(call)
                        launchIncomingCallActivity(call)
                    }
                }

                override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                    val callData = snapshot.value as? Map<String, Any?> ?: return
                    val call = SyncFlowCall.fromMap(snapshot.key ?: "", callData)

                    // If call is no longer ringing, dismiss notification
                    if (!call.isRinging) {
                        dismissIncomingCallNotification()
                        pendingIncomingCall = null
                        stopPendingCallStatusListener()
                        updateNotificationForIdle()
                    }
                }

                override fun onChildRemoved(snapshot: DataSnapshot) {
                    val callId = snapshot.key
                    if (pendingIncomingCall == null || callId == pendingIncomingCall?.id) {
                        dismissIncomingCallNotification()
                        pendingIncomingCall = null
                        stopPendingCallStatusListener()
                        updateNotificationForIdle()
                    }
                }

                override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Incoming calls listener cancelled: ${error.message}")
                }
            })

        // Listen for user-to-user calls (new path for cross-user video calling)
        startListeningForUserCalls(userId)
    }

    private fun startListeningForUserCalls(userId: String) {
        Log.d(TAG, "Starting to listen for incoming user-to-user calls for userId: $userId")

        val userCallsRef = database.reference
            .child("users")
            .child(userId)
            .child("incoming_syncflow_calls")

        // Also add a simple value listener to see if any data exists
        val debugListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                Log.d(TAG, "incoming_syncflow_calls snapshot: exists=${snapshot.exists()}, childrenCount=${snapshot.childrenCount}")
                var hasRingingCall = false
                snapshot.children.forEach { child ->
                    val status = (child.value as? Map<*, *>)?.get("status")
                    Log.d(TAG, "  - Call ${child.key}: status=$status")
                    if ((status as? String) == "ringing") {
                        hasRingingCall = true
                    }
                }
                if (!hasRingingCall) {
                    Log.d(TAG, "No ringing user calls remain; dismissing any lingering notifications")
                    dismissIncomingCallNotification()
                    pendingIncomingCall = null
                    stopPendingCallStatusListener()
                    updateNotificationForIdle()
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "incoming_syncflow_calls listener error: ${error.message}")
            }
        }
        incomingUserCallsDebugListener = debugListener
        userCallsRef.addValueEventListener(debugListener)

        incomingUserCallsListener = userCallsRef.orderByChild("status").equalTo("ringing")
            .addChildEventListener(object : ChildEventListener {
                override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                    Log.d(TAG, "ChildEventListener onChildAdded: key=${snapshot.key}")
                    val callData = snapshot.value as? Map<String, Any?> ?: run {
                        Log.w(TAG, "Call data is null or not a Map")
                        return
                    }
                    val callId = snapshot.key ?: return

                    val callerUid = callData["callerUid"] as? String ?: ""
                    val callerName = callData["callerName"] as? String ?: "Unknown"
                    val callerPhone = callData["callerPhone"] as? String ?: ""
                    val callerPlatform = callData["callerPlatform"] as? String ?: "unknown"
                    val callType = callData["callType"] as? String ?: "audio"
                    val status = callData["status"] as? String ?: ""

                    Log.d(TAG, "Parsed call data: callerName=$callerName, status=$status, callType=$callType")

                    if (status == "ringing") {
                        Log.d(TAG, "Incoming user call from $callerName ($callerPhone)")

                        // Create a SyncFlowCall object for compatibility with existing UI
                        val call = SyncFlowCall(
                            id = callId,
                            callerId = callerUid,
                            callerName = callerName,
                            callerPlatform = callerPlatform,
                            calleeId = userId,
                            calleeName = "",
                            calleePlatform = "android",
                            callType = SyncFlowCall.CallType.fromString(callType),
                            status = SyncFlowCall.CallStatus.RINGING,
                            startedAt = System.currentTimeMillis(),
                            isUserCall = true,  // Mark as user call
                            callerPhone = callerPhone
                        )

                        cancelIdleTimeout() // Keep service alive while call is ringing
                        pendingIncomingCall = call
                        startPendingCallStatusListener(call)
                        showIncomingCallNotification(call)
                        launchIncomingCallActivity(call)
                    }
                }

                override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                    val callData = snapshot.value as? Map<String, Any?> ?: return
                    val callId = snapshot.key ?: return
                    val status = callData["status"] as? String ?: ""

                    // If call is no longer ringing, dismiss notification
                    if (status != "ringing") {
                        Log.d(TAG, "User call status changed to: $status")
                        dismissIncomingCallNotification()
                        pendingIncomingCall = null
                        stopPendingCallStatusListener()
                        updateNotificationForIdle()
                    }
                }

                override fun onChildRemoved(snapshot: DataSnapshot) {
                    val callId = snapshot.key
                    if (pendingIncomingCall == null || callId == pendingIncomingCall?.id) {
                        dismissIncomingCallNotification()
                        pendingIncomingCall = null
                        stopPendingCallStatusListener()
                        updateNotificationForIdle()
                    }
                }

                override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Incoming user calls listener cancelled: ${error.message}")
                }
            })
    }

    private fun stopListeningForCalls() {
        val userId = auth.currentUser?.uid ?: return

        incomingCallsListener?.let {
            database.reference
                .child("users")
                .child(userId)
                .child("syncflow_calls")
                .removeEventListener(it)
        }
        incomingCallsListener = null

        // Also stop listening for user calls
        incomingUserCallsListener?.let {
            database.reference
                .child("users")
                .child(userId)
                .child("incoming_syncflow_calls")
                .removeEventListener(it)
        }
        incomingUserCallsListener = null

        incomingUserCallsDebugListener?.let {
            database.reference
                .child("users")
                .child(userId)
                .child("incoming_syncflow_calls")
                .removeEventListener(it)
        }
        incomingUserCallsDebugListener = null

        stopPendingCallStatusListener()
    }

    private fun startPendingCallStatusListener(call: SyncFlowCall) {
        val userId = auth.currentUser?.uid ?: return

        stopPendingCallStatusListener()

        val callRef = database.reference
            .child("users")
            .child(userId)
            .child(if (call.isUserCall) "incoming_syncflow_calls" else "syncflow_calls")
            .child(call.id)

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (pendingIncomingCall?.id != call.id) return

                if (!snapshot.exists()) {
                    Log.d(TAG, "Pending call removed from Firebase, dismissing notification")
                    dismissIncomingCallNotification()
                    pendingIncomingCall = null
                    updateNotificationForIdle()
                    return
                }

                val status = snapshot.child("status").getValue(String::class.java) ?: return
                if (status != "ringing") {
                    Log.d(TAG, "Pending call status updated to $status, dismissing notification")
                    dismissIncomingCallNotification()
                    pendingIncomingCall = null
                    updateNotificationForIdle()
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Pending call status listener cancelled: ${error.message}")
            }
        }

        pendingIncomingCallStatusRef = callRef
        pendingIncomingCallStatusListener = listener
        callRef.addValueEventListener(listener)
    }

    private fun stopPendingCallStatusListener() {
        val ref = pendingIncomingCallStatusRef
        val listener = pendingIncomingCallStatusListener
        if (ref != null && listener != null) {
            ref.removeEventListener(listener)
        }
        pendingIncomingCallStatusRef = null
        pendingIncomingCallStatusListener = null
    }

    /**
     * Register/update FCM token in Firebase.
     * This ensures push notifications can be received even when the app is closed.
     */
    private fun registerFcmToken() {
        val userId = auth.currentUser?.uid ?: return

        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Log.e(TAG, "Failed to get FCM token", task.exception)
                return@addOnCompleteListener
            }

            val token = task.result
            Log.d(TAG, "Registering FCM token for user $userId")

            // Save token to Firebase
            database.reference
                .child("fcm_tokens")
                .child(userId)
                .setValue(token)
                .addOnSuccessListener {
                    Log.d(TAG, "FCM token registered successfully")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to register FCM token", e)
                }
        }
    }

    private fun updateDeviceOnlineStatus(online: Boolean) {
        val userId = auth.currentUser?.uid ?: return
        val deviceId = getAndroidDeviceId()

        deviceStatusRef = database.reference
            .child("users")
            .child(userId)
            .child("devices")
            .child(deviceId)

        val deviceData = SyncFlowDevice(
            id = deviceId,
            name = getAndroidDeviceName(),
            platform = "android",
            online = online,
            lastSeen = System.currentTimeMillis()
        )

        deviceStatusRef?.setValue(deviceData.toMap())

        // Set up disconnect handler
        if (online) {
            deviceStatusRef?.child("online")?.onDisconnect()?.setValue(false)
            deviceStatusRef?.child("lastSeen")?.onDisconnect()?.setValue(ServerValue.TIMESTAMP)
        }
    }

    private fun observeCallState() {
        serviceScope.launch {
            _callManager?.callState?.collectLatest { state ->
                when (state) {
                    is SyncFlowCallManager.CallState.Connected -> {
                        dismissIncomingCallNotification()
                        updateNotificationForActiveCall()
                    }
                    is SyncFlowCallManager.CallState.Ended,
                    is SyncFlowCallManager.CallState.Failed -> {
                        dismissIncomingCallNotification()
                        updateNotificationForIdle()
                    }
                    is SyncFlowCallManager.CallState.Idle -> {
                        if (pendingIncomingCall == null) {
                            updateNotificationForIdle()
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    private fun cancelFcmCallNotification() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.cancel(NOTIFICATION_ID_FCM_CALL)
    }

    private fun updateNotificationForIdle() {
        startForegroundNotification()
        isCallActive = false
        startIdleTimeout() // Service will stop after timeout if no new calls
    }

    private fun showIncomingCallNotification(call: SyncFlowCall) {
        Log.d(TAG, "Showing incoming call notification for ${call.callerName}")

        // Start ringtone
        startIncomingRingtone()

        // Start vibration
        startVibration()

        // Create full screen intent to launch incoming call UI
        // These flags are critical for Samsung devices to show the activity
        val fullScreenIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            putExtra("incoming_syncflow_call_id", call.id)
            putExtra("incoming_syncflow_call_name", call.callerName)
            putExtra("incoming_syncflow_call_video", call.isVideo)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, 100, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Use BroadcastReceiver for answer/decline to avoid unlock requirement on Samsung
        // The receiver will handle the action and then launch the activity

        val answerVideoIntent = Intent(this, CallActionReceiver::class.java).apply {
            action = CallActionReceiver.ACTION_ANSWER_CALL
            putExtra(CallActionReceiver.EXTRA_CALL_ID, call.id)
            putExtra(CallActionReceiver.EXTRA_CALLER_NAME, call.callerName)
            putExtra(CallActionReceiver.EXTRA_WITH_VIDEO, true)
            putExtra(CallActionReceiver.EXTRA_IS_VIDEO_CALL, call.isVideo)
        }
        val answerVideoPendingIntent = PendingIntent.getBroadcast(
            this, 110, answerVideoIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val answerAudioIntent = Intent(this, CallActionReceiver::class.java).apply {
            action = CallActionReceiver.ACTION_ANSWER_CALL
            putExtra(CallActionReceiver.EXTRA_CALL_ID, call.id)
            putExtra(CallActionReceiver.EXTRA_CALLER_NAME, call.callerName)
            putExtra(CallActionReceiver.EXTRA_WITH_VIDEO, false)
            putExtra(CallActionReceiver.EXTRA_IS_VIDEO_CALL, call.isVideo)
        }
        val answerAudioPendingIntent = PendingIntent.getBroadcast(
            this, 111, answerAudioIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Use BroadcastReceiver for decline too for consistency
        val rejectIntent = Intent(this, CallActionReceiver::class.java).apply {
            action = CallActionReceiver.ACTION_DECLINE_CALL
            putExtra(CallActionReceiver.EXTRA_CALL_ID, call.id)
        }
        val rejectPendingIntent = PendingIntent.getBroadcast(
            this, 2, rejectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val callType = if (call.isVideo) "Video" else "Audio"

        // Create caller person for call style notification
        val caller = Person.Builder()
            .setName(call.callerName)
            .setImportant(true)
            .build()

        // Build notification with call style for Android 12+
        val notificationBuilder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setTimeoutAfter(60000) // Auto dismiss after 60 seconds

        // Use CallStyle for Android 12+ for better incoming call UI
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            notificationBuilder.setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    caller,
                    rejectPendingIntent,
                    if (call.isVideo) answerVideoPendingIntent else answerAudioPendingIntent
                ).setIsVideo(call.isVideo)
            )
            // Add audio-only answer option for video calls
            if (call.isVideo) {
                notificationBuilder.addAction(
                    R.drawable.ic_launcher_foreground,
                    "Audio Only",
                    answerAudioPendingIntent
                )
            }
        } else {
            // Legacy notification style for older Android versions
            notificationBuilder
                .setContentTitle("Incoming $callType Call")
                .setContentText("${call.callerName} is calling...")
                .addAction(
                    android.R.drawable.ic_menu_call,
                    if (call.isVideo) "Accept Video" else "Accept",
                    if (call.isVideo) answerVideoPendingIntent else answerAudioPendingIntent
                )
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Decline",
                    rejectPendingIntent
                )
            if (call.isVideo) {
                notificationBuilder.addAction(
                    android.R.drawable.ic_menu_call,
                    "Audio Only",
                    answerAudioPendingIntent
                )
            }
        }

        val notification = notificationBuilder.build()
        notification.flags = notification.flags or Notification.FLAG_INSISTENT // Keep ringing

        // For Android 12+, CallStyle notifications must be posted as foreground service notification
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                // Use phone call foreground service type for incoming call
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        NOTIFICATION_ID_INCOMING_CALL,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                    )
                } else {
                    startForeground(
                        NOTIFICATION_ID_INCOMING_CALL,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    )
                }
                Log.d(TAG, "Incoming call notification shown as foreground service")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting foreground with call notification", e)
                // Fallback to regular notification
                val notificationManager = getSystemService(NotificationManager::class.java)
                notificationManager.notify(NOTIFICATION_ID_INCOMING_CALL, notification)
            }
        } else {
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID_INCOMING_CALL, notification)
            Log.d(TAG, "Incoming call notification shown")
        }
    }

    private fun startIncomingRingtone() {
        try {
            stopIncomingRingtone() // Stop any existing ringtone

            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtonePlayer = MediaPlayer().apply {
                setDataSource(this@SyncFlowCallService, ringtoneUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
            Log.d(TAG, "Incoming ringtone started")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting incoming ringtone", e)
        }
    }

    private fun stopIncomingRingtone() {
        try {
            val player = ringtonePlayer
            ringtonePlayer = null

            if (player != null) {
                if (player.isPlaying) {
                    player.stop()
                }
                player.reset()
                player.release()
                Log.d(TAG, "Incoming ringtone stopped")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping incoming ringtone", e)
        }
    }

    private fun startVibration() {
        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(VibratorManager::class.java)
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as? Vibrator
            }

            vibrator?.let { v ->
                if (v.hasVibrator()) {
                    // Vibration pattern: wait 0ms, vibrate 500ms, wait 500ms, repeat
                    val pattern = longArrayOf(0, 500, 500, 500, 500)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        v.vibrate(VibrationEffect.createWaveform(pattern, 0)) // 0 = repeat from index 0
                    } else {
                        @Suppress("DEPRECATION")
                        v.vibrate(pattern, 0)
                    }
                    Log.d(TAG, "Vibration started")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting vibration", e)
        }
    }

    private fun stopVibration() {
        try {
            vibrator?.cancel()
            vibrator = null
            Log.d(TAG, "Vibration stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping vibration", e)
        }
    }

    private fun dismissIncomingCallNotification() {
        Log.d(TAG, "Dismissing incoming call notification")

        // Stop ringtone and vibration
        stopIncomingRingtone()
        stopVibration()
        stopPendingCallStatusListener()

        // Ensure the incoming call foreground notification is removed
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping foreground for incoming call notification", e)
        }

        // Cancel notifications
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.cancel(NOTIFICATION_ID_INCOMING_CALL)
        cancelFcmCallNotification()
    }

    private fun launchIncomingCallActivity(call: SyncFlowCall) {
        Log.d(TAG, "Launching incoming call activity for call: ${call.id}")

        // Check if device is locked
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val isLocked = keyguardManager.isKeyguardLocked
        Log.d(TAG, "Device locked: $isLocked")

        // Launch the incoming call activity with proper flags for locked screen
        val intent = Intent(this, MainActivity::class.java).apply {
            // Essential flags for launching from service
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP

            // Add flags for showing over lock screen (for older API levels)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
                @Suppress("DEPRECATION")
                addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
            }

            putExtra("incoming_syncflow_call_id", call.id)
            putExtra("incoming_syncflow_call_name", call.callerName)
            putExtra("incoming_syncflow_call_video", call.isVideo)
            putExtra("from_locked_screen", isLocked)
        }

        try {
            startActivity(intent)
            Log.d(TAG, "Activity launched successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch activity", e)
        }
    }

    private fun updateNotificationForActiveCall() {
        val call = _callManager?.currentCall?.value ?: return

        val endIntent = Intent(this, SyncFlowCallService::class.java).apply {
            action = ACTION_END_CALL
        }
        val endPendingIntent = PendingIntent.getService(
            this, 3, endIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("SyncFlow Call")
            .setContentText("In call with ${call.displayName}")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .addAction(R.drawable.ic_launcher_foreground, "End Call", endPendingIntent)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val hasMicPermission = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                val hasCameraPermission = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED

                val serviceType = if (hasMicPermission) {
                    var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    if (call.isVideo && hasCameraPermission) {
                        type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                    }
                    type
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                }

                startForeground(NOTIFICATION_ID_SERVICE, notification, serviceType)
            } else {
                startForeground(NOTIFICATION_ID_SERVICE, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating foreground notification", e)
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID_SERVICE, notification)
        }
    }

    private fun startCall(calleeDeviceId: String, calleeName: String, isVideo: Boolean) {
        // Upgrade foreground service to microphone/camera type before starting call
        upgradeToCallForegroundType()

        serviceScope.launch {
            _callManager?.startCall(calleeDeviceId, calleeName, isVideo)
        }
    }

    private fun answerCall(callId: String, withVideo: Boolean) {
        val userId = auth.currentUser?.uid ?: return

        val incomingCall = pendingIncomingCall
        val isUserCall = incomingCall?.isUserCall ?: false
        val callerName = incomingCall?.callerName ?: "Unknown"

        dismissIncomingCallNotification()
        pendingIncomingCall = null

        // Upgrade foreground service to microphone/camera type before answering call
        upgradeToCallForegroundType()

        val needsAudioPermission =
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        val needsCameraPermission =
            withVideo && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED

        if (needsAudioPermission || needsCameraPermission) {
            pendingPermissionCallId = callId
            pendingPermissionWithVideo = withVideo
            pendingPermissionIsUserCall = isUserCall
            pendingPermissionCallerName = callerName

            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                putExtra("syncflow_call_action", "request_permissions")
                putExtra("incoming_syncflow_call_id", callId)
                putExtra("incoming_syncflow_call_name", callerName)
                putExtra("syncflow_call_answer_video", withVideo)
                putExtra("dismiss_keyguard", true)
            }

            try {
                startActivity(intent)
                Log.d(TAG, "Requested media permissions before answering call")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch MainActivity for permission request", e)
            }
            return
        }

        // Check if device is locked
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val isLocked = keyguardManager.isKeyguardLocked
        Log.d(TAG, "Answering call, device locked: $isLocked")

        // Launch MainActivity to show the call screen
        // Use comprehensive flags for Samsung and other OEM devices
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION)

            // Add flags for showing over lock screen (for older API levels)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
                @Suppress("DEPRECATION")
                addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
            }

            putExtra("active_syncflow_call", true)
            putExtra("active_syncflow_call_id", callId)
            putExtra("active_syncflow_call_name", callerName)
            putExtra("active_syncflow_call_video", withVideo)
            putExtra("from_locked_screen", isLocked)
            putExtra("dismiss_keyguard", true)  // Signal to MainActivity to dismiss keyguard
        }

        // Start activity - try multiple approaches for Samsung compatibility
        try {
            startActivity(intent)
            Log.d(TAG, "Launched MainActivity for active call screen with callId: $callId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MainActivity", e)
            // Fallback: use pending intent
            val pendingIntent = PendingIntent.getActivity(
                this, 200, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            try {
                pendingIntent.send()
                Log.d(TAG, "Launched MainActivity via PendingIntent")
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to launch via PendingIntent", e2)
            }
        }

        serviceScope.launch {
            if (isUserCall) {
                // Answer user-to-user call (uses internal auth for userId)
                _callManager?.answerUserCall(callId, withVideo)
            } else {
                // Answer device-to-device call
                _callManager?.answerCall(userId, callId, withVideo)
            }
        }
    }

    private fun rejectCall(callId: String) {
        val userId = auth.currentUser?.uid ?: return
        val isUserCall = pendingIncomingCall?.isUserCall ?: false

        dismissIncomingCallNotification()
        pendingIncomingCall = null

        serviceScope.launch {
            if (isUserCall) {
                // Reject user-to-user call (uses internal auth for userId)
                _callManager?.rejectUserCall(callId)
            } else {
                _callManager?.rejectCall(userId, callId)
            }
        }
    }

    private fun endCall() {
        serviceScope.launch {
            _callManager?.endCall()
        }
    }

    private fun getAndroidDeviceId(): String {
        return android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        )
    }

    private fun getAndroidDeviceName(): String {
        return android.os.Build.MODEL
    }
}
