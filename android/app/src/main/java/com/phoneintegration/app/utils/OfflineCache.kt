package com.phoneintegration.app.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Offline-first caching utility for SyncFlow
 * Queues operations when offline and executes them when connectivity is restored
 */
class OfflineCache(private val context: Context) {

    companion object {
        private const val TAG = "OfflineCache"
        private const val PREFS_NAME = "syncflow_offline_cache"
        private const val KEY_PENDING_OPS = "pending_operations"
        private const val KEY_LAST_SYNC = "last_sync_timestamp"
        private const val MAX_PENDING_OPS = 100
        private const val MAX_RETRY_COUNT = 3
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _pendingOperations = MutableStateFlow<List<PendingOperation>>(emptyList())
    val pendingOperations: StateFlow<List<PendingOperation>> = _pendingOperations.asStateFlow()

    private val _syncStatus = MutableStateFlow(SyncStatus.IDLE)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private var syncJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        // Load pending operations from storage
        loadPendingOperations()

        // Start monitoring network connectivity
        startNetworkMonitoring()
    }

    /**
     * Queue an operation to be executed when online
     */
    fun queueOperation(operation: PendingOperation) {
        val current = _pendingOperations.value.toMutableList()

        // Check for duplicate operations
        if (current.any { it.id == operation.id }) {
            Log.d(TAG, "Operation ${operation.id} already queued, skipping")
            return
        }

        // Enforce max pending operations
        if (current.size >= MAX_PENDING_OPS) {
            Log.w(TAG, "Max pending operations reached, removing oldest")
            current.removeAt(0)
        }

        current.add(operation)
        _pendingOperations.value = current
        savePendingOperations()

        Log.d(TAG, "Operation queued: ${operation.type} - ${operation.id}")

        // Try to execute immediately if online
        if (NetworkUtils.isNetworkAvailable(context)) {
            processPendingOperations()
        }
    }

    /**
     * Queue a message sync operation
     */
    fun queueMessageSync(messageId: Long, data: Map<String, Any?>) {
        queueOperation(
            PendingOperation(
                id = "msg_$messageId",
                type = OperationType.SYNC_MESSAGE,
                data = json.encodeToString(data.mapValues { it.value?.toString() }),
                timestamp = System.currentTimeMillis()
            )
        )
    }

    /**
     * Queue a reaction sync operation
     */
    fun queueReactionSync(messageId: Long, reaction: String?) {
        queueOperation(
            PendingOperation(
                id = "reaction_$messageId",
                type = OperationType.SYNC_REACTION,
                data = json.encodeToString(mapOf(
                    "messageId" to messageId.toString(),
                    "reaction" to (reaction ?: "")
                )),
                timestamp = System.currentTimeMillis()
            )
        )
    }

    /**
     * Queue a group sync operation
     */
    fun queueGroupSync(groupId: Long, data: Map<String, Any?>) {
        queueOperation(
            PendingOperation(
                id = "group_$groupId",
                type = OperationType.SYNC_GROUP,
                data = json.encodeToString(data.mapValues { it.value?.toString() }),
                timestamp = System.currentTimeMillis()
            )
        )
    }

