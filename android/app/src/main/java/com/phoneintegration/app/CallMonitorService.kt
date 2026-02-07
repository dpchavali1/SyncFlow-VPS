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
 * CallMonitorService - VPS Backend Only
 *
 * Monitors phone calls and syncs call state to VPS server.
 * Handles incoming call notifications and call commands from desktop/web.
 */
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
            Log.d(TAG, "📞📞📞 BROADCAST RECEIVED! action=${intent?.action}")
            if (intent?.action == DesktopInCallService.ACTION_INCOMING_CALL) {
                val phoneNumber = intent.getStringExtra(DesktopInCallService.EXTRA_PHONE_NUMBER)
                val callIdFromBroadcast = intent.getStringExtra("call_id")
                val callState = intent.getStringExtra(DesktopInCallService.EXTRA_CALL_STATE)
                Log.d(TAG, "📞📞📞 INCOMING_CALL broadcast - phoneNumber: $phoneNumber, callId: $callIdFromBroadcast, state: $callState")

                if (!phoneNumber.isNullOrEmpty()) {
                    pendingPhoneNumber = phoneNumber

                    if (!callIdFromBroadcast.isNullOrEmpty()) {
                        Log.d(TAG, "📞 Setting currentCallId from broadcast: $callIdFromBroadcast")
                        currentCallId = callIdFromBroadcast

                        // OPTIMIZATION: Sync to VPS IMMEDIATELY when we receive the broadcast
                        if (callState == "ringing") {
                            Log.d(TAG, "📞 FAST PATH: Syncing ringing call immediately from broadcast!")
                            serviceScope.launch(NonCancellable) {
                                try {
                                    val contactHelper = ContactHelper(this@CallMonitorService)
                                    val contactName = contactHelper.getContactName(phoneNumber)
                                    Log.d(TAG, "📞 Contact lookup: $phoneNumber -> ${contactName ?: "null"}")

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

                                    Log.d(TAG, "📞 FAST PATH: Call synced to VPS!")
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
        Log.d(TAG, "CallMonitorService created (VPS backend)")

        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        vpsSyncService = VPSSyncService.getInstance(this)
        simManager = SimManager(this)

        // Restore currentCallId from SharedPreferences in case service was recreated
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

        // Register ContentObserver to watch call log changes
        registerCallLogObserver()

        registerCallStateListener()
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
                serviceScope.launch {
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
                            val storedNumber = getStoredPhoneNumber()
                            if (storedNumber != null) {
                                Log.d(TAG, "Using phone number from SharedPreferences: $storedNumber")
                                storedNumber
                            } else {
                                val callLogNumber = getLastIncomingCallNumber()
                                Log.d(TAG, "Attempted call log query, result: $callLogNumber")
                                callLogNumber ?: "Unknown"
                            }
                        }
                    }
                    Log.d(TAG, "Incoming call from: $number")
                    onIncomingCall(number)
                }
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                Log.d(TAG, "Call active")
                onCallActive()
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                Log.d(TAG, "Call ended")
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
                Log.d(TAG, "Found stored phone number: $storedNumber (age: ${age}ms)")
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
                Log.d(TAG, "Found stored callId: $storedCallId (age: ${age}ms)")
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
            Log.d(TAG, "Cleared stored phone number")
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
                    Log.d(TAG, "Found incoming call number from call log: $number")
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

        serviceScope.launch(NonCancellable) {
            try {
                Log.d(TAG, "📞 Processing incoming call")

                val storedCallId = getStoredCallId()
                val callId = storedCallId ?: System.currentTimeMillis().toString()
                currentCallId = callId
                Log.d(TAG, "📞 Using callId: $callId")

                val contactHelper = ContactHelper(this@CallMonitorService)
                val contactName = contactHelper.getContactName(phoneNumber)
                Log.d(TAG, "📞 Contact lookup done: phoneNumber=$phoneNumber, contactName=$contactName")

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

                Log.d(TAG, "📞 Incoming call synced to VPS")
            } catch (e: Exception) {
                Log.e(TAG, "📞 ERROR syncing incoming call: ${e.message}", e)
            }
        }
    }

    private fun onCallActive() {
        currentCallId?.let { callId ->
            serviceScope.launch {
                try {
                    vpsSyncService.updateActiveCallState(callId, "active")
                    vpsSyncService.clearActiveCall(callId) // Clear from active_calls to dismiss notification
                    Log.d(TAG, "Call active state synced")
                } catch (e: Exception) {
                    Log.e(TAG, "Error syncing call active state", e)
                }
            }
        }
    }

    private fun onCallEnded() {
        Log.d(TAG, "📞 onCallEnded called, currentCallId=$currentCallId")

        currentCallId?.let { callId ->
            Log.d(TAG, "📞 Syncing ended state for call: $callId")
            serviceScope.launch {
                try {
                    vpsSyncService.updateActiveCallState(callId, "ended")
                    vpsSyncService.clearActiveCall(callId)
                    Log.d(TAG, "📞 Call ended state synced successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "📞 Error syncing call ended state", e)
                }
            }
        }

        currentCallId = null
        pendingPhoneNumber = null
        clearStoredPhoneNumber()

        // Auto-stop service after call ends
        serviceScope.launch {
            delay(5000)
            Log.d(TAG, "📞 Auto-stopping CallMonitorService after call ended")
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
            Log.d(TAG, "📞 syncActiveCall - callId: $callId, state: $callState")

            vpsSyncService.syncActiveCall(
                callId = callId,
                phoneNumber = phoneNumber,
                contactName = contactName,
                state = callState,
                callType = "incoming"
            )

            Log.d(TAG, "📞 Active call synced to VPS: $callId - $callState")
        } catch (e: Exception) {
            Log.e(TAG, "📞 ERROR syncing active call: ${e.message}", e)
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
            Log.d(TAG, "Call event synced: $callId - $callType - $callState")
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing call event", e)
        }
    }

    private fun listenForCallCommands() {
        serviceScope.launch {
            try {
                Log.d(TAG, "Setting up call commands listener via WebSocket")

                vpsSyncService.callCommands.collect { command ->
                    Log.d(TAG, "Received call command: ${command.command}")

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
                Log.d(TAG, "Setting up call requests listener via WebSocket")

                vpsSyncService.callRequests.collect { request ->
                    if (request.status == "pending") {
                        Log.d(TAG, "Received call request for: ${request.phoneNumber}")
                        processCallRequest(request)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in call requests listener", e)
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

                Log.d(TAG, "Initiated call from desktop request to ${request.phoneNumber}")
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
                    Log.d(TAG, "Call placed with SIM $subscriptionId")
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
                Log.d(TAG, "Call placed via TelecomManager")
            } else {
                val callIntent = Intent(Intent.ACTION_CALL).apply {
                    data = Uri.parse("tel:$phoneNumber")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(callIntent)
                Log.d(TAG, "Call started via Intent")
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
                // TODO: Sync SIM info to VPS when endpoint is available
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing SIM information", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleCallCommand(command: String, callId: String?, phoneNumber: String?) {
        Log.d(TAG, "Handling call command: $command (callId: $callId)")

        if (!checkCallPermissions()) {
            Log.e(TAG, "Missing permissions to handle call command")
            return
        }

        try {
            when (command) {
                "answer" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        Log.d(TAG, "Attempting to answer call via TelecomManager")
                        telecomManager.acceptRingingCall()
                        Log.d(TAG, "Accept ringing call executed")

                        callId?.let { id ->
                            serviceScope.launch {
                                delay(500)
                                vpsSyncService.updateActiveCallState(id, "active")
                            }
                        }
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        DesktopInCallService.answerCall()
                    }
                }
                "reject", "end" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        Log.d(TAG, "Attempting to end call via TelecomManager")
                        val result = telecomManager.endCall()
                        Log.d(TAG, "End call result: $result")

                        callId?.let { id ->
                            serviceScope.launch {
                                delay(300)
                                vpsSyncService.clearActiveCall(id)
                            }
                        }
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        DesktopInCallService.endCall()
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
                Log.d(TAG, "Initiated outgoing call to $phoneNumber")
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
            Log.e(TAG, "Error making outgoing call to $phoneNumber", e)
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
                        Log.d(TAG, "Call history synced: ${calls.size} calls")
                    }
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
