package com.phoneintegration.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * BroadcastReceiver to handle call actions (answer/decline) from notifications.
 * This allows answering calls from a locked screen without requiring unlock on Samsung devices.
 * The receiver handles the action and then launches the activity if needed.
 */
class CallActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CallActionReceiver"

        const val ACTION_ANSWER_CALL = "com.phoneintegration.app.ACTION_ANSWER_CALL"
        const val ACTION_DECLINE_CALL = "com.phoneintegration.app.ACTION_DECLINE_CALL"

        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_CALLER_NAME = "caller_name"
        const val EXTRA_WITH_VIDEO = "with_video"
        const val EXTRA_IS_VIDEO_CALL = "is_video_call"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val callId = intent.getStringExtra(EXTRA_CALL_ID)
        val callerName = intent.getStringExtra(EXTRA_CALLER_NAME) ?: "Unknown"
        val withVideo = intent.getBooleanExtra(EXTRA_WITH_VIDEO, true)
        val isVideoCall = intent.getBooleanExtra(EXTRA_IS_VIDEO_CALL, false)

        Log.d(TAG, "Received action: ${intent.action}, callId: $callId, withVideo: $withVideo")

        when (intent.action) {
            ACTION_ANSWER_CALL -> {
                if (callId != null) {
                    // First, tell the service to answer the call
                    val serviceIntent = Intent(context, SyncFlowCallService::class.java).apply {
                        action = SyncFlowCallService.ACTION_ANSWER_CALL
                        putExtra(SyncFlowCallService.EXTRA_CALL_ID, callId)
                        putExtra(SyncFlowCallService.EXTRA_WITH_VIDEO, withVideo)
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }

                    Log.d(TAG, "Sent answer command to service")

                    // Then launch the activity to show the call screen
                    // Small delay to let the service process the answer first
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        val activityIntent = Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                            putExtra("active_syncflow_call", true)
                            putExtra("active_syncflow_call_id", callId)
                            putExtra("active_syncflow_call_name", callerName)
                            putExtra("active_syncflow_call_video", withVideo)
                            putExtra("dismiss_keyguard", true)
                            putExtra("from_locked_screen", true)
                        }
                        context.startActivity(activityIntent)
                        Log.d(TAG, "Launched MainActivity for call screen")
                    }, 300)
                }
            }

            ACTION_DECLINE_CALL -> {
                if (callId != null) {
                    val serviceIntent = Intent(context, SyncFlowCallService::class.java).apply {
                        action = SyncFlowCallService.ACTION_REJECT_CALL
                        putExtra(SyncFlowCallService.EXTRA_CALL_ID, callId)
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }

                    Log.d(TAG, "Sent decline command to service")
                }
            }
        }
    }
}
