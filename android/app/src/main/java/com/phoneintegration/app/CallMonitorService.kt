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
import com.phoneintegration.app.BuildConfig
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.phoneintegration.app.vps.VPSCallCommand
import com.phoneintegration.app.vps.VPSCallRequest
import com.phoneintegration.app.vps.VPSSyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service that monitors telephony call state (ringing/active/ended) and syncs it
 * to the VPS server so paired desktop/web clients see live call status. Also processes call
 * commands (answer/reject/end/make_call) received from desktop via WebSocket, and syncs the
 * device call log on startup. Uses InCallService broadcasts for fast caller-ID resolution.
 */
class CallMonitorService : Service() {

    companion object {
        private const val TAG = "CallMonitorService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "call_monitor_channel"
        // Auto-stop after this period if no active call (on-demand service, not persistent)
        private const val IDLE_TIMEOUT_MS = 30_000L // 30 seconds

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
    private var idleTimeoutJob: Job? = null
    private lateinit var telephonyManager: TelephonyManager
    private lateinit var telecomManager: TelecomManager
    private lateinit var vpsSyncService: VPSSyncService
    private lateinit var simManager: SimManager

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
            if (intent?.action == DesktopInCallService.ACTION_INCOMING_CALL) {
                val phoneNumber = intent.getStringExtra(DesktopInCallService.EXTRA_PHONE_NUMBER)
                val callIdFromBroadcast = intent.getStringExtra("call_id")
                val callState = intent.getStringExtra(DesktopInCallService.EXTRA_CALL_STATE)

                if (!phoneNumber.isNullOrEmpty()) {
                    pendingPhoneNumber = phoneNumber

                    if (!callIdFromBroadcast.isNullOrEmpty()) {
                        currentCallId = callIdFromBroadcast

                        // OPTIMIZATION: Sync to VPS IMMEDIATELY when we receive the broadcast
                        if (callState == "ringing") {
                            serviceScope.launch(NonCancellable) {
                                try {
                                    val contactHelper = ContactHelper(this@CallMonitorService)
                                    val contactName = contactHelper.getContactName(phoneNumber)

                                    // Sync to VPS
                                    syncActiveCall(
                                        callId = callIdFromBroadcast,
                                        phoneNumber = phoneNumber,
                                        contactName = contactName,
                                        callState = "ringing"
                                    )

                                    // Also sync to call history
                                    syncCallEvent(
                                        callId = callIdFromBroadcast,
                                        phoneNumber = phoneNumber,
                                        contactName = contactName,
                                        callType = "incoming",
                                        callState = "ringing"
                                    )
                                } catch (e: Exception) {
                                    Log.e(TAG, "Fast path error: ${e.message}", e)
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
                                    // VPS doesn't need partial updates - sync is idempotent
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
        if (BuildConfig.DEBUG) Log.d(TAG, "CallMonitorService created (VPS backend)")

        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        vpsSyncService = VPSSyncService.getInstance(this)
        simManager = SimManager(this)

        // Restore currentCallId from SharedPreferences in case service was recreated
        val storedCallId = getStoredCallId()
        if (storedCallId != null) {
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

        registerCallLogObserver()
        registerCallStateListener()
        listenForCallCommands()
        listenForCallRequests()
        // Call history and SIM sync are done at pairing time, not here
        startIdleTimeout()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (BuildConfig.DEBUG) Log.d(TAG, "CallMonitorService started")
        // Poll for pending call requests on each start (handles FCM wake-up + missed WebSocket events)
        pollPendingCallRequests()
        // Reset idle timeout — a new start command means something needs us
        resetIdleTimeout()
        return START_NOT_STICKY // Don't auto-restart — FCM will wake us when needed
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Start idle timeout — auto-stops service if no active call within IDLE_TIMEOUT_MS.
     * Called on startup and reset on each onStartCommand.
     */
    private fun startIdleTimeout() {
        idleTimeoutJob?.cancel()
        idleTimeoutJob = serviceScope.launch {
            delay(IDLE_TIMEOUT_MS)
            if (currentCallId == null) {
                Log.d(TAG, "Idle timeout reached, no active call — stopping service")
                stopSelf()
            }
        }
    }

    private fun resetIdleTimeout() {
        startIdleTimeout()
    }

    private fun cancelIdleTimeout() {
        idleTimeoutJob?.cancel()
        idleTimeoutJob = null
    }

    override fun onDestroy() {
        super.onDestroy()
        if (BuildConfig.DEBUG) Log.d(TAG, "CallMonitorService destroyed")
        idleTimeoutJob?.cancel()
        try {
            unregisterReceiver(incomingCallReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering receiver", e)
        }
        unregisterCallLogObserver()
        unregisterCallStateListener()
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
                // If we're waiting for a phone number and a call is ringing
                if (currentCallId != null && pendingPhoneNumber.isNullOrEmpty()) {
                    serviceScope.launch {
                        val number = getLastIncomingCallNumber()
                        if (!number.isNullOrEmpty() && number != "Unknown") {
                            pendingPhoneNumber = number
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
    }

    private fun unregisterCallLogObserver() {
        callLogObserver?.let {
            contentResolver.unregisterContentObserver(it)
            callLogObserver = null
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
                    if (BuildConfig.DEBUG) Log.d(TAG, "Call state listener registered")
                }
            } else {
                @Suppress("DEPRECATION")
                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
                if (BuildConfig.DEBUG) Log.d(TAG, "Call state listener registered")
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
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering call state listener", e)
        }
    }

    // Telephony state machine: RINGING → OFFHOOK (answered) → IDLE (ended).
    // Phone number may come from TelephonyCallback, InCallService broadcast, SharedPreferences,
    // or call log fallback - we try each source in priority order.
    private fun handleCallStateChange(state: Int, phoneNumber: String?) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                serviceScope.launch {
                    val number = when {
                        !phoneNumber.isNullOrEmpty() && phoneNumber != "Unknown" -> {
                            phoneNumber
                        }
                        !pendingPhoneNumber.isNullOrEmpty() -> {
                            pendingPhoneNumber!!
                        }
                        else -> {
                            val storedNumber = getStoredPhoneNumber()
                            if (storedNumber != null) {
                                storedNumber
                            } else {
                                val callLogNumber = getLastIncomingCallNumber()
                                callLogNumber ?: "Unknown"
                            }
                        }
                    }
                    onIncomingCall(number)
                }
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                onCallActive()
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                onCallEnded()
            }
        }
    }

    private fun getStoredPhoneNumber(): String? {
        try {
            val prefs = getSharedPreferences("call_screening_prefs", MODE_PRIVATE)
            val storedNumber = prefs.getString("last_incoming_number", null)
            val storedTimestamp = prefs.getLong("last_incoming_timestamp", 0L)
            val age = System.currentTimeMillis() - storedTimestamp

            if (storedNumber != null && age < 30000) {
                return storedNumber
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading stored phone number", e)
        }
        return null
    }

    private fun getStoredCallId(): String? {
        try {
            val prefs = getSharedPreferences("call_screening_prefs", MODE_PRIVATE)
            val storedCallId = prefs.getString("last_incoming_call_id", null)
            val storedTimestamp = prefs.getLong("last_incoming_timestamp", 0L)
            val age = System.currentTimeMillis() - storedTimestamp

            if (storedCallId != null && age < 60000) {
                return storedCallId
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading stored callId", e)
        }
        return null
    }

    private fun clearStoredPhoneNumber() {
        try {
            val prefs = getSharedPreferences("call_screening_prefs", MODE_PRIVATE)
            prefs.edit().clear().apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing stored phone number", e)
        }
    }

    @SuppressLint("MissingPermission", "Range")
    private suspend fun getLastIncomingCallNumber(): String? = withContext(Dispatchers.IO) {
        try {
            val hasPermission = ActivityCompat.checkSelfPermission(
                this@CallMonitorService,
                Manifest.permission.READ_CALL_LOG
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasPermission) {
                Log.w(TAG, "READ_CALL_LOG permission not granted")
                return@withContext null
            }

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
        cancelIdleTimeout() // Active call — don't auto-stop
        serviceScope.launch(NonCancellable) {
            try {
                val storedCallId = getStoredCallId()
                val callId = storedCallId ?: System.currentTimeMillis().toString()
                currentCallId = callId

                val contactHelper = ContactHelper(this@CallMonitorService)
                val contactName = contactHelper.getContactName(phoneNumber)

                // Sync to VPS
                syncActiveCall(
                    callId = callId,
                    phoneNumber = phoneNumber,
                    contactName = contactName,
                    callState = "ringing"
                )

                // Also sync to call history
                syncCallEvent(
                    callId = callId,
                    phoneNumber = phoneNumber,
                    contactName = contactName,
                    callType = "incoming",
                    callState = "ringing"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing incoming call: ${e.message}", e)
            }
        }
    }

    private fun onCallActive() {
        currentCallId?.let { callId ->
            serviceScope.launch {
                try {
                    vpsSyncService.updateActiveCallState(callId, "active")
                    vpsSyncService.clearActiveCall(callId) // Clear from active_calls to dismiss notification
                } catch (e: Exception) {
                    Log.e(TAG, "Error syncing call active state", e)
                }
            }
        }
    }

    private fun onCallEnded() {
        currentCallId?.let { callId ->
            serviceScope.launch {
                try {
                    vpsSyncService.updateActiveCallState(callId, "ended")
                    vpsSyncService.clearActiveCall(callId)
                } catch (e: Exception) {
                    Log.e(TAG, "Error syncing call ended state", e)
                }
            }
        }

        currentCallId = null
        pendingPhoneNumber = null
        clearStoredPhoneNumber()

        // Auto-stop service after call ends
        serviceScope.launch {
            delay(5000)
            stopSelf()
        }
    }

    private suspend fun syncActiveCall(
        callId: String,
        phoneNumber: String,
        contactName: String?,
        callState: String
    ) {
        try {
            vpsSyncService.syncActiveCall(
                callId = callId,
                phoneNumber = phoneNumber,
                contactName = contactName,
                state = callState,
                callType = "incoming"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing active call: ${e.message}", e)
        }
    }

    private suspend fun syncCallEvent(
        callId: String,
        phoneNumber: String,
        contactName: String?,
        callType: String,
        callState: String
    ) {
        try {
            val callData = mapOf(
                "id" to callId,
                "phoneNumber" to phoneNumber,
                "contactName" to (contactName ?: phoneNumber),
                "callType" to callType,
                "callDate" to System.currentTimeMillis(),
                "duration" to 0L
            )
            vpsSyncService.syncCallHistory(listOf(callData))
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing call event", e)
        }
    }

    private fun listenForCallCommands() {
        serviceScope.launch {
            try {
                vpsSyncService.callCommands.collect { command ->
                    if (!command.processed) {
                        val commandAge = System.currentTimeMillis() - command.timestamp
                        if (commandAge <= 10000) {
                            handleCallCommand(command.command, command.callId, command.phoneNumber)
                        } else {
                            Log.w(TAG, "IGNORING stale command (age: ${commandAge}ms)")
                        }

                        // Mark as processed
                        vpsSyncService.markCallCommandProcessed(command.id)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in call commands listener", e)
            }
        }
    }

    private fun listenForCallRequests() {
        serviceScope.launch {
            try {
                vpsSyncService.callRequests.collect { request ->
                    if (request.status == "pending") {
                        processCallRequest(request)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in call requests listener", e)
            }
        }
    }

    private fun pollPendingCallRequests() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val requests = vpsSyncService.getCallRequests()
                for (request in requests) {
                    if (request.status == "pending") {
                        processCallRequest(request)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error polling pending call requests", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun processCallRequest(request: VPSCallRequest) {
        if (!checkCallPermissions()) {
            Log.e(TAG, "Missing permissions to process call request")
            serviceScope.launch {
                vpsSyncService.updateCallRequestStatus(request.id, "failed")
            }
            return
        }

        serviceScope.launch(Dispatchers.IO) {
            try {
                vpsSyncService.updateCallRequestStatus(request.id, "calling")

                // Make the call
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && request.simSubscriptionId != null) {
                    makeCallWithSim(request.phoneNumber, request.simSubscriptionId)
                } else {
                    makeCallDefault(request.phoneNumber)
                }

                vpsSyncService.updateCallRequestStatus(request.id, "completed")

                // Sync call event
                val newCallId = System.currentTimeMillis().toString()
                val contactHelper = ContactHelper(this@CallMonitorService)
                val contactName = contactHelper.getContactName(request.phoneNumber)

                syncCallEvent(
                    callId = newCallId,
                    phoneNumber = request.phoneNumber,
                    contactName = contactName,
                    callType = "outgoing",
                    callState = "dialing"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error processing call request", e)
                vpsSyncService.updateCallRequestStatus(request.id, "failed")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun makeCallWithSim(phoneNumber: String, subscriptionId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val callCapableAccounts = telecomManager.callCapablePhoneAccounts
                val phoneAccountHandle = callCapableAccounts.find { handle ->
                    handle.id == subscriptionId.toString()
                }

                if (phoneAccountHandle != null) {
                    val uri = Uri.parse("tel:$phoneNumber")
                    val extras = android.os.Bundle().apply {
                        putParcelable("android.telecom.extra.PHONE_ACCOUNT_HANDLE", phoneAccountHandle)
                    }
                    telecomManager.placeCall(uri, extras)
                } else {
                    makeCallDefault(phoneNumber)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error making call with specific SIM", e)
                makeCallDefault(phoneNumber)
            }
        } else {
            makeCallDefault(phoneNumber)
        }
    }

    @SuppressLint("MissingPermission")
    private fun makeCallDefault(phoneNumber: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val uri = Uri.parse("tel:$phoneNumber")
                telecomManager.placeCall(uri, android.os.Bundle())
            } else {
                val callIntent = Intent(Intent.ACTION_CALL).apply {
                    data = Uri.parse("tel:$phoneNumber")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(callIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error placing call", e)
        }
    }

    private fun syncSimInformation() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val sims = simManager.getActiveSims()
                // TODO: Sync SIM info to VPS when endpoint is available
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing SIM information", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleCallCommand(command: String, callId: String?, phoneNumber: String?) {
        Log.d(TAG, "handleCallCommand: command=$command, callId=$callId, hasInCallService=${DesktopInCallService.hasActiveCall()}, callState=${DesktopInCallService.getCallState()}")

        val hasAnswerPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED
        } else true
        val hasReadPhoneState = ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        Log.d(TAG, "Permissions: ANSWER_PHONE_CALLS=$hasAnswerPermission, READ_PHONE_STATE=$hasReadPhoneState")

        try {
            when (command) {
                "answer" -> {
                    var answered = false

                    // Method 1: InCallService (most reliable if bound)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && DesktopInCallService.hasActiveCall()) {
                        Log.d(TAG, "Answering via InCallService")
                        DesktopInCallService.answerCall()
                        answered = true
                    }

                    // Method 2: TelecomManager (requires ANSWER_PHONE_CALLS permission)
                    if (!answered && hasAnswerPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        Log.d(TAG, "Answering via TelecomManager.acceptRingingCall()")
                        @Suppress("DEPRECATION")
                        telecomManager.acceptRingingCall()
                        answered = true
                    }

                    // Method 3: Media key simulation (no special permissions needed)
                    if (!answered) {
                        Log.d(TAG, "Answering via media key simulation")
                        answerViaMediaKey()
                    }

                    callId?.let { id ->
                        serviceScope.launch {
                            delay(500)
                            vpsSyncService.updateActiveCallState(id, "active")
                        }
                    }
                }
                "reject", "end" -> {
                    var ended = false

                    // Method 1: InCallService
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && DesktopInCallService.hasActiveCall()) {
                        Log.d(TAG, "Ending via InCallService")
                        DesktopInCallService.endCall()
                        ended = true
                    }

                    // Method 2: TelecomManager
                    if (!ended && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        Log.d(TAG, "Ending via TelecomManager.endCall()")
                        @Suppress("DEPRECATION")
                        telecomManager.endCall()
                        ended = true
                    }

                    callId?.let { id ->
                        serviceScope.launch {
                            delay(300)
                            vpsSyncService.clearActiveCall(id)
                        }
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
        }
    }

    @SuppressLint("MissingPermission")
    private fun makeOutgoingCall(phoneNumber: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val uri = Uri.parse("tel:$phoneNumber")
                telecomManager.placeCall(uri, android.os.Bundle())
            } else {
                val callIntent = Intent(Intent.ACTION_CALL).apply {
                    data = Uri.parse("tel:$phoneNumber")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(callIntent)
            }

            // Sync outgoing call
            val newCallId = System.currentTimeMillis().toString()
            serviceScope.launch {
                try {
                    val contactHelper = ContactHelper(this@CallMonitorService)
                    val contactName = contactHelper.getContactName(phoneNumber)

                    syncCallEvent(
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
            Log.e(TAG, "Error making outgoing call", e)
        }
    }

    @SuppressLint("MissingPermission", "Range")
    private fun syncCallHistory() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val hasCallLogPermission = ActivityCompat.checkSelfPermission(
                    this@CallMonitorService,
                    Manifest.permission.READ_CALL_LOG
                ) == PackageManager.PERMISSION_GRANTED

                if (!hasCallLogPermission) {
                    Log.w(TAG, "READ_CALL_LOG permission not granted")
                    return@launch
                }

                val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
                    val calls = mutableListOf<Map<String, Any?>>()

                    while (it.moveToNext()) {
                        val callId = it.getLong(it.getColumnIndex(CallLog.Calls._ID))
                        val number = it.getString(it.getColumnIndex(CallLog.Calls.NUMBER)) ?: "Unknown"
                        val callType = it.getInt(it.getColumnIndex(CallLog.Calls.TYPE))
                        val callDate = it.getLong(it.getColumnIndex(CallLog.Calls.DATE))
                        val duration = it.getLong(it.getColumnIndex(CallLog.Calls.DURATION))
                        val cachedName = it.getString(it.getColumnIndex(CallLog.Calls.CACHED_NAME))

                        val contactName = cachedName ?: contactHelper.getContactName(number)

                        val typeString = when (callType) {
                            CallLog.Calls.INCOMING_TYPE -> "incoming"
                            CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                            CallLog.Calls.MISSED_TYPE -> "missed"
                            CallLog.Calls.REJECTED_TYPE -> "rejected"
                            else -> "unknown"
                        }

                        calls.add(mapOf(
                            "id" to callId.toString(),
                            "phoneNumber" to number,
                            "contactName" to (contactName ?: number),
                            "callType" to typeString,
                            "callDate" to callDate,
                            "duration" to duration
                        ))
                    }

                    if (calls.isNotEmpty()) {
                        vpsSyncService.syncCallHistory(calls)
                    }
                } ?: Log.w(TAG, "Call log cursor is null")

            } catch (e: Exception) {
                Log.e(TAG, "Error syncing call history", e)
            }
        }
    }

    private fun answerViaMediaKey() {
        try {
            val keyDown = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                putExtra(Intent.EXTRA_KEY_EVENT, android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_HEADSETHOOK))
            }
            val keyUp = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                putExtra(Intent.EXTRA_KEY_EVENT, android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_HEADSETHOOK))
            }
            sendOrderedBroadcast(keyDown, null)
            sendOrderedBroadcast(keyUp, null)
        } catch (e: Exception) {
            Log.e(TAG, "Media key answer failed", e)
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
