/**
 * Unified Sync Service
 *
 * This service provides a unified interface for syncing messages, contacts, and calls
 * to either Firebase or VPS backend based on the current configuration.
 * It acts as an adapter/facade that allows gradual migration from Firebase to VPS.
 */

package com.phoneintegration.app.vps

import android.content.Context
import android.util.Log
import com.phoneintegration.app.SmsMessage
import com.phoneintegration.app.desktop.DesktopSyncService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Unified sync state
 */
sealed class UnifiedSyncState {
    object Idle : UnifiedSyncState()
    object Syncing : UnifiedSyncState()
    data class Error(val message: String) : UnifiedSyncState()
    object Success : UnifiedSyncState()
}

/**
 * Unified Sync Service that abstracts Firebase and VPS backends
 */
class UnifiedSyncService private constructor(private val context: Context) {

    companion object {
        private const val TAG = "UnifiedSyncService"

        @Volatile
        private var instance: UnifiedSyncService? = null

        fun getInstance(context: Context): UnifiedSyncService {
            return instance ?: synchronized(this) {
                instance ?: UnifiedSyncService(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val appContext = context.applicationContext
    private val backendConfig = SyncBackendConfig.getInstance(appContext)

    // Lazy initialization of services
    private val vpsSyncService: VPSSyncService by lazy {
        VPSSyncService.getInstance(appContext)
    }

    private val firebaseSyncService: DesktopSyncService by lazy {
        DesktopSyncService(appContext)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // State flows
    private val _syncState = MutableStateFlow<UnifiedSyncState>(UnifiedSyncState.Idle)
    val syncState: StateFlow<UnifiedSyncState> = _syncState.asStateFlow()

    // Outgoing messages from desktop/web (for both backends)
    private val _outgoingMessages = MutableSharedFlow<OutgoingMessageData>()
    val outgoingMessages: SharedFlow<OutgoingMessageData> = _outgoingMessages.asSharedFlow()

    // Call requests from desktop/web
    private val _callRequests = MutableSharedFlow<CallRequestData>()
    val callRequests: SharedFlow<CallRequestData> = _callRequests.asSharedFlow()

    init {
        // Forward VPS events to unified streams when using VPS
        scope.launch {
            backendConfig.currentBackend.collect { backend ->
                if (backend == SyncBackend.VPS || backend == SyncBackend.HYBRID) {
                    setupVpsEventForwarding()
                }
            }
        }
    }

    private fun setupVpsEventForwarding() {
        // Forward VPS outgoing messages
        scope.launch {
            vpsSyncService.outgoingMessages.collect { vpsMessage ->
                _outgoingMessages.emit(OutgoingMessageData(
                    id = vpsMessage.id,
                    address = vpsMessage.address,
                    body = vpsMessage.body,
                    timestamp = vpsMessage.timestamp,
                    simSubscriptionId = vpsMessage.simSubscriptionId
                ))
            }
        }

        // Forward VPS call requests
        scope.launch {
            vpsSyncService.callRequests.collect { vpsRequest ->
                _callRequests.emit(CallRequestData(
                    id = vpsRequest.id,
                    phoneNumber = vpsRequest.phoneNumber,
                    status = vpsRequest.status,
                    requestedAt = vpsRequest.requestedAt,
                    simSubscriptionId = vpsRequest.simSubscriptionId
                ))
            }
        }
    }

    /**
     * Get the current backend being used
     */
    fun getCurrentBackend(): SyncBackend = backendConfig.currentBackend.value

    /**
     * Check if user is authenticated (on current backend)
     */
    fun isAuthenticated(): Boolean {
        return when (backendConfig.currentBackend.value) {
            SyncBackend.VPS -> vpsSyncService.isAuthenticated
            SyncBackend.FIREBASE, SyncBackend.HYBRID -> {
                // For Firebase/Hybrid, check Firebase auth
                com.google.firebase.auth.FirebaseAuth.getInstance().currentUser != null
            }
        }
    }

    /**
     * Get current user ID (from appropriate backend)
     */
    suspend fun getCurrentUserId(): String? {
        return when (backendConfig.currentBackend.value) {
            SyncBackend.VPS -> vpsSyncService.userId
            SyncBackend.FIREBASE -> firebaseSyncService.getCurrentUserId()
            SyncBackend.HYBRID -> {
                // In hybrid mode, use Firebase auth but VPS for sync
                firebaseSyncService.getCurrentUserId()
            }
        }
    }

    // ==================== Message Sync ====================

    /**
     * Sync a single message
     */
    suspend fun syncMessage(message: SmsMessage, skipAttachments: Boolean = false) {
        _syncState.value = UnifiedSyncState.Syncing
        try {
            when (backendConfig.currentBackend.value) {
                SyncBackend.VPS -> {
                    vpsSyncService.syncMessage(message, skipAttachments)
                }
                SyncBackend.FIREBASE -> {
                    firebaseSyncService.syncMessage(message, skipAttachments)
                }
                SyncBackend.HYBRID -> {
                    // Sync to both backends
                    coroutineScope {
                        launch { vpsSyncService.syncMessage(message, skipAttachments) }
                        launch { firebaseSyncService.syncMessage(message, skipAttachments) }
                    }
                }
            }
            _syncState.value = UnifiedSyncState.Success
            Log.d(TAG, "Message synced: ${message.id}")
        } catch (e: Exception) {
            _syncState.value = UnifiedSyncState.Error(e.message ?: "Unknown error")
            Log.e(TAG, "Failed to sync message: ${e.message}")
            throw e
        }
    }

    /**
     * Sync multiple messages
     */
    suspend fun syncMessages(messages: List<SmsMessage>) {
        _syncState.value = UnifiedSyncState.Syncing
        try {
            when (backendConfig.currentBackend.value) {
                SyncBackend.VPS -> {
                    vpsSyncService.syncMessages(messages)
                }
                SyncBackend.FIREBASE -> {
                    firebaseSyncService.syncMessages(messages)
                }
                SyncBackend.HYBRID -> {
                    // Sync to both backends
                    coroutineScope {
                        launch { vpsSyncService.syncMessages(messages) }
                        launch { firebaseSyncService.syncMessages(messages) }
                    }
                }
            }
            _syncState.value = UnifiedSyncState.Success
            Log.d(TAG, "Messages synced: ${messages.size}")
        } catch (e: Exception) {
            _syncState.value = UnifiedSyncState.Error(e.message ?: "Unknown error")
            Log.e(TAG, "Failed to sync messages: ${e.message}")
            throw e
        }
    }

    /**
     * Mark message as read
     */
    suspend fun markMessageRead(messageId: String) {
        try {
            when (backendConfig.currentBackend.value) {
                SyncBackend.VPS -> vpsSyncService.markMessageRead(messageId)
                SyncBackend.FIREBASE -> {
                    // Firebase marking handled differently
                    // firebaseSyncService.markMessageRead(messageId)
                }
                SyncBackend.HYBRID -> {
                    vpsSyncService.markMessageRead(messageId)
                    // Also update Firebase if needed
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mark message read: ${e.message}")
        }
    }

    // ==================== Contact Sync ====================

    /**
     * Sync contacts
     * Note: Firebase handles contacts via ContactsSyncWorker, not DesktopSyncService
     */
    suspend fun syncContacts(contacts: List<Map<String, Any?>>) {
        _syncState.value = UnifiedSyncState.Syncing
        try {
            when (backendConfig.currentBackend.value) {
                SyncBackend.VPS -> {
                    vpsSyncService.syncContacts(contacts)
                }
                SyncBackend.FIREBASE -> {
                    // Firebase contacts sync is handled by ContactsSyncWorker
                    // This unified service only supports VPS direct sync
                    Log.d(TAG, "Firebase contacts sync via ContactsSyncWorker")
                }
                SyncBackend.HYBRID -> {
                    // VPS sync only - Firebase handled separately by workers
                    vpsSyncService.syncContacts(contacts)
                }
            }
            _syncState.value = UnifiedSyncState.Success
            Log.d(TAG, "Contacts synced: ${contacts.size}")
        } catch (e: Exception) {
            _syncState.value = UnifiedSyncState.Error(e.message ?: "Unknown error")
            Log.e(TAG, "Failed to sync contacts: ${e.message}")
            throw e
        }
    }

    // ==================== Call History Sync ====================

    /**
     * Sync call history
     * Note: Firebase handles call history via CallHistorySyncWorker, not DesktopSyncService
     */
    suspend fun syncCallHistory(calls: List<Map<String, Any?>>) {
        _syncState.value = UnifiedSyncState.Syncing
        try {
            when (backendConfig.currentBackend.value) {
                SyncBackend.VPS -> {
                    vpsSyncService.syncCallHistory(calls)
                }
                SyncBackend.FIREBASE -> {
                    // Firebase call history sync is handled by CallHistorySyncWorker
                    // This unified service only supports VPS direct sync
                    Log.d(TAG, "Firebase call history sync via CallHistorySyncWorker")
                }
                SyncBackend.HYBRID -> {
                    // VPS sync only - Firebase handled separately by workers
                    vpsSyncService.syncCallHistory(calls)
                }
            }
            _syncState.value = UnifiedSyncState.Success
            Log.d(TAG, "Call history synced: ${calls.size}")
        } catch (e: Exception) {
            _syncState.value = UnifiedSyncState.Error(e.message ?: "Unknown error")
            Log.e(TAG, "Failed to sync call history: ${e.message}")
            throw e
        }
    }

    // ==================== Outgoing Messages ====================

    /**
     * Get pending outgoing messages from desktop/web
     */
    suspend fun getOutgoingMessages(): List<OutgoingMessageData> {
        return when (backendConfig.currentBackend.value) {
            SyncBackend.VPS, SyncBackend.HYBRID -> {
                vpsSyncService.getOutgoingMessages().map { msg ->
                    OutgoingMessageData(
                        id = msg.id,
                        address = msg.address,
                        body = msg.body,
                        timestamp = msg.timestamp,
                        simSubscriptionId = msg.simSubscriptionId
                    )
                }
            }
            SyncBackend.FIREBASE -> {
                // Firebase version - get from Firebase
                emptyList() // TODO: Implement Firebase outgoing messages
            }
        }
    }

    /**
     * Update outgoing message status
     */
    suspend fun updateOutgoingMessageStatus(id: String, status: String, error: String? = null) {
        when (backendConfig.currentBackend.value) {
            SyncBackend.VPS, SyncBackend.HYBRID -> {
                vpsSyncService.updateOutgoingMessageStatus(id, status, error)
            }
            SyncBackend.FIREBASE -> {
                // Firebase version
            }
        }
    }

    // ==================== Call Requests ====================

    /**
     * Get pending call requests from desktop/web
     */
    suspend fun getCallRequests(): List<CallRequestData> {
        return when (backendConfig.currentBackend.value) {
            SyncBackend.VPS, SyncBackend.HYBRID -> {
                vpsSyncService.getCallRequests().map { req ->
                    CallRequestData(
                        id = req.id,
                        phoneNumber = req.phoneNumber,
                        status = req.status,
                        requestedAt = req.requestedAt,
                        simSubscriptionId = req.simSubscriptionId
                    )
                }
            }
            SyncBackend.FIREBASE -> {
                emptyList() // TODO: Implement Firebase call requests
            }
        }
    }

    /**
     * Update call request status
     */
    suspend fun updateCallRequestStatus(id: String, status: String) {
        when (backendConfig.currentBackend.value) {
            SyncBackend.VPS, SyncBackend.HYBRID -> {
                vpsSyncService.updateCallRequestStatus(id, status)
            }
            SyncBackend.FIREBASE -> {
                // Firebase version
            }
        }
    }

    // ==================== Device Management ====================

    /**
     * Get paired devices
     */
    suspend fun getDevices(): List<DeviceData> {
        return when (backendConfig.currentBackend.value) {
            SyncBackend.VPS, SyncBackend.HYBRID -> {
                vpsSyncService.getDevices().map { device ->
                    DeviceData(
                        id = device.id,
                        deviceName = device.deviceName,
                        deviceType = device.deviceType,
                        pairedAt = device.pairedAt,
                        lastSeen = device.lastSeen,
                        isCurrent = device.isCurrent
                    )
                }
            }
            SyncBackend.FIREBASE -> {
                // Firebase version
                emptyList() // TODO: Implement
            }
        }
    }

    /**
     * Remove a device
     */
    suspend fun removeDevice(deviceId: String) {
        when (backendConfig.currentBackend.value) {
            SyncBackend.VPS, SyncBackend.HYBRID -> {
                vpsSyncService.removeDevice(deviceId)
            }
            SyncBackend.FIREBASE -> {
                // Firebase version
            }
        }
    }

    // ==================== Connection Management ====================

    /**
     * Connect WebSocket (for VPS real-time updates)
     */
    fun connectRealtime() {
        when (backendConfig.currentBackend.value) {
            SyncBackend.VPS, SyncBackend.HYBRID -> {
                vpsSyncService.connectWebSocket()
            }
            SyncBackend.FIREBASE -> {
                // Firebase handles this automatically
            }
        }
    }

    /**
     * Disconnect WebSocket
     */
    fun disconnectRealtime() {
        when (backendConfig.currentBackend.value) {
            SyncBackend.VPS, SyncBackend.HYBRID -> {
                vpsSyncService.disconnectWebSocket()
            }
            SyncBackend.FIREBASE -> {
                // Firebase handles this automatically
            }
        }
    }

    /**
     * Get connection state
     */
    val connectionState: StateFlow<Boolean>
        get() = when (backendConfig.currentBackend.value) {
            SyncBackend.VPS, SyncBackend.HYBRID -> vpsSyncService.connectionState
            SyncBackend.FIREBASE -> MutableStateFlow(true).asStateFlow()
        }
}

// ==================== Data Classes ====================

data class OutgoingMessageData(
    val id: String,
    val address: String,
    val body: String,
    val timestamp: Long,
    val simSubscriptionId: Int? = null
)

data class CallRequestData(
    val id: String,
    val phoneNumber: String,
    val status: String,
    val requestedAt: Long,
    val simSubscriptionId: Int? = null
)

data class DeviceData(
    val id: String,
    val deviceName: String = "Unknown",
    val deviceType: String,
    val pairedAt: String? = null,
    val lastSeen: String? = null,
    val isCurrent: Boolean = false
)