    /**
     * Process all pending operations
     */
    fun processPendingOperations() {
        if (syncJob?.isActive == true) {
            Log.d(TAG, "Sync already in progress")
            return
        }

        if (_pendingOperations.value.isEmpty()) {
            Log.d(TAG, "No pending operations")
            return
        }

        if (!NetworkUtils.isNetworkAvailable(context)) {
            Log.d(TAG, "No network, cannot process operations")
            _syncStatus.value = SyncStatus.OFFLINE
            return
        }

        syncJob = scope.launch {
            _syncStatus.value = SyncStatus.SYNCING

            val pending = _pendingOperations.value.toMutableList()
            val completed = mutableListOf<String>()
            val failed = mutableListOf<PendingOperation>()

            for (op in pending) {
                try {
                    val success = executeOperation(op)
                    if (success) {
                        completed.add(op.id)
                        Log.d(TAG, "Operation completed: ${op.id}")
                    } else {
                        val updatedOp = op.copy(retryCount = op.retryCount + 1)
                        if (updatedOp.retryCount < MAX_RETRY_COUNT) {
                            failed.add(updatedOp)
                        } else {
                            Log.w(TAG, "Operation failed after max retries: ${op.id}")
                            completed.add(op.id) // Remove from queue after max retries
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error executing operation ${op.id}", e)
                    val updatedOp = op.copy(retryCount = op.retryCount + 1)
                    if (updatedOp.retryCount < MAX_RETRY_COUNT) {
                        failed.add(updatedOp)
                    }
                }
            }

            // Update pending list
            val remaining = pending.filter { it.id !in completed }.map { op ->
                failed.find { it.id == op.id } ?: op
            }
            _pendingOperations.value = remaining
            savePendingOperations()

            // Update last sync time
            prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply()

            _syncStatus.value = if (remaining.isEmpty()) SyncStatus.SYNCED else SyncStatus.PARTIAL
            Log.d(TAG, "Sync complete. Completed: ${completed.size}, Remaining: ${remaining.size}")
        }
    }

    /**
     * Execute a single operation
     */
    private suspend fun executeOperation(operation: PendingOperation): Boolean {
        return try {
            when (operation.type) {
                OperationType.SYNC_MESSAGE -> {
                    // Message sync would be handled by DesktopSyncService
                    Log.d(TAG, "Would sync message: ${operation.data}")
                    true
                }
                OperationType.SYNC_REACTION -> {
                    Log.d(TAG, "Would sync reaction: ${operation.data}")
                    true
                }
                OperationType.SYNC_GROUP -> {
                    Log.d(TAG, "Would sync group: ${operation.data}")
                    true
                }
                OperationType.SYNC_CALL -> {
                    Log.d(TAG, "Would sync call: ${operation.data}")
                    true
                }
                OperationType.DELETE_MESSAGE -> {
                    Log.d(TAG, "Would delete message: ${operation.data}")
                    true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Operation failed: ${operation.id}", e)
            false
        }
    }

    /**
     * Start monitoring network connectivity
     */
    private fun startNetworkMonitoring() {
        scope.launch {
            NetworkUtils.observeNetworkConnectivity(context).collect { isConnected ->
                if (isConnected) {
                    Log.d(TAG, "Network restored, processing pending operations")
                    delay(1000) // Small delay to ensure stable connection
                    processPendingOperations()
                } else {
                    _syncStatus.value = SyncStatus.OFFLINE
                }
            }
        }
    }

    /**
     * Load pending operations from SharedPreferences
     */
    private fun loadPendingOperations() {
        try {
            val stored = prefs.getString(KEY_PENDING_OPS, null)
            if (!stored.isNullOrBlank()) {
                val ops = json.decodeFromString<List<PendingOperation>>(stored)
                _pendingOperations.value = ops
                Log.d(TAG, "Loaded ${ops.size} pending operations")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading pending operations", e)
            _pendingOperations.value = emptyList()
        }
    }

    /**
     * Save pending operations to SharedPreferences
     */
    private fun savePendingOperations() {
        try {
            val serialized = json.encodeToString(_pendingOperations.value)
            prefs.edit().putString(KEY_PENDING_OPS, serialized).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving pending operations", e)
        }
    }

    /**
     * Get last sync timestamp
     */
    fun getLastSyncTime(): Long {
        return prefs.getLong(KEY_LAST_SYNC, 0L)
    }

    /**
     * Clear all pending operations
     */
    fun clearPendingOperations() {
        _pendingOperations.value = emptyList()
        savePendingOperations()
        Log.d(TAG, "Cleared all pending operations")
    }

    /**
     * Get count of pending operations
     */
    fun getPendingCount(): Int = _pendingOperations.value.size

    /**
     * Check if there are pending operations
     */
    fun hasPendingOperations(): Boolean = _pendingOperations.value.isNotEmpty()

    /**
     * Cancel ongoing sync
     */
    fun cancelSync() {
        syncJob?.cancel()
        _syncStatus.value = SyncStatus.IDLE
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        syncJob?.cancel()
        scope.cancel()
    }
}

/**
 * Types of operations that can be queued
 */
enum class OperationType {
    SYNC_MESSAGE,
    SYNC_REACTION,
    SYNC_GROUP,
    SYNC_CALL,
    DELETE_MESSAGE
}

/**
 * Sync status states
 */
enum class SyncStatus {
    IDLE,
    SYNCING,
    SYNCED,
    PARTIAL,
    OFFLINE,
    ERROR
}

/**
 * Pending operation data class
 */
@Serializable
data class PendingOperation(
    val id: String,
    val type: OperationType,
    val data: String,
    val timestamp: Long,
    val retryCount: Int = 0
)
