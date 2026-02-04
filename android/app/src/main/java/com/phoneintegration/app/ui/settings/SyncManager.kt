package com.phoneintegration.app.ui.settings

import android.content.Context
import android.util.Log
import com.phoneintegration.app.SmsRepository
import com.phoneintegration.app.desktop.DesktopSyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Singleton manager for message history sync.
 * Persists sync state across screen navigations.
 */
object SyncManager {
    private const val TAG = "SyncManager"
    private const val ESTIMATE_OVERHEAD_BYTES = 220L
    private const val ESTIMATE_MIN_MSGS_PER_MIN = 250.0
    private const val ESTIMATE_MAX_MSGS_PER_MIN = 500.0

    data class SyncState(
        val isSyncing: Boolean = false,
        val progress: Float = 0f,
        val status: String = "",
        val syncedCount: Int = 0,
        val totalCount: Int = 0,
        val isComplete: Boolean = false,
        val error: String? = null,
        // Last successful sync info
        val lastSyncCompleted: Long? = null,
        val lastSyncDays: Int? = null,
        val lastSyncMessageCount: Int? = null
    )

    data class SyncEstimate(
        val days: Int,
        val totalMessages: Int,
        val estimatedBytes: Long,
        val minMinutes: Int,
        val maxMinutes: Int
    )

    private val _syncState = MutableStateFlow(SyncState())
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    // Use SupervisorJob so sync continues even if one part fails
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var smsRepository: SmsRepository? = null
    private var desktopSyncService: DesktopSyncService? = null

    private fun ensureInitialized(context: Context) {
        if (smsRepository == null) {
            smsRepository = SmsRepository(context.applicationContext)
        }
        if (desktopSyncService == null) {
            desktopSyncService = DesktopSyncService(context.applicationContext)
        }
        // Load last sync info from preferences
        loadLastSyncInfo(context)
    }

    private fun loadLastSyncInfo(context: Context) {
        val prefs = context.getSharedPreferences("sync_manager", Context.MODE_PRIVATE)
        val lastCompleted = prefs.getLong("last_sync_completed", 0L).takeIf { it > 0 }
        val lastDays = prefs.getInt("last_sync_days", 0).takeIf { it > 0 }
        val lastCount = prefs.getInt("last_sync_message_count", 0).takeIf { it > 0 }

        if (lastCompleted != null) {
            _syncState.value = _syncState.value.copy(
                lastSyncCompleted = lastCompleted,
                lastSyncDays = lastDays,
                lastSyncMessageCount = lastCount
            )
        }
    }

    private fun saveLastSyncInfo(context: Context, days: Int, messageCount: Int) {
        val prefs = context.getSharedPreferences("sync_manager", Context.MODE_PRIVATE)
        prefs.edit()
            .putLong("last_sync_completed", System.currentTimeMillis())
            .putInt("last_sync_days", days)
            .putInt("last_sync_message_count", messageCount)
            .apply()
    }

    /**
     * Start syncing messages for the specified number of days.
     * Sync continues even if user navigates away from the screen.
     */
    fun startSync(context: Context, days: Int) {
        if (_syncState.value.isSyncing) {
            Log.d(TAG, "Sync already in progress, ignoring request")
            return
        }

        ensureInitialized(context)

        scope.launch {
            try {
                _syncState.value = SyncState(
                    isSyncing = true,
                    status = "Loading messages..."
                )

                // Load messages on IO dispatcher
                val daysToLoad = if (days <= 0) 3650 else days
                val messages = withContext(Dispatchers.IO) {
                    smsRepository!!.getMessagesFromLastDays(daysToLoad)
                }

                val totalCount = messages.size
                Log.d(TAG, "Loaded $totalCount messages to sync")

                if (totalCount == 0) {
                    _syncState.value = SyncState(
                        isSyncing = false,
                        status = "No messages to sync",
                        isComplete = true
                    )
                    return@launch
                }

                _syncState.value = _syncState.value.copy(
                    status = "Syncing $totalCount messages...",
                    totalCount = totalCount
                )

                Log.d(TAG, "Starting sync: $totalCount total messages (including MMS attachments)")

                var syncedCount = 0

                desktopSyncService!!.syncMessages(
                    messages = messages,
                    skipAttachments = false
                ) { synced, total ->
                    syncedCount = synced
                    val progress = if (total > 0) synced.toFloat() / total.toFloat() else 0f
                    _syncState.value = _syncState.value.copy(
                        syncedCount = synced,
                        totalCount = total,
                        progress = progress,
                        status = "Syncing... $synced / $total"
                    )
                }

                Log.i(TAG, "Sync completed: $syncedCount messages synced")
                val statusMsg = "Completed! Synced $syncedCount messages"

                // Save last sync info
                val currentTime = System.currentTimeMillis()
                saveLastSyncInfo(context, days, syncedCount)

                _syncState.value = _syncState.value.copy(
                    isSyncing = false,
                    syncedCount = syncedCount,
                    progress = 1f,
                    status = statusMsg,
                    isComplete = true,
                    lastSyncCompleted = currentTime,
                    lastSyncDays = days,
                    lastSyncMessageCount = syncedCount
                )

            } catch (e: Exception) {
                Log.e(TAG, "Sync failed: ${e.message}", e)
                _syncState.value = _syncState.value.copy(
                    isSyncing = false,
                    status = "Failed: ${e.message}",
                    error = e.message
                )
            }
        }
    }

    suspend fun estimateSync(context: Context, days: Int): SyncEstimate = withContext(Dispatchers.IO) {
        ensureInitialized(context)
        val daysToLoad = if (days <= 0) 3650 else days
        val messages = smsRepository!!.getMessagesFromLastDays(daysToLoad)
        val totalMessages = messages.size

        var bodyBytes = 0L
        for (message in messages) {
            bodyBytes += message.body.toByteArray(Charsets.UTF_8).size.toLong()
        }

        val estimatedBytes = bodyBytes + (totalMessages * ESTIMATE_OVERHEAD_BYTES)

        val minMinutes = if (totalMessages == 0) 0 else kotlin.math.ceil(totalMessages / ESTIMATE_MAX_MSGS_PER_MIN).toInt()
        val maxMinutes = if (totalMessages == 0) 0 else kotlin.math.ceil(totalMessages / ESTIMATE_MIN_MSGS_PER_MIN).toInt()

        SyncEstimate(
            days = days,
            totalMessages = totalMessages,
            estimatedBytes = estimatedBytes,
            minMinutes = minMinutes,
            maxMinutes = maxMinutes
        )
    }

    /**
     * Reset the sync state to allow another sync
     */
    fun resetState() {
        if (!_syncState.value.isSyncing) {
            _syncState.value = SyncState()
        }
    }
}
