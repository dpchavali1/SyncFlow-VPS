package com.phoneintegration.app

import android.content.Intent
import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * CallScreeningService that intercepts incoming calls to get the phone number.
 * This works without being the default dialer app.
 * Requires ROLE_CALL_SCREENING permission.
 *
 * CRITICAL: This service is triggered by the system even when the app is closed.
 * It's responsible for:
 * 1. Storing the phone number and callId in SharedPreferences
 * 2. Starting CallMonitorService to sync to Firebase and handle commands
 * 3. Broadcasting the call info to CallMonitorService
 *
 * NOTE: Firebase sync is done by CallMonitorService, not here, because
 * this service runs in a limited context without access to AuthManager.
 */
@RequiresApi(Build.VERSION_CODES.N)
class SyncFlowCallScreeningService : CallScreeningService() {

    companion object {
        private const val TAG = "SyncFlowCallScreening"
        const val ACTION_CALL_SCREENING = "com.phoneintegration.app.CALL_SCREENING_DETECTED"
        const val EXTRA_PHONE_NUMBER = "phone_number"
    }

    override fun onScreenCall(callDetails: Call.Details) {
        val phoneNumber = callDetails.handle?.schemeSpecificPart
        val isIncoming = callDetails.callDirection == Call.Details.DIRECTION_INCOMING

        Log.d(TAG, "📞 onScreenCall - phoneNumber: $phoneNumber, incoming: $isIncoming, direction: ${callDetails.callDirection}")

        if (isIncoming && !phoneNumber.isNullOrEmpty()) {
            Log.d(TAG, "📞 Incoming call detected from: $phoneNumber")

            // Generate a call ID that will be shared between this service and CallMonitorService
            val callId = System.currentTimeMillis().toString()

            // Store phone number AND call ID in SharedPreferences so they survive service restarts
            val prefs = getSharedPreferences("call_screening_prefs", MODE_PRIVATE)
            prefs.edit()
                .putString("last_incoming_number", phoneNumber)
                .putString("last_incoming_call_id", callId)
                .putLong("last_incoming_timestamp", System.currentTimeMillis())
                .apply()
            Log.d(TAG, "📞 Stored phone number and callId in SharedPreferences: $phoneNumber, $callId")

            // Start CallMonitorService IMMEDIATELY so it can:
            // 1. Sync the call to Firebase (so Mac gets notification)
            // 2. Listen for and process commands (answer/reject)
            // This is critical when the app is closed
            try {
                Log.d(TAG, "📞 Starting CallMonitorService...")
                CallMonitorService.start(this)
                Log.d(TAG, "📞 CallMonitorService start requested")
            } catch (e: Exception) {
                Log.e(TAG, "📞 Error starting CallMonitorService", e)
            }

            // Broadcast the phone number and callId to CallMonitorService
            val intent = Intent(DesktopInCallService.ACTION_INCOMING_CALL).apply {
                putExtra(DesktopInCallService.EXTRA_PHONE_NUMBER, phoneNumber)
                putExtra(DesktopInCallService.EXTRA_CALL_STATE, "ringing")
                putExtra("call_id", callId)
                setPackage(packageName)
            }
            sendBroadcast(intent)
            Log.d(TAG, "📞 Broadcast sent to CallMonitorService")
        }

        // Allow the call to proceed normally (don't block or silence)
        val response = CallResponse.Builder()
            .setDisallowCall(false)
            .setRejectCall(false)
            .setSilenceCall(false)
            .setSkipCallLog(false)
            .setSkipNotification(false)
            .build()

        respondToCall(callDetails, response)
    }
}
