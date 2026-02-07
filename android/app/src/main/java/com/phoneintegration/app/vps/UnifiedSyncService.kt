/**
 * Unified Sync Service - VPS Backend Only
 *
 * This service provides the main interface for syncing messages, contacts, and calls
 * to the VPS backend. Firebase has been completely removed.
 *
 * This is the primary sync service to use throughout the app.
 */

package com.phoneintegration.app.vps

import android.content.Context
import android.util.Log
import com.phoneintegration.app.SmsMessage
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
 * Unified Sync Service - VPS backend only
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

    // VPS Sync Service - the only backend
    private val vpsSyncService: VPSSyncService by lazy {
        VPSSyncService.getInstance(appContext)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // State flows
    private val _syncState = MutableStateFlow<UnifiedSyncState>(UnifiedSyncState.Idle)
    val syncState: StateFlow<UnifiedSyncState> = _syncState.asStateFlow()

    // Outgoing messages from desktop/web
    private val _outgoingMessages = MutableSharedFlow<OutgoingMessageData>()
    val outgoingMessages: SharedFlow<OutgoingMessageData> = _outgoingMessages.asSharedFlow()

    // Call requests from desktop/web
    private val _callRequests = MutableSharedFlow<CallRequestData>()
    val callRequests: SharedFlow<CallRequestData> = _callRequests.asSharedFlow()

    init {
        Log.i(TAG, "UnifiedSyncService initialized (VPS backend only)")
        setupVpsEventForwarding()
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
     * Check if user is authenticated
     */
    fun isAuthenticated(): Boolean {
        return vpsSyncService.isAuthenticated
    }

    /**
     * Get current user ID
     */
    suspend fun getCurrentUserId(): String? {
        return vpsSyncService.userId
    }

    /**
     * Get current device ID
     */
    fun getDeviceId(): String? {
        return vpsSyncService.deviceId
    }

    // ==================== Message Sync ====================

    /**
     * Sync a single message
     */
    suspend fun syncMessage(message: SmsMessage, skipAttachments: Boolean = false) {
        _syncState.value = UnifiedSyncState.Syncing
        try {
            vpsSyncService.syncMessage(message, skipAttachments)
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
            vpsSyncService.syncMessages(messages)
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
            vpsSyncService.markMessageRead(messageId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mark message read: ${e.message}")
        }
    }

    /**
     * Get messages from VPS
     */
    suspend fun getMessages(limit: Int = 100, before: Long? = null): List<VPSMessage> {
        return vpsSyncService.getMessages(limit, before)
    }

    // ==================== Contact Sync ====================

    /**
     * Sync contacts
     */
    suspend fun syncContacts(contacts: List<Map<String, Any?>>) {
        _syncState.value = UnifiedSyncState.Syncing
        try {
            vpsSyncService.syncContacts(contacts)
            _syncState.value = UnifiedSyncState.Success
            Log.d(TAG, "Contacts synced: ${contacts.size}")
        } catch (e: Exception) {
            _syncState.value = UnifiedSyncState.Error(e.message ?: "Unknown error")
            Log.e(TAG, "Failed to sync contacts: ${e.message}")
            throw e
        }
    }

    /**
     * Get contacts from VPS
     */
    suspend fun getContacts(): List<VPSContact> {
        return vpsSyncService.getContacts()
    }

    // ==================== Call History Sync ====================

    /**
     * Sync call history
     */
    suspend fun syncCallHistory(calls: List<Map<String, Any?>>) {
        _syncState.value = UnifiedSyncState.Syncing
        try {
            vpsSyncService.syncCallHistory(calls)
            _syncState.value = UnifiedSyncState.Success
            Log.d(TAG, "Call history synced: ${calls.size}")
        } catch (e: Exception) {
            _syncState.value = UnifiedSyncState.Error(e.message ?: "Unknown error")
            Log.e(TAG, "Failed to sync call history: ${e.message}")
            throw e
        }
    }

    /**
     * Get call history from VPS
     */
    suspend fun getCallHistory(limit: Int = 100): List<VPSCallHistoryEntry> {
        return vpsSyncService.getCallHistory(limit)
    }

    // ==================== Outgoing Messages ====================

    /**
     * Get pending outgoing messages from desktop/web
     */
    suspend fun getOutgoingMessages(): List<OutgoingMessageData> {
        return vpsSyncService.getOutgoingMessages().map { msg ->
            OutgoingMessageData(
                id = msg.id,
                address = msg.address,
                body = msg.body,
                timestamp = msg.timestamp,
                simSubscriptionId = msg.simSubscriptionId
            )
        }
    }

    /**
     * Update outgoing message status
     */
    suspend fun updateOutgoingMessageStatus(id: String, status: String, error: String? = null) {
        vpsSyncService.updateOutgoingMessageStatus(id, status, error)
    }

    // ==================== Call Requests ====================

    /**
     * Get pending call requests from desktop/web
     */
    suspend fun getCallRequests(): List<CallRequestData> {
        return vpsSyncService.getCallRequests().map { req ->
            CallRequestData(
                id = req.id,
                phoneNumber = req.phoneNumber,
                status = req.status,
                requestedAt = req.requestedAt,
                simSubscriptionId = req.simSubscriptionId
            )
        }
    }

    /**
     * Update call request status
     */
    suspend fun updateCallRequestStatus(id: String, status: String) {
        vpsSyncService.updateCallRequestStatus(id, status)
    }

    // ==================== Device Management ====================

    /**
     * Get paired devices
     */
    suspend fun getDevices(): List<DeviceData> {
        return vpsSyncService.getDevices().map { device ->
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

    /**
     * Remove a device
     */
    suspend fun removeDevice(deviceId: String) {
        vpsSyncService.removeDevice(deviceId)
    }

    // ==================== Pairing ====================

    /**
     * Complete pairing (called after scanning QR code)
     */
    suspend fun completePairing(token: String) {
        vpsSyncService.completePairing(token)
    }

    // ==================== Connection Management ====================

    /**
     * Connect WebSocket for real-time updates
     */
    fun connectRealtime() {
        vpsSyncService.connectWebSocket()
    }

    /**
     * Disconnect WebSocket
     */
    fun disconnectRealtime() {
        vpsSyncService.disconnectWebSocket()
    }

    /**
     * Get connection state
     */
    val connectionState: StateFlow<Boolean>
        get() = vpsSyncService.connectionState

    /**
     * Initialize the sync service
     */
    suspend fun initialize(): Boolean {
        return vpsSyncService.initialize()
    }

    /**
     * Authenticate anonymously
     */
    suspend fun authenticateAnonymous(): VPSUser {
        return vpsSyncService.authenticateAnonymous()
    }

    /**
     * Logout
     */
    fun logout() {
        vpsSyncService.logout()
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
