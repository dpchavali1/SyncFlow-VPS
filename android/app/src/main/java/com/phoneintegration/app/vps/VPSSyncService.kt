/**
 * VPS Sync Service - Replacement for Firebase-based DesktopSyncService
 *
 * This service handles syncing messages, contacts, and calls to the VPS server
 * instead of Firebase. It provides similar functionality to DesktopSyncService
 * but uses REST API and WebSocket instead of Firebase Realtime Database.
 */

package com.phoneintegration.app.vps

import android.content.Context
import android.util.Log
import com.phoneintegration.app.SmsMessage
import com.phoneintegration.app.e2ee.SignalProtocolManager
import com.phoneintegration.app.data.PreferencesManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class VPSSyncService(context: Context) {

    companion object {
        private const val TAG = "VPSSyncService"

        @Volatile
        private var instance: VPSSyncService? = null

        fun getInstance(context: Context): VPSSyncService {
            return instance ?: synchronized(this) {
                instance ?: VPSSyncService(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val appContext = context.applicationContext
    private val vpsClient = VPSClient.getInstance(appContext)
    private val e2eeManager = SignalProtocolManager(appContext)
    private val preferencesManager = PreferencesManager(appContext)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // State flows
    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val _outgoingMessages = MutableSharedFlow<VPSOutgoingMessage>()
    val outgoingMessages: SharedFlow<VPSOutgoingMessage> = _outgoingMessages.asSharedFlow()

    private val _callRequests = MutableSharedFlow<VPSCallRequest>()
    val callRequests: SharedFlow<VPSCallRequest> = _callRequests.asSharedFlow()

    val isAuthenticated: Boolean get() = vpsClient.isAuthenticated
    val userId: String? get() = vpsClient.userId
    val deviceId: String? get() = vpsClient.deviceId

    sealed class SyncState {
        object Idle : SyncState()
        object Syncing : SyncState()
        data class Error(val message: String) : SyncState()
        object Success : SyncState()
    }

    init {
        // Set up WebSocket listener for real-time events
        vpsClient.setWebSocketListener(object : VPSWebSocketListener {
            override fun onConnected() {
                Log.d(TAG, "WebSocket connected")
            }

            override fun onDisconnected() {
                Log.d(TAG, "WebSocket disconnected")
            }

            override fun onError(error: String) {
                Log.e(TAG, "WebSocket error: $error")
            }

            override fun onMessageAdded(message: VPSMessage) {
                // Handle new message from other device
                Log.d(TAG, "Message added: ${message.id}")
            }

            override fun onMessageUpdated(message: VPSMessage) {
                Log.d(TAG, "Message updated: ${message.id}")
            }

            override fun onMessageDeleted(messageId: String) {
                Log.d(TAG, "Message deleted: $messageId")
            }

            override fun onContactAdded(contact: VPSContact) {
                Log.d(TAG, "Contact added: ${contact.id}")
            }

            override fun onContactUpdated(contact: VPSContact) {
                Log.d(TAG, "Contact updated: ${contact.id}")
            }

            override fun onContactDeleted(contactId: String) {
                Log.d(TAG, "Contact deleted: $contactId")
            }

            override fun onCallAdded(call: VPSCallHistoryEntry) {
                Log.d(TAG, "Call added: ${call.id}")
            }

            override fun onOutgoingMessage(message: VPSOutgoingMessage) {
                // Outgoing message from desktop - need to send via Android
                Log.d(TAG, "Outgoing message received: ${message.id}")
                scope.launch {
                    _outgoingMessages.emit(message)
                }
            }

            override fun onCallRequest(request: VPSCallRequest) {
                // Call request from desktop
                Log.d(TAG, "Call request received: ${request.id}")
                scope.launch {
                    _callRequests.emit(request)
                }
            }
        })
    }

    // ==================== Authentication ====================

    suspend fun initialize(): Boolean {
        return try {
            val success = vpsClient.initialize()
            if (success) {
                Log.d(TAG, "VPS initialized successfully")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize VPS: ${e.message}")
            false
        }
    }

    suspend fun authenticateAnonymous(): VPSUser {
        val user = vpsClient.authenticateAnonymous()
        vpsClient.connectWebSocket()
        return user
    }

    suspend fun completePairing(token: String) {
        vpsClient.completePairing(token)
    }

    fun logout() {
        vpsClient.logout()
    }

    // ==================== Message Sync ====================

    suspend fun syncMessage(message: SmsMessage, skipAttachments: Boolean = false) {
        try {
            _syncState.value = SyncState.Syncing

            val messageMap = buildMessageMap(message, skipAttachments)
            val messages = listOf(messageMap)

            val response = vpsClient.syncMessages(messages)
            Log.d(TAG, "Synced message: ${message.id}, response: synced=${response.synced}")

            _syncState.value = SyncState.Success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync message: ${e.message}")
            _syncState.value = SyncState.Error(e.message ?: "Unknown error")
            throw e
        }
    }

    suspend fun syncMessages(messages: List<SmsMessage>) {
        try {
            _syncState.value = SyncState.Syncing

            val messageMaps = messages.map { buildMessageMap(it, skipAttachments = true) }

            // Sync in batches of 50
            messageMaps.chunked(50).forEach { batch ->
                val response = vpsClient.syncMessages(batch)
                Log.d(TAG, "Synced batch: synced=${response.synced}, skipped=${response.skipped}")
            }

            _syncState.value = SyncState.Success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync messages: ${e.message}")
            _syncState.value = SyncState.Error(e.message ?: "Unknown error")
            throw e
        }
    }

    private suspend fun buildMessageMap(message: SmsMessage, skipAttachments: Boolean): Map<String, Any?> {
        val isE2eeEnabled = preferencesManager.isE2eeEnabled()

        var body = message.body
        var encryptedBody: String? = null

        // Encrypt if E2EE is enabled
        if (isE2eeEnabled && !message.body.isNullOrEmpty()) {
            try {
                encryptedBody = e2eeManager.encryptForSync(message.body)
                body = "[Encrypted]"
            } catch (e: Exception) {
                Log.w(TAG, "E2EE encryption failed, sending unencrypted: ${e.message}")
            }
        }

        return mapOf(
            "id" to message.id.toString(),
            "threadId" to message.threadId,
            "address" to message.address,
            "body" to body,
            "date" to message.date,
            "type" to message.type,
            "read" to message.read,
            "isMms" to message.isMms,
            "encrypted" to (encryptedBody != null),
            "encryptedBody" to encryptedBody,
            "simSubscriptionId" to message.subscriptionId
        )
    }

    suspend fun getMessages(limit: Int = 100, before: Long? = null): List<VPSMessage> {
        return try {
            val response = vpsClient.getMessages(limit, before)
            response.messages
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get messages: ${e.message}")
            emptyList()
        }
    }

    suspend fun markMessageRead(messageId: String) {
        try {
            vpsClient.markMessageRead(messageId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mark message read: ${e.message}")
        }
    }

    // ==================== Contact Sync ====================

    suspend fun syncContacts(contacts: List<Map<String, Any?>>) {
        try {
            _syncState.value = SyncState.Syncing

            // Sync in batches of 100
            contacts.chunked(100).forEach { batch ->
                val response = vpsClient.syncContacts(batch)
                Log.d(TAG, "Synced contacts batch: synced=${response.synced}")
            }

            _syncState.value = SyncState.Success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync contacts: ${e.message}")
            _syncState.value = SyncState.Error(e.message ?: "Unknown error")
            throw e
        }
    }

    suspend fun getContacts(): List<VPSContact> {
        return try {
            val response = vpsClient.getContacts()
            response.contacts
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get contacts: ${e.message}")
            emptyList()
        }
    }

    // ==================== Call History Sync ====================

    suspend fun syncCallHistory(calls: List<Map<String, Any?>>) {
        try {
            _syncState.value = SyncState.Syncing

            // Sync in batches of 100
            calls.chunked(100).forEach { batch ->
                val response = vpsClient.syncCallHistory(batch)
                Log.d(TAG, "Synced call history batch: synced=${response.synced}")
            }

            _syncState.value = SyncState.Success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync call history: ${e.message}")
            _syncState.value = SyncState.Error(e.message ?: "Unknown error")
            throw e
        }
    }

    suspend fun getCallHistory(limit: Int = 100): List<VPSCallHistoryEntry> {
        return try {
            val response = vpsClient.getCallHistory(limit)
            response.calls
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get call history: ${e.message}")
            emptyList()
        }
    }

    // ==================== Outgoing Messages ====================

    suspend fun getOutgoingMessages(): List<VPSOutgoingMessage> {
        return try {
            vpsClient.getOutgoingMessages()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get outgoing messages: ${e.message}")
            emptyList()
        }
    }

    suspend fun updateOutgoingMessageStatus(id: String, status: String, error: String? = null) {
        try {
            vpsClient.updateOutgoingStatus(id, status, error)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update outgoing message status: ${e.message}")
        }
    }

    // ==================== Call Requests ====================

    suspend fun getCallRequests(): List<VPSCallRequest> {
        return try {
            vpsClient.getCallRequests()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get call requests: ${e.message}")
            emptyList()
        }
    }

    suspend fun updateCallRequestStatus(id: String, status: String) {
        try {
            vpsClient.updateCallRequestStatus(id, status)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update call request status: ${e.message}")
        }
    }

    // ==================== Devices ====================

    suspend fun getDevices(): List<VPSDevice> {
        return try {
            val response = vpsClient.getDevices()
            response.devices
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get devices: ${e.message}")
            emptyList()
        }
    }

    suspend fun removeDevice(deviceId: String) {
        try {
            vpsClient.removeDevice(deviceId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove device: ${e.message}")
        }
    }

    // ==================== Connection ====================

    fun connectWebSocket() {
        vpsClient.connectWebSocket()
    }

    fun disconnectWebSocket() {
        vpsClient.disconnectWebSocket()
    }

    val connectionState: StateFlow<Boolean> get() = vpsClient.connectionState
}
