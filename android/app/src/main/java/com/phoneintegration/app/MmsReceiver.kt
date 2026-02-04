package com.phoneintegration.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MmsReceiver : BroadcastReceiver() {

    companion object {
        // Track recently synced MMS IDs to prevent duplicates
        private val recentlySyncedMmsIds = mutableSetOf<Long>()
        private var lastCleanupTime = 0L
        private const val CLEANUP_INTERVAL_MS = 60_000L // Clean up every minute
        private const val MAX_TRACKED_IDS = 100
        private var lastNotifiedMmsId: Long? = null
        private var lastNotificationTime: Long = 0L
        private const val NOTIFICATION_DEBOUNCE_MS = 8000L

        @Synchronized
        fun markMmsSynced(mmsId: Long) {
            cleanupOldEntries()
            recentlySyncedMmsIds.add(mmsId)
        }

        @Synchronized
        fun isMmsAlreadySynced(mmsId: Long): Boolean {
            return recentlySyncedMmsIds.contains(mmsId)
        }

        @Synchronized
        private fun cleanupOldEntries() {
            val now = System.currentTimeMillis()
            if (now - lastCleanupTime > CLEANUP_INTERVAL_MS) {
                // Keep only the most recent IDs to prevent memory bloat
                if (recentlySyncedMmsIds.size > MAX_TRACKED_IDS) {
                    val toRemove = recentlySyncedMmsIds.size - MAX_TRACKED_IDS
                    repeat(toRemove) {
                        recentlySyncedMmsIds.remove(recentlySyncedMmsIds.firstOrNull())
                    }
                }
                lastCleanupTime = now
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d("MmsReceiver", "Received action: $action")

        when (action) {
            "android.provider.Telephony.WAP_PUSH_DELIVER",
            "android.provider.Telephony.WAP_PUSH_RECEIVED" -> {
                handleMmsReceived(context, intent)
            }
        }
    }

    private var lastMmsTimestamp: Long = 0

    private fun handleMmsReceived(context: Context, intent: Intent) {
        try {
            val now = System.currentTimeMillis()

            // Debounce duplicate MMS broadcasts occurring within 5 seconds
            // (increased from 2s to handle multi-part MMS)
            if (now - lastMmsTimestamp < 5000) {
                Log.d("MmsReceiver", "Duplicate MMS intent ignored (within 5s window)")
                return
            }
            lastMmsTimestamp = now

            Log.d("MmsReceiver", "MMS message received")

            // Wait briefly for provider to persist the MMS, then process
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    kotlinx.coroutines.delay(2000)
                    processRecentMms(context)
                } catch (e: Exception) {
                    Log.e("MmsReceiver", "Error processing MMS", e)
                }
            }

        } catch (e: Exception) {
            Log.e("MmsReceiver", "Error handling MMS", e)
        }
    }

    private suspend fun processRecentMms(context: Context) {
        val smsRepository = SmsRepository(context)
        val syncService = com.phoneintegration.app.desktop.DesktopSyncService(context)
        val notificationHelper = NotificationHelper(context)

        // Get the most recent MMS messages (dedicated MMS query)
        val recentMms = smsRepository.getRecentInboxMmsMessages(10)
        if (recentMms.isEmpty()) {
            Log.d("MmsReceiver", "No recent MMS found after delay")
            return
        }

        var syncedCount = 0
        var skippedCount = 0
        var notified = 0
        var notifiedOnce = false

        for (message in recentMms) {
            if (!message.isMms) continue
            val mmsId = message.id

            Log.d("MmsReceiver", "Processing MMS: id=$mmsId, address=${message.address}, body length=${message.body?.length ?: 0}, attachments=${message.mmsAttachments.size}")

            if (isMmsAlreadySynced(mmsId)) {
                Log.d("MmsReceiver", "MMS $mmsId already synced, skipping")
                skippedCount++
                continue
            }

            // Notify once for the newest unsynced MMS
            if (!notifiedOnce && shouldNotifyForMms(mmsId)) {
                val contactName = smsRepository.resolveContactName(message.address)
                Log.d("MmsReceiver", "Showing MMS notification - address: '${message.address}', contactName: '$contactName', mmsId: $mmsId")

                notificationHelper.showMmsNotification(
                    sender = message.address,
                    message = "You received a multimedia message",
                    contactName = contactName
                )
                notified++
                notifiedOnce = true
                Log.d("MmsReceiver", "MMS notification shown for $mmsId")
            }

            // Broadcast to refresh UI after we have a real MMS record
            val local = Intent("com.phoneintegration.app.MMS_RECEIVED")
            androidx.localbroadcastmanager.content.LocalBroadcastManager
                .getInstance(context)
                .sendBroadcast(local)

            // Sync to Firebase only if devices are paired (saves battery for Android-only users)
            if (com.phoneintegration.app.desktop.DesktopSyncService.hasPairedDevices(context)) {
                try {
                    Log.d("MmsReceiver", "Starting Firebase sync for MMS $mmsId")
                    syncService.syncMessage(message)
                    markMmsSynced(mmsId)
                    syncedCount++
                    Log.d("MmsReceiver", "MMS message synced to Firebase successfully: $mmsId")
                } catch (e: Exception) {
                    Log.e("MmsReceiver", "Failed to sync MMS $mmsId to Firebase", e)
                }
            } else {
                Log.d("MmsReceiver", "Skipping Firebase sync for MMS $mmsId - no paired devices")
            }
        }

        Log.d("MmsReceiver", "MMS processing complete: $syncedCount synced, $skippedCount skipped, $notified notified")
    }

    private fun shouldNotifyForMms(mmsId: Long): Boolean {
        val now = System.currentTimeMillis()
        if (lastNotifiedMmsId == mmsId && now - lastNotificationTime < NOTIFICATION_DEBOUNCE_MS) {
            return false
        }
        if (now - lastNotificationTime < NOTIFICATION_DEBOUNCE_MS) {
            return false
        }
        lastNotifiedMmsId = mmsId
        lastNotificationTime = now
        return true
    }
}
