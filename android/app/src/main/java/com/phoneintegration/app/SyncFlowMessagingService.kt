/**
 * SyncFlowMessagingService.kt - VPS Backend Only (Stub)
 *
 * In VPS mode, real-time notifications are handled via WebSocket connection
 * managed by VPSSyncService, not Firebase Cloud Messaging.
 *
 * This file is kept as a stub for compilation compatibility.
 * The actual real-time functionality is in VPSSyncService which uses WebSocket.
 *
 * If FCM is still desired for waking up the device when the app is killed,
 * a separate push notification service would need to be implemented on VPS.
 */
package com.phoneintegration.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Stub FCM Service - VPS backend only
 *
 * This service is not used in VPS mode. Real-time notifications
 * are delivered via WebSocket connection to the VPS server.
 *
 * To enable push notifications when the app is killed, implement
 * a push notification service on the VPS server that uses a provider
 * like Firebase Admin SDK, OneSignal, or direct APNs/FCM integration.
 */
class SyncFlowMessagingService : Service() {

    companion object {
        private const val TAG = "SyncFlowMessaging"
        private const val CALL_CHANNEL_ID = "syncflow_calls"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "SyncFlowMessagingService initialized (VPS mode - FCM not used)")
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "SyncFlowMessagingService started (stub - no FCM in VPS mode)")
        // Stop immediately as this service is not needed
        stopSelf()
        return START_NOT_STICKY
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
