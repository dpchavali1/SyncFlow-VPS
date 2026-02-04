/**
 * SyncFlow Service - Android Application Integration
 *
 * High-level service that wraps SyncFlowApiClient with:
 * - Token persistence (EncryptedSharedPreferences)
 * - Auto-reconnection
 * - Background sync with WorkManager
 * - LiveData/Flow state management
 */

package com.syncflow.services

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.syncflow.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

// ==================== State ====================

data class SyncFlowState(
    val isAuthenticated: Boolean = false,
    val isConnected: Boolean = false,
    val isPaired: Boolean = false,
    val userId: String? = null,
    val deviceId: String? = null,
    val messages: List<Message> = emptyList(),
    val contacts: List<Contact> = emptyList(),
    val calls: List<CallHistoryEntry> = emptyList(),
    val devices: List<Device> = emptyList()
)

// ==================== Service ====================

class SyncFlowService private constructor(
    private val context: Context,
    apiUrl: String = "http://5.78.188.206"
) {
    private val client = SyncFlowApiClient(apiUrl)
    private val prefs: SharedPreferences
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _state = MutableStateFlow(SyncFlowState())
    val state: StateFlow<SyncFlowState> = _state.asStateFlow()

    // Event flows
    private val _messageEvents = MutableSharedFlow<Message>()
    val messageEvents: SharedFlow<Message> = _messageEvents.asSharedFlow()

    private val _connectionEvents = MutableSharedFlow<Boolean>()
    val connectionEvents: SharedFlow<Boolean> = _connectionEvents.asSharedFlow()

    init {
        // Initialize encrypted preferences
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context,
            "syncflow_auth",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // ==================== Initialization ====================

    suspend fun init(): Boolean {
        val accessToken = prefs.getString("accessToken", null)
        val refreshToken = prefs.getString("refreshToken", null)
        val userId = prefs.getString("userId", null)
        val deviceId = prefs.getString("deviceId", null)

        if (accessToken != null && refreshToken != null) {
            client.setTokens(accessToken, refreshToken)

            return try {
                client.getCurrentUser()
                updateState {
                    copy(
                        isAuthenticated = true,
                        isPaired = true,
                        userId = userId,
                        deviceId = deviceId
                    )
                }
                connectWebSocket()
                true
            } catch (e: Exception) {
                clearSession()
                false
            }
        }

        return false
    }

    // ==================== Authentication ====================

    suspend fun authenticateAnonymous(): User {
        val user = client.authenticateAnonymous()
        saveSession(user)
        updateState { copy(isAuthenticated = true, userId = user.userId, deviceId = user.deviceId) }
        return user
    }

    suspend fun initiatePairing(deviceName: String): String {
        val result = client.initiatePairing(deviceName, "android")
        updateState { copy(deviceId = result.deviceId) }
        return result.pairingToken
    }

    suspend fun completePairing(token: String) {
        client.completePairing(token)
    }

    suspend fun checkPairingStatus(token: String): Boolean {
        val status = client.checkPairingStatus(token)
        return status.approved
    }

    suspend fun redeemPairing(token: String): User {
        val user = client.redeemPairing(token, null, null)
        saveSession(user)
        updateState {
            copy(
                isAuthenticated = true,
                isPaired = true,
                userId = user.userId,
                deviceId = user.deviceId
            )
        }
        connectWebSocket()
        return user
    }

    fun logout() {
        client.disconnectWebSocket()
        clearSession()
        updateState { SyncFlowState() }
    }

    private fun saveSession(user: User) {
        val tokens = client.getTokens() ?: return
        prefs.edit().apply {
            putString("accessToken", tokens.accessToken)
            putString("refreshToken", tokens.refreshToken)
            putString("userId", user.userId)
            putString("deviceId", user.deviceId)
            apply()
        }
    }

    private fun clearSession() {
        prefs.edit().clear().apply()
    }

    // ==================== Messages ====================

    suspend fun loadMessages(limit: Int = 100, before: Long? = null): List<Message> {
        val response = client.getMessages(limit, before)
        val existing = _state.value.messages.associateBy { it.id }
        val merged = (response.messages + existing.values)
            .distinctBy { it.id }
            .sortedByDescending { it.date }

        updateState { copy(messages = merged) }
        return response.messages
    }

    suspend fun syncMessages(messages: List<Message>): SyncResponse {
        return client.syncMessages(messages)
    }

    suspend fun sendMessage(address: String, body: String, simSubscriptionId: Int? = null) {
        client.sendMessage(address, body, simSubscriptionId)
    }

    suspend fun getOutgoingMessages(): List<OutgoingMessage> {
        return client.getOutgoingMessages()
    }

    suspend fun updateOutgoingStatus(id: String, status: String, error: String? = null) {
        client.updateOutgoingStatus(id, status, error)
    }

    suspend fun markAsRead(messageId: String) {
        client.markMessageRead(messageId)
        updateState {
            copy(messages = messages.map {
                if (it.id == messageId) it.copy(read = true) else it
            })
        }
    }

    // ==================== Contacts ====================

    suspend fun loadContacts(): List<Contact> {
        val response = client.getContacts()
        updateState { copy(contacts = response.contacts) }
        return response.contacts
    }

    suspend fun syncContacts(contacts: List<Contact>): SyncResponse {
        return client.syncContacts(contacts)
    }

    suspend fun searchContacts(query: String): List<Contact> {
        return client.getContacts(query).contacts
    }

    // ==================== Call History ====================

    suspend fun loadCallHistory(limit: Int = 100, before: Long? = null): List<CallHistoryEntry> {
        val response = client.getCallHistory(limit, before)
        val existing = _state.value.calls.associateBy { it.id }
        val merged = (response.calls + existing.values)
            .distinctBy { it.id }
            .sortedByDescending { it.callDate }

        updateState { copy(calls = merged) }
        return response.calls
    }

    suspend fun syncCallHistory(calls: List<CallHistoryEntry>): SyncResponse {
        return client.syncCallHistory(calls)
    }

    suspend fun getCallRequests(): List<CallRequest> {
        return client.getCallRequests()
    }

    suspend fun updateCallRequestStatus(id: String, status: String) {
        client.updateCallRequestStatus(id, status)
    }

    // ==================== Devices ====================

    suspend fun loadDevices(): List<Device> {
        val response = client.getDevices()
        updateState { copy(devices = response.devices) }
        return response.devices
    }

    suspend fun updateDevice(id: String, name: String? = null, fcmToken: String? = null) {
        client.updateDevice(id, name, fcmToken)
    }

    suspend fun removeDevice(id: String) {
        client.removeDevice(id)
        updateState { copy(devices = devices.filter { it.id != id }) }
    }

    // ==================== WebSocket ====================

    private fun connectWebSocket() {
        client.connectWebSocket(object : SyncFlowWebSocketListener {
            override fun onConnected() {
                scope.launch {
                    updateState { copy(isConnected = true) }
                    _connectionEvents.emit(true)
                }
                client.subscribe("messages")
                client.subscribe("contacts")
                client.subscribe("calls")
                client.subscribe("devices")
                client.subscribe("outgoing")
                client.subscribe("call_requests")
            }

            override fun onDisconnected() {
                scope.launch {
                    updateState { copy(isConnected = false) }
                    _connectionEvents.emit(false)
                }
                // Auto-reconnect after delay
                scope.launch {
                    delay(3000)
                    if (_state.value.isAuthenticated) {
                        connectWebSocket()
                    }
                }
            }

            override fun onError(error: String) {
                // Log error
            }

            override fun onMessageAdded(message: Message) {
                scope.launch {
                    val current = _state.value.messages
                    if (current.none { it.id == message.id }) {
                        updateState {
                            copy(messages = listOf(message) + messages)
                        }
                        _messageEvents.emit(message)
                    }
                }
            }

            override fun onMessageUpdated(message: Message) {
                scope.launch {
                    updateState {
                        copy(messages = messages.map {
                            if (it.id == message.id) message else it
                        })
                    }
                }
            }

            override fun onMessageDeleted(messageId: String) {
                scope.launch {
                    updateState {
                        copy(messages = messages.filter { it.id != messageId })
                    }
                }
            }

            override fun onContactAdded(contact: Contact) {
                scope.launch {
                    val current = _state.value.contacts
                    if (current.none { it.id == contact.id }) {
                        updateState { copy(contacts = contacts + contact) }
                    }
                }
            }

            override fun onContactUpdated(contact: Contact) {
                scope.launch {
                    updateState {
                        copy(contacts = contacts.map {
                            if (it.id == contact.id) contact else it
                        })
                    }
                }
            }

            override fun onContactDeleted(contactId: String) {
                scope.launch {
                    updateState {
                        copy(contacts = contacts.filter { it.id != contactId })
                    }
                }
            }

            override fun onCallAdded(call: CallHistoryEntry) {
                scope.launch {
                    val current = _state.value.calls
                    if (current.none { it.id == call.id }) {
                        updateState { copy(calls = listOf(call) + calls) }
                    }
                }
            }

            override fun onOutgoingMessage(message: OutgoingMessage) {
                // Handle outgoing message - Android should process and send
            }

            override fun onCallRequest(request: CallRequest) {
                // Handle call request - Android should initiate call
            }
        })
    }

    fun disconnectWebSocket() {
        client.disconnectWebSocket()
    }

    // ==================== State Helpers ====================

    private inline fun updateState(update: SyncFlowState.() -> SyncFlowState) {
        _state.value = _state.value.update()
    }

    // ==================== Singleton ====================

    companion object {
        @Volatile
        private var instance: SyncFlowService? = null

        fun getInstance(context: Context): SyncFlowService {
            return instance ?: synchronized(this) {
                instance ?: SyncFlowService(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}
