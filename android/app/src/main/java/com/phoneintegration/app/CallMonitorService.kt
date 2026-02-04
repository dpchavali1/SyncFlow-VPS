package com.phoneintegration.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.CallLog
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.Query
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.phoneintegration.app.desktop.DesktopSyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class CallMonitorService : Service() {

    companion object {
        private const val TAG = "CallMonitorService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "call_monitor_channel"

        fun start(context: Context) {
            val intent = Intent(context, CallMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, CallMonitorService::class.java)
            context.stopService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var telephonyManager: TelephonyManager
    private lateinit var telecomManager: TelecomManager
    private lateinit var syncService: DesktopSyncService
    private lateinit var simManager: SimManager
    private val database = FirebaseDatabase.getInstance()

    private var callCommandsQuery: Query? = null
    private var callCommandsListener: com.google.firebase.database.ChildEventListener? = null
    private var callRequestsRef: com.google.firebase.database.DatabaseReference? = null
    private var callRequestsListener: com.google.firebase.database.ChildEventListener? = null

    // For API < 31
    @Suppress("DEPRECATION")
    private val phoneStateListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            handleCallStateChange(state, phoneNumber)
        }
    }

    // For API >= 31
    @SuppressLint("MissingPermission")
    private val telephonyCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                handleCallStateChange(state, null)
            }
        }
    } else null

    private var currentCallId: String? = null
    private var pendingPhoneNumber: String? = null
    private var callLogObserver: android.database.ContentObserver? = null

    // Broadcast receiver for phone number from InCallService/CallScreeningService
    private val incomingCallReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "📞📞📞 BROADCAST RECEIVED! action=${intent?.action}")
            if (intent?.action == DesktopInCallService.ACTION_INCOMING_CALL) {
                val phoneNumber = intent.getStringExtra(DesktopInCallService.EXTRA_PHONE_NUMBER)
                val callIdFromBroadcast = intent.getStringExtra("call_id")
                val callState = intent.getStringExtra(DesktopInCallService.EXTRA_CALL_STATE)
                Log.d(TAG, "📞📞📞 INCOMING_CALL broadcast - phoneNumber: $phoneNumber, callId: $callIdFromBroadcast, state: $callState")

                if (!phoneNumber.isNullOrEmpty()) {
                    pendingPhoneNumber = phoneNumber

                    // If we got a callId from the broadcast (from CallScreeningService), use it
                    if (!callIdFromBroadcast.isNullOrEmpty()) {
                        Log.d(TAG, "📞 Setting currentCallId from broadcast: $callIdFromBroadcast")
                        currentCallId = callIdFromBroadcast

                        // OPTIMIZATION: Sync to Firebase IMMEDIATELY when we receive the broadcast
                        // Don't wait for TelephonyCallback - this speeds up notification delivery
                        if (callState == "ringing") {
                            Log.d(TAG, "📞 FAST PATH: Syncing ringing call immediately from broadcast!")
                            serviceScope.launch(kotlinx.coroutines.NonCancellable) {
                                try {
                                    val contactHelper = ContactHelper(this@CallMonitorService)
                                    val contactName = contactHelper.getContactName(phoneNumber)
                                    Log.d(TAG, "📞 Contact lookup: $phoneNumber -> ${contactName ?: "null"}")

                                    // Sync immediately - don't wait for TelephonyCallback
                                    syncActiveCall(
                                        callId = callIdFromBroadcast,
                                        phoneNumber = phoneNumber,
                                        contactName = contactName,
                                        callState = "ringing"
                                    )

                                    // Also sync to call history
                                    syncService.syncCallEvent(
                                        callId = callIdFromBroadcast,
                                        phoneNumber = phoneNumber,
                                        contactName = contactName,
                                        callType = "incoming",
                                        callState = "ringing"
                                    )

                                    Log.d(TAG, "📞 FAST PATH: Call synced to Firebase!")
                                } catch (e: Exception) {
                                    Log.e(TAG, "📞 FAST PATH error: ${e.message}", e)
                                }
                            }
                        }
                    } else {
                        // No callId in broadcast, just update existing call if we have one
                        currentCallId?.let { callId ->
                            serviceScope.launch {
                                try {
                                    val contactHelper = ContactHelper(this@CallMonitorService)
                                    val contactName = contactHelper.getContactName(phoneNumber)
                                    Log.d(TAG, "Updating call with real phone number: $phoneNumber, contact: $contactName")
                                    updateActiveCallPhoneNumber(callId, phoneNumber, contactName)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error updating call phone number", e)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "CallMonitorService created")

        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        syncService = DesktopSyncService(this)
        simManager = SimManager(this)

        // Restore currentCallId from SharedPreferences in case service was recreated
        // This ensures we can properly sync "ended" state when the call ends
        val storedCallId = getStoredCallId()
        if (storedCallId != null) {
            Log.d(TAG, "📞 Restored currentCallId from SharedPreferences: $storedCallId")
            currentCallId = storedCallId
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        // Register for phone number broadcasts from InCallService
        val filter = android.content.IntentFilter(DesktopInCallService.ACTION_INCOMING_CALL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(incomingCallReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(incomingCallReceiver, filter)
        }

        // Register ContentObserver to watch call log changes (backup method for phone number)
        registerCallLogObserver()

        registerCallStateListener()
        cleanupStaleActiveCalls()
        listenForCallCommands()
        listenForCallRequests()
        syncCallHistory()
        syncSimInformation()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "CallMonitorService started")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "CallMonitorService destroyed")
        try {
            unregisterReceiver(incomingCallReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering receiver", e)
        }
        unregisterCallLogObserver()
        unregisterCallStateListener()
        stopFirebaseListeners()
        serviceScope.cancel()
    }

    @SuppressLint("MissingPermission")
    private fun registerCallLogObserver() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_CALL_LOG permission not granted, skipping observer")
            return
        }

        callLogObserver = object : android.database.ContentObserver(android.os.Handler(mainLooper)) {
            override fun onChange(selfChange: Boolean, uri: android.net.Uri?) {
                super.onChange(selfChange, uri)
                Log.d(TAG, "Call log changed: $uri")
                // If we're waiting for a phone number and a call is ringing
                if (currentCallId != null && pendingPhoneNumber.isNullOrEmpty()) {
                    serviceScope.launch {
                        val number = getLastIncomingCallNumber()
                        if (!number.isNullOrEmpty() && number != "Unknown") {
                            Log.d(TAG, "Got phone number from call log observer: $number")
                            pendingPhoneNumber = number
                            // Update Firebase with the real phone number
                            val contactHelper = ContactHelper(this@CallMonitorService)
                            val contactName = contactHelper.getContactName(number)
                            updateActiveCallPhoneNumber(currentCallId!!, number, contactName)
                        }
                    }
                }
            }
        }

        contentResolver.registerContentObserver(
            CallLog.Calls.CONTENT_URI,
            true,
            callLogObserver!!
        )
        Log.d(TAG, "Call log observer registered")
    }

    private fun unregisterCallLogObserver() {
        callLogObserver?.let {
            contentResolver.unregisterContentObserver(it)
            callLogObserver = null
            Log.d(TAG, "Call log observer unregistered")
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerCallStateListener() {
        if (!checkCallPermissions()) {
            Log.e(TAG, "Missing call permissions")
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephonyCallback?.let {
                    telephonyManager.registerTelephonyCallback(mainExecutor, it)
                    Log.d(TAG, "Registered TelephonyCallback (API 31+)")
                }
            } else {
                @Suppress("DEPRECATION")
                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
                Log.d(TAG, "Registered PhoneStateListener (API < 31)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error registering call state listener", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun unregisterCallStateListener() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephonyCallback?.let {
                    telephonyManager.unregisterTelephonyCallback(it)
                }
            } else {
                @Suppress("DEPRECATION")
                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
            }
            Log.d(TAG, "Unregistered call state listener")
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering call state listener", e)
        }
    }

    private fun handleCallStateChange(state: Int, phoneNumber: String?) {
        Log.d(TAG, "Call state changed: $state, number: $phoneNumber")

        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                // Incoming call - try multiple sources for phone number
                serviceScope.launch {
                    // Priority: 1) TelephonyCallback, 2) InCallService broadcast, 3) SharedPreferences (from CallScreeningService), 4) Call log
                    val number = when {
                        !phoneNumber.isNullOrEmpty() && phoneNumber != "Unknown" -> {
                            Log.d(TAG, "Using phone number from TelephonyCallback: $phoneNumber")
                            phoneNumber
                        }
                        !pendingPhoneNumber.isNullOrEmpty() -> {
                            Log.d(TAG, "Using phone number from InCallService: $pendingPhoneNumber")
                            pendingPhoneNumber!!
                        }
                        else -> {
                            // Try SharedPreferences (set by CallScreeningService, survives service restart)
                            val storedNumber = getStoredPhoneNumber()
                            if (storedNumber != null) {
                                Log.d(TAG, "Using phone number from SharedPreferences (CallScreeningService): $storedNumber")
                                storedNumber
                            } else {
                                // Last resort: try call log (usually won't work during ringing)
                                val callLogNumber = getLastIncomingCallNumber()
                                Log.d(TAG, "Attempted call log query, result: $callLogNumber")
                                callLogNumber ?: "Unknown"
                            }
                        }
                    }
                    Log.d(TAG, "Incoming call from: $number (original: $phoneNumber, pending: $pendingPhoneNumber)")
                    onIncomingCall(number)
                }
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                // Call answered or outgoing call
                Log.d(TAG, "Call active")
                onCallActive()
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                // Call ended
                Log.d(TAG, "Call ended")
                onCallEnded()
            }
        }
    }

    /**
     * Get phone number stored by CallScreeningService.
     * This survives service restarts.
     */
    private fun getStoredPhoneNumber(): String? {
        try {
            val prefs = getSharedPreferences("call_screening_prefs", MODE_PRIVATE)
            val storedNumber = prefs.getString("last_incoming_number", null)
            val storedTimestamp = prefs.getLong("last_incoming_timestamp", 0L)
            val age = System.currentTimeMillis() - storedTimestamp

            // Only use if stored within last 30 seconds (same call)
            if (storedNumber != null && age < 30000) {
                Log.d(TAG, "Found stored phone number: $storedNumber (age: ${age}ms)")
                return storedNumber
            } else if (storedNumber != null) {
                Log.d(TAG, "Stored phone number too old (age: ${age}ms), ignoring")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading stored phone number", e)
        }
        return null
    }

    /**
     * Get call ID stored by CallScreeningService.
     * This ensures we use the same call ID that was already synced to Firebase.
     */
    private fun getStoredCallId(): String? {
        try {
            val prefs = getSharedPreferences("call_screening_prefs", MODE_PRIVATE)
            val storedCallId = prefs.getString("last_incoming_call_id", null)
            val storedTimestamp = prefs.getLong("last_incoming_timestamp", 0L)
            val age = System.currentTimeMillis() - storedTimestamp

            // Only use if stored within last 60 seconds (same call)
            if (storedCallId != null && age < 60000) {
                Log.d(TAG, "Found stored callId: $storedCallId (age: ${age}ms)")
                return storedCallId
            } else if (storedCallId != null) {
                Log.d(TAG, "Stored callId too old (age: ${age}ms), ignoring")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading stored callId", e)
        }
        return null
    }

    /**
     * Clear stored phone number (call ended)
     */
    private fun clearStoredPhoneNumber() {
        try {
            val prefs = getSharedPreferences("call_screening_prefs", MODE_PRIVATE)
            prefs.edit().clear().apply()
            Log.d(TAG, "Cleared stored phone number")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing stored phone number", e)
        }
    }

    /**
     * Get the phone number of the last incoming call from call log.
     * Used on Android 12+ where TelephonyCallback doesn't provide the number.
     */
    @SuppressLint("MissingPermission", "Range")
    private suspend fun getLastIncomingCallNumber(): String? = withContext(Dispatchers.IO) {
        try {
            val hasPermission = ActivityCompat.checkSelfPermission(
                this@CallMonitorService,
                Manifest.permission.READ_CALL_LOG
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasPermission) {
                Log.w(TAG, "READ_CALL_LOG permission not granted, cannot get phone number")
                return@withContext null
            }

            // Query for the most recent incoming call (within last 10 seconds)
            val tenSecondsAgo = System.currentTimeMillis() - 10000
            val cursor = contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE),
                "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.DATE} > ?",
                arrayOf(CallLog.Calls.INCOMING_TYPE.toString(), tenSecondsAgo.toString()),
                "${CallLog.Calls.DATE} DESC"
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    val number = it.getString(it.getColumnIndex(CallLog.Calls.NUMBER))
                    Log.d(TAG, "Found incoming call number from call log: $number")
                    return@withContext number
                }
            }

            // If no recent incoming call found, try to get any recent call
            val anyCursor = contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE),
                "${CallLog.Calls.DATE} > ?",
                arrayOf(tenSecondsAgo.toString()),
                "${CallLog.Calls.DATE} DESC"
            )

            anyCursor?.use {
                if (it.moveToFirst()) {
                    val number = it.getString(it.getColumnIndex(CallLog.Calls.NUMBER))
                    Log.d(TAG, "Found recent call number from call log: $number")
                    return@withContext number
                }
            }

            Log.w(TAG, "Could not find recent call in call log")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error querying call log", e)
            null
        }
    }

    private fun onIncomingCall(phoneNumber: String) {
        Log.d(TAG, "📞 onIncomingCall START - phoneNumber: $phoneNumber")

        // Use NonCancellable to ensure the Firebase write completes even if the coroutine scope is cancelled
        serviceScope.launch(kotlinx.coroutines.NonCancellable) {
            try {
                Log.d(TAG, "📞 Coroutine started for incoming call sync")

                // Priority for call ID:
                // 1. Stored callId from CallScreeningService
                // 2. Existing ringing call in Firebase for same phone number
                // 3. Generate new callId

                val storedCallId = getStoredCallId()
                val callId: String

                if (storedCallId != null) {
                    // Use the callId from CallScreeningService for consistency
                    Log.d(TAG, "📞 Using callId from CallScreeningService: $storedCallId")
                    callId = storedCallId
                } else {
                    // Check if there's already a ringing call in Firebase
                    val existingCallId = findExistingRingingCall(phoneNumber)
                    if (existingCallId != null) {
                        Log.d(TAG, "📞 Found existing ringing call in Firebase: $existingCallId")
                        callId = existingCallId
                    } else {
                        // Generate new call ID
                        callId = System.currentTimeMillis().toString()
                        Log.d(TAG, "📞 Generated new callId: $callId")
                    }
                }

                currentCallId = callId
                Log.d(TAG, "📞 Using callId: $callId")

                // Get contact name
                val contactHelper = ContactHelper(this@CallMonitorService)
                val contactName = contactHelper.getContactName(phoneNumber)

                Log.d(TAG, "📞 Contact lookup done: phoneNumber=$phoneNumber, contactName=$contactName")

                // ALWAYS sync to Firebase - CallScreeningService's sync might have failed
                // (it can't reliably get userId). setValue will overwrite if exists, which is fine.
                Log.d(TAG, "📞 Syncing call to Firebase (always sync to ensure Mac gets notification)")

                // Sync to active_calls for real-time notification
                syncActiveCall(
                    callId = callId,
                    phoneNumber = phoneNumber,
                    contactName = contactName,
                    callState = "ringing"
                )

                Log.d(TAG, "📞 syncActiveCall returned, now syncing to call history")

                // Also sync to call history
                syncService.syncCallEvent(
                    callId = callId,
                    phoneNumber = phoneNumber,
                    contactName = contactName,
                    callType = "incoming",
                    callState = "ringing"
                )

                Log.d(TAG, "📞 Incoming call fully synced to Firebase")
            } catch (e: Exception) {
                Log.e(TAG, "📞 ERROR syncing incoming call: ${e.javaClass.simpleName}: ${e.message}", e)
            }
        }
    }

    /**
     * Check if there's already a ringing call entry in Firebase for this phone number.
     * This prevents duplicate entries when the service restarts during an incoming call.
     * Returns the existing callId if found, null otherwise.
     */
    private suspend fun findExistingRingingCall(phoneNumber: String): String? {
        return try {
            kotlinx.coroutines.withTimeout(2000) {
                val userId = syncService.getCurrentUserId()
                val activeCallsRef = database.reference
                    .child("users")
                    .child(userId)
                    .child("active_calls")

                val snapshot = activeCallsRef.get().await()
                val currentTime = System.currentTimeMillis()

                snapshot.children.forEach { callSnapshot ->
                    val existingCallId = callSnapshot.key ?: return@forEach
                    val state = callSnapshot.child("state").value as? String
                    val existingPhone = callSnapshot.child("phoneNumber").value?.toString()
                    val timestamp = callSnapshot.child("timestamp").value as? Long ?: 0L
                    val age = currentTime - timestamp

                    // Match if same phone number, ringing state, and recent (within 60 seconds)
                    if (state == "ringing" && age < 60000) {
                        // Normalize phone numbers for comparison (remove non-digits)
                        val normalizedExisting = existingPhone?.replace(Regex("[^0-9]"), "") ?: ""
                        val normalizedNew = phoneNumber.replace(Regex("[^0-9]"), "")

                        if (normalizedExisting == normalizedNew ||
                            normalizedExisting.endsWith(normalizedNew) ||
                            normalizedNew.endsWith(normalizedExisting)) {
                            Log.d(TAG, "📞 Found existing ringing call: $existingCallId for phone: $existingPhone (age: ${age}ms)")
                            return@withTimeout existingCallId
                        }
                    }
                }
                null
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.w(TAG, "📞 findExistingRingingCall timed out, creating new entry")
            null
        } catch (e: Exception) {
            Log.w(TAG, "📞 Error checking for existing ringing call: ${e.message}")
            null
        }
    }

    private fun onCallActive() {
        currentCallId?.let { callId ->
            serviceScope.launch {
                try {
                    // Update call state in history
                    syncService.updateCallState(callId, "active")

                    // Clear from active_calls to dismiss Mac notification immediately
                    // (answered calls don't need to stay in active_calls)
                    clearActiveCall(callId)

                    Log.d(TAG, "Call active state synced and cleared from active_calls")
                } catch (e: Exception) {
                    Log.e(TAG, "Error syncing call active state", e)
                }
            }
        }
    }

    private fun onCallEnded() {
        Log.d(TAG, "📞 onCallEnded called, currentCallId=$currentCallId")

        currentCallId?.let { callId ->
            Log.d(TAG, "📞 Syncing ended state to Firebase for call: $callId")
            serviceScope.launch {
                try {
                    syncService.updateCallState(callId, "ended")
                    clearActiveCall(callId)
                    Log.d(TAG, "📞 Call ended state synced to Firebase successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "📞 Error syncing call ended state", e)
                }
            }
        } ?: run {
            Log.w(TAG, "📞 onCallEnded called but currentCallId is null - cannot sync ended state")
        }

        currentCallId = null
        pendingPhoneNumber = null
        clearStoredPhoneNumber()

        // Auto-stop service after call ends to save battery
        // Delay to allow any final commands to be processed
        serviceScope.launch {
            delay(5000) // 5 second grace period
            Log.d(TAG, "📞 Auto-stopping CallMonitorService after call ended")
            stopSelf()
        }
    }

    /**
     * Sync active call to Firebase for real-time notifications on desktop
     */
    private suspend fun syncActiveCall(
        callId: String,
        phoneNumber: String,
        contactName: String?,
        callState: String
    ) {
        try {
            Log.d(TAG, "📞 syncActiveCall START - callId: $callId, state: $callState")

            // If this is a new ringing call, clean up any other ringing calls in BACKGROUND
            // Don't block the sync - Mac will select newest call by timestamp anyway
            // This prevents the 5-second timeout delay when Firebase is slow/offline
            if (callState == "ringing") {
                serviceScope.launch {
                    try {
                        Log.d(TAG, "📞 Cleaning up old ringing calls in background...")
                        cleanupOtherRingingCalls(callId)
                        Log.d(TAG, "📞 Background cleanup completed")
                    } catch (e: Exception) {
                        Log.w(TAG, "📞 Background cleanup failed (non-critical): ${e.message}")
                    }
                }
            }

            // Check Firebase auth status first
            val currentUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
            Log.d(TAG, "📞 Firebase auth check: currentUser=${currentUser?.uid ?: "NULL"}, isAnonymous=${currentUser?.isAnonymous}")

            if (currentUser == null) {
                Log.e(TAG, "📞 ERROR: Not signed in to Firebase! Cannot sync call.")
                return
            }

            val userId = syncService.getCurrentUserId()
            Log.d(TAG, "📞 Got userId from syncService: $userId")

            // Check Firebase connection status
            val connectedRef = database.getReference(".info/connected")
            connectedRef.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val connected = snapshot.getValue(Boolean::class.java) ?: false
                    Log.d(TAG, "📞 Firebase connection status: connected=$connected")
                }
                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "📞 Firebase connection check failed: ${error.message}")
                }
            })

            val activeCallRef = database.reference
                .child("users")
                .child(userId)
                .child("active_calls")
                .child(callId)

            val callData = mapOf(
                "id" to callId,
                "phoneNumber" to phoneNumber,
                "contactName" to (contactName ?: ""),
                "state" to callState,
                "timestamp" to ServerValue.TIMESTAMP
            )

            Log.d(TAG, "📞 About to write to Firebase path: users/$userId/active_calls/$callId")
            Log.d(TAG, "📞 Call data: phoneNumber=$phoneNumber, contactName=${contactName ?: "null"}, state=$callState")

            // Try direct setValue with await and timeout
            try {
                kotlinx.coroutines.withTimeout(10000) {
                    // First, try to go online explicitly
                    database.goOnline()
                    Log.d(TAG, "📞 Called database.goOnline()")

                    activeCallRef.setValue(callData).await()
                    Log.d(TAG, "📞 Firebase setValue completed via await()")
                }
                Log.d(TAG, "📞 SUCCESS: Active call synced to Firebase: $callId - $callState")
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Log.e(TAG, "📞 TIMEOUT: Firebase write took >10s")

                // Try alternative: use updateChildren which sometimes works better
                Log.d(TAG, "📞 Trying alternative: updateChildren...")
                try {
                    kotlinx.coroutines.withTimeout(5000) {
                        val updates = hashMapOf<String, Any>(
                            "users/$userId/active_calls/$callId" to callData
                        )
                        database.reference.updateChildren(updates).await()
                    }
                    Log.d(TAG, "📞 SUCCESS via updateChildren!")
                } catch (e2: Exception) {
                    Log.e(TAG, "📞 updateChildren also failed: ${e2.message}")
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.w(TAG, "📞 CANCELLED: Coroutine was cancelled during Firebase write", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "📞 ERROR syncing active call: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    /**
     * Update active call state
     */
    private suspend fun updateActiveCallState(callId: String, callState: String) {
        try {
            val userId = syncService.getCurrentUserId()
            val activeCallRef = database.reference
                .child("users")
                .child(userId)
                .child("active_calls")
                .child(callId)

            activeCallRef.child("state").setValue(callState).await()
            Log.d(TAG, "Active call state updated: $callId - $callState")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating active call state", e)
        }
    }

    /**
     * Update active call with real phone number (when received from InCallService)
     */
    private suspend fun updateActiveCallPhoneNumber(callId: String, phoneNumber: String, contactName: String?) {
        try {
            val userId = syncService.getCurrentUserId()
            val activeCallRef = database.reference
                .child("users")
                .child(userId)
                .child("active_calls")
                .child(callId)

            val updates = mapOf(
                "phoneNumber" to phoneNumber,
                "contactName" to (contactName ?: "")
            )
            activeCallRef.updateChildren(updates).await()
            Log.d(TAG, "Active call phone number updated: $callId - $phoneNumber")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating active call phone number", e)
        }
    }

    /**
     * Clear active call from Firebase when call ends
     */
    private suspend fun clearActiveCall(callId: String) {
        try {
            val userId = syncService.getCurrentUserId()
            val activeCallRef = database.reference
                .child("users")
                .child(userId)
                .child("active_calls")
                .child(callId)

            activeCallRef.removeValue().await()
            Log.d(TAG, "Active call cleared: $callId")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing active call", e)
        }
    }

    /**
     * Clean up other ringing calls when a new ringing call is detected.
     * This handles service restart scenarios where duplicate entries get created.
     * CRITICAL: Must complete before syncing new call to prevent Mac picking old calls.
     * Has a 5-second timeout (increased from 3s) to give Firebase more time.
     */
    private suspend fun cleanupOtherRingingCalls(currentCallId: String) {
        try {
            kotlinx.coroutines.withTimeout(5000) {
                val userId = syncService.getCurrentUserId()
                val activeCallsRef = database.reference
                    .child("users")
                    .child(userId)
                    .child("active_calls")

                val snapshot = activeCallsRef.get().await()
                var cleanedCount = 0
                val currentTime = System.currentTimeMillis()

                snapshot.children.forEach { callSnapshot ->
                    val otherCallId = callSnapshot.key ?: return@forEach
                    val state = callSnapshot.child("state").value as? String
                    val timestamp = callSnapshot.child("timestamp").value as? Long ?: 0L
                    val age = currentTime - timestamp

                    // Remove other ringing calls (not the current one)
                    // Also remove calls older than 60 seconds regardless of state
                    val shouldRemove = (otherCallId != currentCallId && state == "ringing") ||
                                      (otherCallId != currentCallId && age > 60000)

                    if (shouldRemove) {
                        Log.d(TAG, "📞 Cleaning up call: $otherCallId (state=$state, age=${age}ms, keeping $currentCallId)")
                        try {
                            callSnapshot.ref.removeValue().await()
                            cleanedCount++
                        } catch (e: Exception) {
                            Log.w(TAG, "📞 Failed to remove call $otherCallId: ${e.message}")
                        }
                    }
                }

                if (cleanedCount > 0) {
                    Log.d(TAG, "📞 Cleaned up $cleanedCount old/duplicate call(s)")
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.w(TAG, "📞 Cleanup timed out after 5s - Mac will select newest call by timestamp")
        } catch (e: Exception) {
            Log.w(TAG, "📞 Failed to clean up other ringing calls: ${e.message}")
        }
    }

    /**
     * Clean up stale active_calls entries on service startup.
     * This handles cases where the service was killed while a call was active.
     * Has a 5-second timeout to prevent blocking.
     */
    private fun cleanupStaleActiveCalls() {
        serviceScope.launch {
            try {
                Log.d(TAG, "Starting stale active_calls cleanup...")
                kotlinx.coroutines.withTimeout(5000) {
                    val userId = syncService.getCurrentUserId()
                    val activeCallsRef = database.reference
                        .child("users")
                        .child(userId)
                        .child("active_calls")

                    val currentTime = System.currentTimeMillis()
                    val maxAge = 60000L // 60 seconds - calls older than this are definitely stale

                    val snapshot = activeCallsRef.get().await()
                    var cleanedCount = 0

                    snapshot.children.forEach { callSnapshot ->
                        val callId = callSnapshot.key ?: return@forEach
                        val timestamp = callSnapshot.child("timestamp").value as? Long ?: 0L
                        val age = currentTime - timestamp

                        if (age > maxAge) {
                            Log.d(TAG, "Cleaning up stale active_call: $callId (age: ${age}ms)")
                            callSnapshot.ref.removeValue()
                            cleanedCount++
                        }
                    }

                    if (cleanedCount > 0) {
                        Log.d(TAG, "Cleaned up $cleanedCount stale active_call entries on startup")
                    } else {
                        Log.d(TAG, "No stale active_calls to clean up")
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Log.w(TAG, "Stale active_calls cleanup timed out after 5s")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clean up stale active_calls: ${e.message}")
            }
        }
    }

    private fun listenForCallCommands() {
        serviceScope.launch {
            try {
                val userId = syncService.getCurrentUserId()
                val commandsRef = database.reference
                    .child("users")
                    .child(userId)
                    .child("call_commands")

                // Get current timestamp - only listen for commands created AFTER this point
                val currentTime = System.currentTimeMillis()
                Log.d(TAG, "Setting up call commands listener at: users/$userId/call_commands (ignoring commands older than $currentTime)")

                // Clean up old unprocessed commands to prevent them from being processed
                // Use a timeout to prevent blocking if Firebase is slow
                try {
                    kotlinx.coroutines.withTimeout(3000) {
                        val oldCommandsSnapshot = commandsRef.get().await()
                        var cleanedCount = 0
                        oldCommandsSnapshot.children.forEach { snapshot ->
                            val processed = snapshot.child("processed").value as? Boolean ?: false
                            val timestamp = snapshot.child("timestamp").value as? Long ?: 0L
                            val age = currentTime - timestamp
                            // Mark old unprocessed commands as processed
                            if (!processed && age > 5000) {
                                snapshot.ref.child("processed").setValue(true)
                                cleanedCount++
                            }
                        }
                        if (cleanedCount > 0) {
                            Log.d(TAG, "Cleaned up $cleanedCount stale call commands on startup")
                        }
                    }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    Log.w(TAG, "Call commands cleanup timed out after 3s")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to clean up old commands: ${e.message}")
                }

                // Listen for NEW commands by ordering by timestamp and starting from now
                val listener = object : com.google.firebase.database.ChildEventListener {
                    override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                        Log.d(TAG, "onChildAdded triggered! Key: ${snapshot.key}, exists: ${snapshot.exists()}")

                        val command = snapshot.child("command").value as? String
                        val callId = snapshot.child("callId").value as? String
                        val phoneNumber = snapshot.child("phoneNumber").value as? String
                        val processed = snapshot.child("processed").value as? Boolean ?: false
                        val timestamp = snapshot.child("timestamp").value as? Long ?: 0L

                        Log.d(TAG, "Command data: command=$command, callId=$callId, phoneNumber=$phoneNumber, processed=$processed, timestamp=$timestamp")

                        // Process unprocessed commands
                        if (!processed && command != null) {
                            val commandAge = System.currentTimeMillis() - timestamp
                            Log.d(TAG, "Received call command: $command for call: $callId (age: ${commandAge}ms)")

                            // CRITICAL: Validate the command before processing
                            val currentCall = currentCallId
                            val isValidCommand = when {
                                // Command is too old (>10 seconds) - reject it
                                // Increased from 5s to 10s to handle service restart delays
                                commandAge > 10000 -> {
                                    Log.w(TAG, "IGNORING stale command (age: ${commandAge}ms > 10000ms)")
                                    false
                                }
                                // No active call tracked - BUT still try answer/reject!
                                // TelecomManager.acceptRingingCall() doesn't need a call ID,
                                // it will answer whatever is currently ringing.
                                // This handles service restart scenarios where currentCallId
                                // hasn't been set yet by TelephonyCallback
                                currentCall == null && (command == "answer" || command == "reject" || command == "end") -> {
                                    Log.w(TAG, "No tracked call (currentCallId is null) but ATTEMPTING command anyway - TelecomManager will handle it")
                                    true // Still try! acceptRingingCall/endCall don't need our tracking
                                }
                                // Command is for a different call - but we have an active call
                                // Be lenient: if user wants to answer/reject, do it for the CURRENT call
                                // This handles service restart scenarios where call IDs get regenerated
                                callId != null && currentCall != null && callId != currentCall -> {
                                    Log.w(TAG, "Command callId mismatch (command=$callId, current=$currentCall) - processing for CURRENT call anyway")
                                    true // Process it anyway for the current call
                                }
                                else -> true
                            }

                            if (isValidCommand) {
                                // Always use currentCall for answer/reject/end to handle ID mismatches
                                val effectiveCallId = if (command in listOf("answer", "reject", "end")) {
                                    currentCall ?: callId
                                } else {
                                    callId ?: currentCall
                                }
                                Log.d(TAG, "Processing VALID command: $command for call: $effectiveCallId")
                                handleCallCommand(command, effectiveCallId, phoneNumber)
                            }

                            // Mark as processed regardless (to prevent reprocessing)
                            snapshot.ref.child("processed").setValue(true)
                        } else {
                            Log.d(TAG, "Ignoring command: processed=$processed, command=$command")
                        }
                    }

                    override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                        Log.d(TAG, "onChildChanged triggered! Key: ${snapshot.key}")
                    }

                    override fun onChildRemoved(snapshot: DataSnapshot) {
                        Log.d(TAG, "onChildRemoved triggered! Key: ${snapshot.key}")
                    }

                    override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {
                        Log.d(TAG, "onChildMoved triggered! Key: ${snapshot.key}")
                    }

                    override fun onCancelled(error: DatabaseError) {
                        Log.e(TAG, "Error listening for call commands", error.toException())
                    }
                }

                callCommandsListener?.let { existing ->
                    callCommandsQuery?.removeEventListener(existing)
                }
                // NOTE: Removed startAt(currentTime) filter to fix multiple Answer clicks issue
                // When the service restarts (e.g., when answer button triggers service restart),
                // the old startAt filter would cause the listener to miss commands sent before
                // the listener started. We now rely on:
                // 1. processed flag - commands already processed are skipped
                // 2. age check (10s) - commands older than 10 seconds are ignored
                // This allows the listener to receive commands that were sent during service restart
                callCommandsQuery = commandsRef.orderByChild("timestamp")
                callCommandsListener = listener
                callCommandsQuery?.addChildEventListener(listener)

                Log.d(TAG, "Call commands listener attached successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up call commands listener", e)
            }
        }
    }

    private fun listenForCallRequests() {
        serviceScope.launch {
            try {
                Log.d(TAG, "Setting up call requests listener...")
                val userId = syncService.getCurrentUserId()
                Log.d(TAG, "Got userId: $userId")
                val requestsRef = database.reference
                    .child("users")
                    .child(userId)
                    .child("call_requests")

                Log.d(TAG, "Attaching Firebase listener to: users/$userId/call_requests")
                val listener = object : com.google.firebase.database.ChildEventListener {
                    override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                        val requestId = snapshot.key ?: return
                        val phoneNumber = snapshot.child("phoneNumber").value as? String
                        val status = snapshot.child("status").value as? String ?: "pending"

                        if (status == "pending" && phoneNumber != null) {
                            Log.d(TAG, "Received call request for: $phoneNumber")
                            processCallRequest(requestId, phoneNumber, snapshot.ref)
                        }
                    }

                    override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
                    override fun onChildRemoved(snapshot: DataSnapshot) {}
                    override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
                    override fun onCancelled(error: DatabaseError) {
                        Log.e(TAG, "Error listening for call requests", error.toException())
                    }
                }

                callRequestsListener?.let { existing ->
                    callRequestsRef?.removeEventListener(existing)
                }
                callRequestsRef = requestsRef
                callRequestsListener = listener
                requestsRef.addChildEventListener(listener)

                Log.d(TAG, "Listening for call requests from Firebase")
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up call requests listener", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun processCallRequest(requestId: String, phoneNumber: String, requestRef: com.google.firebase.database.DatabaseReference) {
        if (!checkCallPermissions()) {
            Log.e(TAG, "Missing permissions to process call request")
            // Update status to failed
            requestRef.updateChildren(mapOf(
                "status" to "failed",
                "error" to "Missing permissions",
                "completedAt" to ServerValue.TIMESTAMP
            ))
            return
        }

        serviceScope.launch(Dispatchers.IO) {
            try {
                // Get the request data to check for SIM selection
                val snapshot = requestRef.get().await()
                val requestData = snapshot.value as? Map<*, *>
                val requestedSimSubId = requestData?.get("simSubscriptionId") as? Number

                // Update status to calling
                requestRef.updateChildren(mapOf(
                    "status" to "calling"
                )).await()

                // Make the call with specific SIM if requested
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && requestedSimSubId != null) {
                    // Use specific SIM card
                    makeCallWithSim(phoneNumber, requestedSimSubId.toInt())
                } else {
                    // Use default SIM
                    makeCallDefault(phoneNumber)
                }

                Log.d(TAG, "Initiated call from web/desktop request to $phoneNumber" +
                        if (requestedSimSubId != null) " using SIM subscription $requestedSimSubId" else "")

                // Update status to completed
                requestRef.updateChildren(mapOf(
                    "status" to "completed",
                    "completedAt" to ServerValue.TIMESTAMP
                )).await()

                // Sync call event to Firebase
                val newCallId = System.currentTimeMillis().toString()
                try {
                    val contactHelper = ContactHelper(this@CallMonitorService)
                    val contactName = contactHelper.getContactName(phoneNumber)

                    syncService.syncCallEvent(
                        callId = newCallId,
                        phoneNumber = phoneNumber,
                        contactName = contactName,
                        callType = "outgoing",
                        callState = "dialing"
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error syncing call request event", e)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing call request to $phoneNumber", e)
                // Update status to failed
                requestRef.updateChildren(mapOf(
                    "status" to "failed",
                    "error" to e.message,
                    "completedAt" to ServerValue.TIMESTAMP
                ))
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun makeCallWithSim(phoneNumber: String, subscriptionId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                // Find the PhoneAccountHandle for this subscription ID
                // The phone account ID is the subscription ID as a string
                val callCapableAccounts = telecomManager.callCapablePhoneAccounts
                Log.d(TAG, "Looking for PhoneAccount with subscription ID: $subscriptionId")
                Log.d(TAG, "Available call-capable accounts: ${callCapableAccounts.size}")
                callCapableAccounts.forEach { handle ->
                    Log.d(TAG, "  - Account ID: ${handle.id}, Component: ${handle.componentName}")
                }

                val phoneAccountHandle = callCapableAccounts.find { handle ->
                    handle.id == subscriptionId.toString()
                }

                if (phoneAccountHandle != null) {
                    // Use TelecomManager.placeCall() instead of startActivity()
                    // This is the proper API for making calls from a service
                    val uri = Uri.parse("tel:$phoneNumber")
                    val extras = android.os.Bundle().apply {
                        putParcelable("android.telecom.extra.PHONE_ACCOUNT_HANDLE", phoneAccountHandle)
                    }

                    Log.d(TAG, "Placing call with PhoneAccount: ${phoneAccountHandle.id} (subscription $subscriptionId) to $phoneNumber")
                    telecomManager.placeCall(uri, extras)
                    Log.d(TAG, "Call placed successfully via TelecomManager")
                } else {
                    Log.w(TAG, "Could not find PhoneAccount for subscription $subscriptionId, using default")
                    makeCallDefault(phoneNumber)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error making call with specific SIM, falling back to default", e)
                makeCallDefault(phoneNumber)
            }
        } else {
            makeCallDefault(phoneNumber)
        }
    }

    @SuppressLint("MissingPermission")
    private fun makeCallDefault(phoneNumber: String) {
        try {
            // Use TelecomManager.placeCall() for Android M+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val uri = Uri.parse("tel:$phoneNumber")
                telecomManager.placeCall(uri, android.os.Bundle())
                Log.d(TAG, "Call placed successfully via TelecomManager (default SIM)")
            } else {
                // Fallback to Intent for older versions
                val callIntent = Intent(Intent.ACTION_CALL).apply {
                    data = Uri.parse("tel:$phoneNumber")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(callIntent)
                Log.d(TAG, "Call started via Intent (API < 23)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error placing call to $phoneNumber", e)
        }
    }

    private fun syncSimInformation() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val sims = simManager.getActiveSims()
                Log.d(TAG, "Detected ${sims.size} active SIM(s)")

                sims.forEachIndexed { index, sim ->
                    Log.d(TAG, "SIM $index: ${simManager.getSimDisplayName(sim)}")
                }

                // Sync to Firebase
                simManager.syncSimsToFirebase(syncService)
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing SIM information", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleCallCommand(command: String, callId: String?, phoneNumber: String?) {
        Log.d(TAG, "Handling call command: $command (callId: $callId, phoneNumber: $phoneNumber)")

        if (!checkCallPermissions()) {
            Log.e(TAG, "Missing permissions to handle call command")
            return
        }

        try {
            when (command) {
                "answer" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        Log.d(TAG, "Attempting to answer call via TelecomManager.acceptRingingCall()...")
                        telecomManager.acceptRingingCall()
                        Log.d(TAG, "Accept ringing call executed")

                        // Update active call state
                        callId?.let { id ->
                            serviceScope.launch {
                                delay(500) // Small delay to let call connect
                                updateActiveCallState(id, "active")
                            }
                        }
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        Log.d(TAG, "Attempting to answer call via InCallService (Android 6-7)...")
                        DesktopInCallService.answerCall()
                        Log.d(TAG, "Answer command sent to InCallService")

                        callId?.let { id ->
                            serviceScope.launch {
                                updateActiveCallState(id, "active")
                            }
                        }
                    } else {
                        Log.w(TAG, "Answer call not supported on API < 23 (current: ${Build.VERSION.SDK_INT})")
                    }
                }
                "reject", "end" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        Log.d(TAG, "Attempting to end call via TelecomManager.endCall()...")
                        val result = telecomManager.endCall()
                        Log.d(TAG, "End call result: $result")

                        // Clear active call
                        callId?.let { id ->
                            serviceScope.launch {
                                delay(300)
                                clearActiveCall(id)
                            }
                        }
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        Log.d(TAG, "Attempting to end call via InCallService (Android 6-8)...")
                        DesktopInCallService.endCall()
                        Log.d(TAG, "End command sent to InCallService")

                        callId?.let { id ->
                            serviceScope.launch {
                                clearActiveCall(id)
                            }
                        }
                    } else {
                        Log.w(TAG, "End call not supported on API < 23 (current: ${Build.VERSION.SDK_INT})")
                    }
                }
                "make_call" -> {
                    if (phoneNumber != null) {
                        makeOutgoingCall(phoneNumber)
                    } else {
                        Log.e(TAG, "Cannot make call: phone number is null")
                    }
                }
                else -> {
                    Log.w(TAG, "Unknown call command: $command")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling call command: $command", e)
            e.printStackTrace()
        }
    }

    @SuppressLint("MissingPermission")
    private fun makeOutgoingCall(phoneNumber: String) {
        try {
            // Use TelecomManager.placeCall() for Android M+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val uri = Uri.parse("tel:$phoneNumber")
                telecomManager.placeCall(uri, android.os.Bundle())
                Log.d(TAG, "Initiated outgoing call to $phoneNumber via TelecomManager")
            } else {
                // Fallback to Intent for older versions
                val callIntent = Intent(Intent.ACTION_CALL).apply {
                    data = Uri.parse("tel:$phoneNumber")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(callIntent)
                Log.d(TAG, "Initiated outgoing call to $phoneNumber via Intent")
            }

            // Sync outgoing call to Firebase
            val newCallId = System.currentTimeMillis().toString()
            serviceScope.launch {
                try {
                    val contactHelper = ContactHelper(this@CallMonitorService)
                    val contactName = contactHelper.getContactName(phoneNumber)

                    syncService.syncCallEvent(
                        callId = newCallId,
                        phoneNumber = phoneNumber,
                        contactName = contactName,
                        callType = "outgoing",
                        callState = "dialing"
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error syncing outgoing call", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error making outgoing call to $phoneNumber", e)
        }
    }

    @SuppressLint("MissingPermission", "Range")
    private fun syncCallHistory() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                // Check if we have READ_CALL_LOG permission
                val hasCallLogPermission = ActivityCompat.checkSelfPermission(
                    this@CallMonitorService,
                    Manifest.permission.READ_CALL_LOG
                ) == PackageManager.PERMISSION_GRANTED

                if (!hasCallLogPermission) {
                    Log.w(TAG, "READ_CALL_LOG permission not granted")
                    return@launch
                }

                val userId = syncService.getCurrentUserId()
                val callLogRef = database.reference
                    .child("users")
                    .child(userId)
                    .child("call_history")

                // Query recent calls (last 100)
                val cursor = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    // Use Bundle for API 26+
                    val queryArgs = android.os.Bundle().apply {
                        putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, 100)
                        putStringArray(
                            android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS,
                            arrayOf(CallLog.Calls.DATE)
                        )
                        putInt(
                            android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION,
                            android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING
                        )
                    }
                    contentResolver.query(
                        CallLog.Calls.CONTENT_URI,
                        arrayOf(
                            CallLog.Calls._ID,
                            CallLog.Calls.NUMBER,
                            CallLog.Calls.TYPE,
                            CallLog.Calls.DATE,
                            CallLog.Calls.DURATION,
                            CallLog.Calls.CACHED_NAME
                        ),
                        queryArgs,
                        null
                    )
                } else {
                    // Fallback for older APIs - just use DESC without LIMIT
                    contentResolver.query(
                        CallLog.Calls.CONTENT_URI,
                        arrayOf(
                            CallLog.Calls._ID,
                            CallLog.Calls.NUMBER,
                            CallLog.Calls.TYPE,
                            CallLog.Calls.DATE,
                            CallLog.Calls.DURATION,
                            CallLog.Calls.CACHED_NAME
                        ),
                        null,
                        null,
                        "${CallLog.Calls.DATE} DESC"
                    )
                }

                cursor?.use {
                    val contactHelper = ContactHelper(this@CallMonitorService)

                    while (it.moveToNext()) {
                        val callId = it.getLong(it.getColumnIndex(CallLog.Calls._ID))
                        val number = it.getString(it.getColumnIndex(CallLog.Calls.NUMBER)) ?: "Unknown"
                        val callType = it.getInt(it.getColumnIndex(CallLog.Calls.TYPE))
                        val callDate = it.getLong(it.getColumnIndex(CallLog.Calls.DATE))
                        val duration = it.getLong(it.getColumnIndex(CallLog.Calls.DURATION))
                        val cachedName = it.getString(it.getColumnIndex(CallLog.Calls.CACHED_NAME))

                        // Get contact name (try cached first, then lookup)
                        val contactName = cachedName ?: contactHelper.getContactName(number)

                        // Determine call type string
                        val typeString = when (callType) {
                            CallLog.Calls.INCOMING_TYPE -> "incoming"
                            CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                            CallLog.Calls.MISSED_TYPE -> "missed"
                            CallLog.Calls.REJECTED_TYPE -> "rejected"
                            else -> "unknown"
                        }

                        // Sync to Firebase
                        val callData = mapOf(
                            "id" to callId,
                            "phoneNumber" to number,
                            "contactName" to (contactName ?: number),
                            "callType" to typeString,
                            "date" to callDate,
                            "duration" to duration,
                            "timestamp" to ServerValue.TIMESTAMP
                        )

                        callLogRef.child(callId.toString()).setValue(callData)
                    }

                    Log.d(TAG, "Call history synced: ${it.count} calls")
                } ?: Log.w(TAG, "Call log cursor is null")

            } catch (e: Exception) {
                Log.e(TAG, "Error syncing call history", e)
            }
        }
    }

    private fun checkCallPermissions(): Boolean {
        val hasReadPhoneState = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        val hasAnswerCalls = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ANSWER_PHONE_CALLS
            ) == PackageManager.PERMISSION_GRANTED
        } else true

        return hasReadPhoneState && hasAnswerCalls
    }

    private fun stopFirebaseListeners() {
        callCommandsListener?.let { listener ->
            callCommandsQuery?.removeEventListener(listener)
        }
        callCommandsListener = null
        callCommandsQuery = null

        callRequestsListener?.let { listener ->
            callRequestsRef?.removeEventListener(listener)
        }
        callRequestsListener = null
        callRequestsRef = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Call Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors phone calls for web integration"
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SyncFlow Call Monitor")
            .setContentText("Monitoring calls for web integration")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        return builder.build()
    }
}
