package com.phoneintegration.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.phoneintegration.app.data.PreferencesManager
import com.phoneintegration.app.desktop.OutgoingMessageService

/**
 * BootReceiver - handles boot completed event
 *
 * Note: We no longer start CallMonitorService on boot.
 * Like WhatsApp, we use FCM push notifications for incoming calls
 * which doesn't require a persistent foreground service.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Boot completed - FCM will handle incoming calls")

            // No persistent services needed on boot
            // FCM (Firebase Cloud Messaging) will wake the app when needed:
            // 1. Incoming SyncFlow calls trigger FCM → SyncFlowMessagingService
            // 2. SMS sync happens when user opens the app
            // 3. Desktop commands can be sent via FCM high-priority messages
            val preferencesManager = PreferencesManager(context)
            if (preferencesManager.backgroundSyncEnabled.value) {
                OutgoingMessageService.start(context)
            }
        }
    }
}
