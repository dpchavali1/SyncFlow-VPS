/**
 * SyncFlowMessagingService.kt - FCM Push Notifications
 *
 * Handles Firebase Cloud Messaging for waking the device when the app is killed.
 * Real-time notifications are still primarily handled via WebSocket (VPSSyncService),
 * but FCM serves as a fallback for cross-user SyncFlow calls when the callee's
 * app is not running.
 *
 * Only minimal data (callId, callerName, callType) is sent via FCM.
 * Full call details are fetched from the authenticated GET /calls/syncflow/:id endpoint.
 */
package com.phoneintegration.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.phoneintegration.app.vps.VPSClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SyncFlowMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "SyncFlowMessaging"
        private const val CALL_CHANNEL_ID = "syncflow_calls"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onNewToken(token: String) {
        Log.d(TAG, "FCM token refreshed")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                VPSClient.getInstance(applicationContext)
                    .registerFcmToken(token)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register FCM token", e)
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val data = remoteMessage.data
        Log.d(TAG, "FCM message received: type=${data["type"]}")

        when (data["type"]) {
            "syncflow_call" -> handleIncomingCall(data)
            "outgoing_message" -> handleOutgoingMessage()
            "call_request" -> handleCallRequest()
        }
    }

    private fun handleOutgoingMessage() {
        Log.i(TAG, "Outgoing message FCM received — starting OutgoingMessageService")
        try {
            com.phoneintegration.app.desktop.OutgoingMessageService.start(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start OutgoingMessageService from FCM: ${e.message}", e)
        }
    }

    private fun handleCallRequest() {
        Log.i(TAG, "Call request FCM received — starting CallMonitorService")
        try {
            CallMonitorService.start(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start CallMonitorService from FCM: ${e.message}", e)
        }
    }

    private fun handleIncomingCall(data: Map<String, String>) {
        val callId = data["callId"] ?: return
        val callerName = data["callerName"] ?: "Unknown"
        val callType = data["callType"] ?: "audio"

        Log.i(TAG, "Incoming SyncFlow call via FCM: callId=$callId, caller=$callerName, type=$callType")

        try {
            val intent = Intent(this, SyncFlowCallService::class.java).apply {
                action = SyncFlowCallService.ACTION_INCOMING_USER_CALL
                putExtra(SyncFlowCallService.EXTRA_CALL_ID, callId)
                putExtra(SyncFlowCallService.EXTRA_CALLER_NAME, callerName)
                putExtra(SyncFlowCallService.EXTRA_CALLER_PHONE, "")
                putExtra(SyncFlowCallService.EXTRA_IS_VIDEO, callType == "video")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SyncFlowCallService from FCM: ${e.message}", e)
            // Fallback: show a basic notification with the call info
            showFallbackCallNotification(callId, callerName, callType)
        }
    }

    private fun showFallbackCallNotification(callId: String, callerName: String, callType: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("incoming_syncflow_call_id", callId)
            putExtra("incoming_syncflow_call_name", callerName)
            putExtra("incoming_syncflow_call_video", callType == "video")
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification = androidx.core.app.NotificationCompat.Builder(this, CALL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Incoming ${if (callType == "video") "Video" else "Audio"} Call")
            .setContentText("$callerName is calling...")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(true)
            .build()

        val notificationManager = getSystemService(android.app.NotificationManager::class.java)
        notificationManager.notify(3001, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ringtoneUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
            val audioAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val channel = NotificationChannel(
                CALL_CHANNEL_ID,
                "SyncFlow Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming SyncFlow video/audio calls"
                setShowBadge(true)
                setSound(ringtoneUri, audioAttributes)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1000, 500, 1000, 500, 1000)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
}
