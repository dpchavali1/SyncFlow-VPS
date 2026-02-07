package com.phoneintegration.app.desktop

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.phoneintegration.app.vps.VPSClient
import kotlinx.coroutines.*

/**
 * Service to sync Do Not Disturb status between Android and macOS.
 * Requires Notification Policy Access permission.
 *
 * VPS Backend Only - Uses VPS API instead of Firebase.
 */
class DNDSyncService(context: Context) {
    private val context: Context = context.applicationContext
    private val vpsClient = VPSClient.getInstance(context)
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private var dndReceiver: BroadcastReceiver? = null
    private var commandPollingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var lastSyncedDndState: Boolean? = null

    companion object {
        private const val TAG = "DNDSyncService"
        private const val COMMAND_POLL_INTERVAL_MS = 2000L

        /**
         * Check if notification policy access is granted
         */
        fun hasNotificationPolicyAccess(context: Context): Boolean {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            return nm.isNotificationPolicyAccessGranted
        }

        /**
         * Open notification policy access settings
         */
        fun openNotificationPolicySettings(context: Context) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    /**
     * Start syncing DND status
     */
    fun startSync() {
        Log.d(TAG, "Starting DND sync")
        registerDndReceiver()
        startListeningForCommands()
        syncDndStatus()
    }

    /**
     * Stop syncing
     */
    fun stopSync() {
        Log.d(TAG, "Stopping DND sync")
        unregisterDndReceiver()
        stopListeningForCommands()
        scope.cancel()
    }

    /**
     * Get current DND mode
     */
    fun isDndEnabled(): Boolean {
        return try {
            val filter = notificationManager.currentInterruptionFilter
            filter != NotificationManager.INTERRUPTION_FILTER_ALL
        } catch (e: Exception) {
            Log.e(TAG, "Error checking DND status", e)
            false
        }
    }

    /**
     * Get detailed DND filter mode
     */
    fun getDndFilterMode(): String {
        return try {
            when (notificationManager.currentInterruptionFilter) {
                NotificationManager.INTERRUPTION_FILTER_ALL -> "off"
                NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
                NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms_only"
                NotificationManager.INTERRUPTION_FILTER_NONE -> "total_silence"
                else -> "unknown"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting DND filter mode", e)
            "unknown"
        }
    }

    /**
     * Register broadcast receiver for DND changes
     */
    private fun registerDndReceiver() {
        dndReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED) {
                    Log.d(TAG, "DND state changed")
                    syncDndStatus()
                }
            }
        }

        val filter = IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        context.registerReceiver(dndReceiver, filter)
        Log.d(TAG, "DND receiver registered")
    }

    private fun unregisterDndReceiver() {
        dndReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering DND receiver", e)
            }
        }
        dndReceiver = null
    }

    /**
     * Listen for DND commands from macOS via polling
     */
    private fun startListeningForCommands() {
        commandPollingJob?.cancel()
        commandPollingJob = scope.launch {
            while (isActive) {
                try {
                    if (!vpsClient.isAuthenticated) {
                        delay(COMMAND_POLL_INTERVAL_MS)
                        continue
                    }

                    val commands = vpsClient.getDndCommands()
                    for (command in commands) {
                        // Only process recent commands
                        if (System.currentTimeMillis() - command.timestamp > 10000) continue

                        Log.d(TAG, "Received DND command: ${command.action}")

                        when (command.action) {
                            "enable" -> enableDnd()
                            "disable" -> disableDnd()
                            "toggle" -> toggleDnd()
                            "priority" -> setDndMode(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                            "alarms" -> setDndMode(NotificationManager.INTERRUPTION_FILTER_ALARMS)
                            "silence" -> setDndMode(NotificationManager.INTERRUPTION_FILTER_NONE)
                        }

                        // Mark command as processed
                        vpsClient.markDndCommandProcessed(command.id)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error polling DND commands", e)
                }
                delay(COMMAND_POLL_INTERVAL_MS)
            }
        }
        Log.d(TAG, "DND command polling started")
    }

    private fun stopListeningForCommands() {
        commandPollingJob?.cancel()
        commandPollingJob = null
    }

    /**
     * Enable DND
     */
    private fun enableDnd() {
        if (!hasNotificationPolicyAccess(context)) {
            Log.w(TAG, "No notification policy access")
            sendStatusUpdate(isDndEnabled(), "Permission required")
            return
        }

        try {
            notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            Log.d(TAG, "DND enabled")
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling DND", e)
        }
    }

    /**
     * Disable DND
     */
    private fun disableDnd() {
        if (!hasNotificationPolicyAccess(context)) {
            Log.w(TAG, "No notification policy access")
            sendStatusUpdate(isDndEnabled(), "Permission required")
            return
        }

        try {
            notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            Log.d(TAG, "DND disabled")
        } catch (e: Exception) {
            Log.e(TAG, "Error disabling DND", e)
        }
    }

    /**
     * Toggle DND
     */
    private fun toggleDnd() {
        if (isDndEnabled()) {
            disableDnd()
        } else {
            enableDnd()
        }
    }

    /**
     * Set specific DND mode
     */
    private fun setDndMode(filter: Int) {
        if (!hasNotificationPolicyAccess(context)) {
            Log.w(TAG, "No notification policy access")
            sendStatusUpdate(isDndEnabled(), "Permission required")
            return
        }

        try {
            notificationManager.setInterruptionFilter(filter)
            Log.d(TAG, "DND mode set to $filter")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting DND mode", e)
        }
    }

    /**
     * Sync current DND status to VPS
     */
    private fun syncDndStatus() {
        val isEnabled = isDndEnabled()

        // Skip if no change
        if (lastSyncedDndState == isEnabled) return
        lastSyncedDndState = isEnabled

        scope.launch {
            try {
                if (!vpsClient.isAuthenticated) return@launch

                val statusData = mapOf(
                    "enabled" to isEnabled,
                    "mode" to getDndFilterMode(),
                    "hasPermission" to hasNotificationPolicyAccess(context),
                    "timestamp" to System.currentTimeMillis(),
                    "source" to "android"
                )

                vpsClient.syncDndStatus(statusData)
                Log.d(TAG, "DND status synced: enabled=$isEnabled")
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing DND status", e)
            }
        }
    }

    /**
     * Send status update with message
     */
    private fun sendStatusUpdate(enabled: Boolean, message: String) {
        scope.launch {
            try {
                if (!vpsClient.isAuthenticated) return@launch

                val statusData = mapOf(
                    "enabled" to enabled,
                    "mode" to getDndFilterMode(),
                    "hasPermission" to hasNotificationPolicyAccess(context),
                    "message" to message,
                    "timestamp" to System.currentTimeMillis(),
                    "source" to "android"
                )

                vpsClient.syncDndStatus(statusData)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending status update", e)
            }
        }
    }
}
