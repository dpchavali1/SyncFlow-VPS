package com.phoneintegration.app

import android.content.Intent
import android.os.Build
import android.telecom.Call
import android.telecom.InCallService
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * InCallService that allows programmatic control of phone calls
 * This service is bound by the system when there's an active call
 */
@RequiresApi(Build.VERSION_CODES.M)
class DesktopInCallService : InCallService() {

    companion object {
        private const val TAG = "DesktopInCallService"
        private var instance: DesktopInCallService? = null
        private var activeCall: Call? = null

        // Broadcast action for incoming call with phone number
        const val ACTION_INCOMING_CALL = "com.phoneintegration.app.INCOMING_CALL_DETECTED"
        const val EXTRA_PHONE_NUMBER = "phone_number"
        const val EXTRA_CALL_STATE = "call_state"

        /**
         * Answer the currently ringing call
         */
        fun answerCall() {
            Log.d(TAG, "answerCall() requested")
            activeCall?.let { call ->
                if (call.state == Call.STATE_RINGING) {
                    Log.d(TAG, "Answering ringing call")
                    call.answer(0) // 0 = audio route default
                } else {
                    Log.w(TAG, "Call is not ringing, state: ${call.state}")
                }
            } ?: run {
                Log.w(TAG, "No active call to answer")
            }
        }

        /**
         * Reject/End the currently active call
         */
        fun endCall() {
            Log.d(TAG, "endCall() requested")
            activeCall?.let { call ->
                Log.d(TAG, "Ending/rejecting call, state: ${call.state}")
                when (call.state) {
                    Call.STATE_RINGING -> {
                        Log.d(TAG, "Rejecting ringing call")
                        call.reject(false, null)
                    }
                    Call.STATE_DIALING, Call.STATE_ACTIVE -> {
                        Log.d(TAG, "Disconnecting active call")
                        call.disconnect()
                    }
                    else -> {
                        Log.w(TAG, "Call in unexpected state: ${call.state}, attempting disconnect")
                        call.disconnect()
                    }
                }
            } ?: run {
                Log.w(TAG, "No active call to end")
            }
        }

        /**
         * Check if there's an active call
         */
        fun hasActiveCall(): Boolean {
            return activeCall != null
        }

        /**
         * Get the current call state
         */
        fun getCallState(): Int? {
            return activeCall?.state
        }
    }

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            Log.d(TAG, "Call state changed: ${stateToString(state)}")

            when (state) {
                Call.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Call disconnected, clearing active call reference")
                    if (activeCall == call) {
                        activeCall = null
                    }
                    call.unregisterCallback(this)
                }
            }
        }
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        Log.d(TAG, "onCallAdded - state: ${stateToString(call.state)}")

        activeCall = call
        call.registerCallback(callCallback)

        // Extract phone number from call details and broadcast it
        val phoneNumber = call.details?.handle?.schemeSpecificPart
        Log.d(TAG, "Call phone number: $phoneNumber")

        if (call.state == Call.STATE_RINGING && !phoneNumber.isNullOrEmpty()) {
            Log.d(TAG, "Broadcasting incoming call with phone number: $phoneNumber")
            val intent = Intent(ACTION_INCOMING_CALL).apply {
                putExtra(EXTRA_PHONE_NUMBER, phoneNumber)
                putExtra(EXTRA_CALL_STATE, "ringing")
                setPackage(packageName)
            }
            sendBroadcast(intent)
        }

        Log.d(TAG, "Active call registered, ready for programmatic control")
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        Log.d(TAG, "onCallRemoved")

        if (activeCall == call) {
            activeCall = null
        }
        call.unregisterCallback(callCallback)
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "DesktopInCallService created")
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "DesktopInCallService destroyed")
        instance = null
        activeCall = null
    }

    private fun stateToString(state: Int): String {
        return when (state) {
            Call.STATE_NEW -> "NEW"
            Call.STATE_RINGING -> "RINGING"
            Call.STATE_DIALING -> "DIALING"
            Call.STATE_ACTIVE -> "ACTIVE"
            Call.STATE_HOLDING -> "HOLDING"
            Call.STATE_DISCONNECTED -> "DISCONNECTED"
            Call.STATE_CONNECTING -> "CONNECTING"
            Call.STATE_DISCONNECTING -> "DISCONNECTING"
            Call.STATE_SELECT_PHONE_ACCOUNT -> "SELECT_PHONE_ACCOUNT"
            else -> "UNKNOWN($state)"
        }
    }
}
