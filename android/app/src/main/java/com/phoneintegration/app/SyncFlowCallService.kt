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
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.phoneintegration.app.models.SyncFlowCall
import com.phoneintegration.app.models.SyncFlowDevice
import com.phoneintegration.app.vps.VPSClient
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

        // Track recently handled call IDs to prevent duplicate notifications
        // (e.g., FCM arriving after WebSocket already handled the call)
        private val recentlyHandledCallIds = LinkedHashSet<String>()
        private const val MAX_RECENT_CALL_IDS = 20

        fun markCallHandled(callId: String) {
            synchronized(recentlyHandledCallIds) {
                recentlyHandledCallIds.add(callId)
                // Trim old entries to prevent unbounded growth
                while (recentlyHandledCallIds.size > MAX_RECENT_CALL_IDS) {
                    recentlyHandledCallIds.iterator().let { it.next(); it.remove() }
                }
            }
        }

        fun wasCallRecentlyHandled(callId: String): Boolean {
            synchronized(recentlyHandledCallIds) {
                return callId in recentlyHandledCallIds
            }
        }

        fun getCallManager(): SyncFlowCallManager? = _callManager

        // Called when incoming call should be dismissed (answered elsewhere, ended, etc.)
        fun dismissIncomingCall() {
            Log.d(TAG, "📞 dismissIncomingCall() called - setting flow to null")
            _pendingIncomingCallFlow.value = null
        }

        /**
         * Start the service. This starts the VPS call listener for incoming calls
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

    private lateinit var vpsClient: VPSClient

    private var incomingCallsPollingJob: Job? = null
    private var callStatusPollingJob: Job? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var pendingIncomingCall: SyncFlowCall? = null
    private var pendingPermissionCallId: String? = null
    private var pendingPermissionWithVideo: Boolean = false
    private var pendingPermissionIsUserCall: Boolean = false
    private var pendingPermissionCallerName: String? = null
    private var idleTimeoutJob: Job? = null
    private var isCallActive = false
    private var lastWsConnectAttemptMs: Long = 0

    // Ringtone for incoming calls
    private var ringtonePlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SyncFlowCallService created")

        vpsClient = VPSClient.getInstance(applicationContext)

        createNotificationChannel()

        // Initialize call manager
        _callManager = SyncFlowCallManager(applicationContext)
        _callManager?.initialize()

        // Start listening for call state changes
        observeCallState()

        // Start polling for calls if authenticated
        if (vpsClient.isAuthenticated) {
            Log.d(TAG, "VPS authenticated in service, starting call polling")
            ensureWebSocketConnected("service_create")
            startListeningForCalls()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // MUST call startForeground() immediately for ANY action started via startForegroundService().
        // Without this, early returns in action handlers would crash with
        // ForegroundServiceDidNotStartInTimeException on Android O+.
        startForegroundNotification()

        when (intent?.action) {
            ACTION_START_SERVICE -> {
                ensureWebSocketConnected("service_start")
                startListeningForCalls()
                updateDeviceOnlineStatus(true)
                startIdleTimeout() // Start idle timeout to auto-stop when no calls
                Log.d(TAG, "Service started (on-demand), listening for incoming calls")
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
                    ensureWebSocketConnected("answer_call")
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
                    ensureWebSocketConnected("start_call")
                    startCall(calleeDeviceId, calleeName, isVideo)
                }
            }
            ACTION_INCOMING_USER_CALL -> {
                // Handle incoming user call directly from push notification
                val callId = intent.getStringExtra(EXTRA_CALL_ID) ?: return START_NOT_STICKY
                val callerName = intent.getStringExtra(EXTRA_CALLER_NAME) ?: "Unknown"
                val callerPhone = intent.getStringExtra(EXTRA_CALLER_PHONE) ?: ""
                val isVideo = intent.getBooleanExtra(EXTRA_IS_VIDEO, false)

                Log.d(TAG, "Incoming user call: callId=$callId, caller=$callerName")

                // Guard: ignore duplicate notifications for the same call
                // This handles: same WebSocket message twice, FCM after WebSocket, service restart
                if (pendingIncomingCall?.id == callId || isCallActive || wasCallRecentlyHandled(callId)) {
                    Log.d(TAG, "Ignoring duplicate incoming call notification for $callId (pending=${pendingIncomingCall?.id}, active=$isCallActive, recentlyHandled=${wasCallRecentlyHandled(callId)})")
                    return START_NOT_STICKY
                }

                // Also check if the call manager already has this call active
                if (_callManager?.currentCall?.value?.id == callId) {
                    Log.d(TAG, "Ignoring incoming call notification - call $callId is already active in call manager")
                    return START_NOT_STICKY
                }

                ensureWebSocketConnected("incoming_call")

                val userId = vpsClient.userId ?: return START_NOT_STICKY

                markCallHandled(callId)

                // Create the pending incoming call
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
                startCallStatusPolling(call)

                // Verify call is still ringing on the server (async).
                // If FCM arrived late and the call is already ended, dismiss immediately.
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        val serverStatus = vpsClient.getSyncFlowCallStatus(callId)
                        if (serverStatus != null && serverStatus != "ringing") {
                            Log.d(TAG, "Call $callId is already $serverStatus on server, dismissing stale notification")
                            withContext(Dispatchers.Main) {
                                dismissIncomingCallNotification()
                                pendingIncomingCall = null
                                _pendingIncomingCallFlow.value = null
                                updateNotificationForIdle()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error verifying call status: ${e.message}")
                    }
                }
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
        _callManager?.release()
        _callManager = null
        serviceScope.cancel()
    }

    private fun startIdleTimeout() {
        // Don't start idle timeout if there's an active call or pending incoming call
        val callState = _callManager?.callState?.value
        val callInProgress = when (callState) {
            is SyncFlowCallManager.CallState.Ringing,
            is SyncFlowCallManager.CallState.Connecting,
            is SyncFlowCallManager.CallState.Connected -> true
            else -> false
        }
        if (isCallActive || pendingIncomingCall != null || callInProgress) {
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
        // We MUST always call startForeground() when started via startForegroundService(),
        // even when the app is backgrounded. Skipping it causes
        // ForegroundServiceDidNotStartInTimeException on Android 14+.
        // Using FOREGROUND_SERVICE_TYPE_PHONE_CALL is allowed from background when started
        // via high-priority FCM (which is how incoming calls arrive when app is killed).
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID_SERVICE)
            .setContentTitle("SyncFlow")
            .setContentText("Processing call...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID_SERVICE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
            } else {
                startForeground(NOTIFICATION_ID_SERVICE, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground notification: ${e.message}", e)
            // Last resort: try without specifying service type
            try {
                startForeground(NOTIFICATION_ID_SERVICE, notification)
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback startForeground also failed: ${e2.message}", e2)
            }
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
        if (!vpsClient.isAuthenticated) {
            Log.w(TAG, "Cannot start call polling - not authenticated yet")
            return
        }
        Log.d(TAG, "Starting to poll for incoming SyncFlow calls")
        ensureWebSocketConnected("start_listening")
        stopListeningForCalls()

        // Poll for pending SyncFlow calls (catches calls missed while app was killed)
        incomingCallsPollingJob = serviceScope.launch(Dispatchers.IO) {
            // Initial check immediately on start
            checkPendingSyncFlowCalls()

            // Then poll periodically as catch-up
            while (isActive) {
                delay(5000) // Poll every 5 seconds
                if (!isCallActive && pendingIncomingCall == null) {
                    checkPendingSyncFlowCalls()
                }
            }
        }
    }

    /**
     * Check the server for any ringing SyncFlow calls where we are the callee.
     * This is the REST fallback for when FCM/WebSocket fail to deliver the incoming call.
     */
    private suspend fun checkPendingSyncFlowCalls() {
        try {
            val pendingCalls = vpsClient.getPendingSyncFlowCalls()
            Log.d(TAG, "Checked pending SyncFlow calls: ${pendingCalls.size} found")
            if (pendingCalls.isEmpty()) return

            for (callMap in pendingCalls) {
                val callId = callMap["id"] as? String ?: continue

                // Skip if already handled
                if (wasCallRecentlyHandled(callId) || pendingIncomingCall?.id == callId || isCallActive) {
                    continue
                }
                if (_callManager?.currentCall?.value?.id == callId) {
                    continue
                }

                Log.i(TAG, "Discovered pending SyncFlow call via REST polling: $callId")

                withContext(Dispatchers.Main) {
                    // Trigger the incoming call flow
                    val callerName = (callMap["callerName"] as? String) ?: "SyncFlow Device"
                    val callerPhone = (callMap["callerPhone"] as? String) ?: ""
                    val intent = Intent(this@SyncFlowCallService, SyncFlowCallService::class.java).apply {
                        action = ACTION_INCOMING_USER_CALL
                        putExtra(EXTRA_CALL_ID, callId)
                        putExtra(EXTRA_CALLER_NAME, callerName)
                        putExtra(EXTRA_CALLER_PHONE, callerPhone)
                        putExtra(EXTRA_IS_VIDEO, false) // Default to audio; call details fetched on answer
                    }
                    onStartCommand(intent, 0, 0)
                }
                break // Handle one call at a time
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking pending SyncFlow calls: ${e.message}")
        }
    }

    private fun startCallStatusPolling(call: SyncFlowCall) {
        callStatusPollingJob?.cancel()
        callStatusPollingJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    // Check if call is still ringing via SyncFlow call status API
                    val status = vpsClient.getSyncFlowCallStatus(call.id)
                    if (status != null && status != "ringing") {
                        // Call ended, answered elsewhere, rejected, etc.
                        withContext(Dispatchers.Main) {
                            Log.d(TAG, "Call ${call.id} is now '$status', dismissing notification")
                            dismissIncomingCallNotification()
                            pendingIncomingCall = null
                            _pendingIncomingCallFlow.value = null
                            updateNotificationForIdle()
                        }
                        break
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error polling call status", e)
                }
                delay(2000) // Check every 2 seconds
            }
        }
    }

    private fun stopListeningForCalls() {
        incomingCallsPollingJob?.cancel()
        incomingCallsPollingJob = null
        callStatusPollingJob?.cancel()
        callStatusPollingJob = null
    }

    private fun stopPendingCallStatusListener() {
        callStatusPollingJob?.cancel()
        callStatusPollingJob = null
    }

    private fun ensureWebSocketConnected(reason: String) {
        if (!vpsClient.isAuthenticated) return
        if (vpsClient.connectionState.value) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastWsConnectAttemptMs < 2000) return
        lastWsConnectAttemptMs = now
        Log.d(TAG, "Ensuring WebSocket connected ($reason)")
        vpsClient.connectWebSocket()
    }

    private fun updateDeviceOnlineStatus(online: Boolean) {
        if (!vpsClient.isAuthenticated) return
        val deviceId = vpsClient.deviceId ?: return

        serviceScope.launch(Dispatchers.IO) {
            try {
                vpsClient.updateDevice(
                    id = deviceId,
                    name = getAndroidDeviceName()
                )
                Log.d(TAG, "Device status updated: online=$online")
            } catch (e: Exception) {
                Log.e(TAG, "Error updating device status", e)
            }
        }
    }

    private fun observeCallState() {
        serviceScope.launch {
            _callManager?.callState?.collectLatest { state ->
                when (state) {
                    is SyncFlowCallManager.CallState.Ringing,
                    is SyncFlowCallManager.CallState.Connecting -> {
                        isCallActive = true
                        cancelIdleTimeout()
                    }
                    is SyncFlowCallManager.CallState.Connected -> {
                        isCallActive = true
                        cancelIdleTimeout()
                        dismissIncomingCallNotification()
                        updateNotificationForActiveCall()
                    }
                    is SyncFlowCallManager.CallState.Ended,
                    is SyncFlowCallManager.CallState.Failed -> {
                        isCallActive = false
                        dismissIncomingCallNotification()
                        updateNotificationForIdle()
                    }
                    is SyncFlowCallManager.CallState.Idle -> {
                        isCallActive = false
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
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
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

        // Must call startForeground on all Android O+ when started via startForegroundService
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID_INCOMING_CALL,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                startForeground(
                    NOTIFICATION_ID_INCOMING_CALL,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID_INCOMING_CALL, notification)
            }
            Log.d(TAG, "Incoming call notification shown as foreground service")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground with call notification", e)
            // Fallback to regular notification
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID_INCOMING_CALL, notification)
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

        if (isLocked) {
            // Rely on full-screen call notification and lock-screen actions.
            // Avoid launching activity behind keyguard (can hide the notification on some OEMs).
            Log.d(TAG, "Device locked - skipping activity launch, using call notification")
            return
        }

        // Launch the incoming call activity with proper flags for locked screen
        val intent = Intent(this, MainActivity::class.java).apply {
            // Essential flags for launching from service
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP

            // Add flags for showing over lock screen (for older API levels)
            // Do NOT use FLAG_DISMISS_KEYGUARD - we want to show over lock screen without forcing unlock
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
                @Suppress("DEPRECATION")
                addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
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
            val result = _callManager?.startCall(calleeDeviceId, calleeName, isVideo)
            if (result?.isSuccess == true) {
                Log.d(TAG, "WebRTC call started: ${result.getOrNull()}")
            } else {
                Log.e(TAG, "Failed to start WebRTC call: ${result?.exceptionOrNull()?.message}")
                isCallActive = false
                startIdleTimeout()
            }
        }
    }

    private fun answerCall(callId: String, withVideo: Boolean) {
        val userId = vpsClient.userId ?: return

        markCallHandled(callId) // Prevent duplicate notifications for this call

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
            // Do NOT use FLAG_DISMISS_KEYGUARD - we want to show over lock screen without forcing unlock
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
                @Suppress("DEPRECATION")
                addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
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
        val userId = vpsClient.userId ?: return
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
