/**
 * VPS Client - Replacement for Firebase Realtime Database
 *
 * This client connects to the SyncFlow VPS server instead of Firebase.
 * It provides similar functionality for message sync, contacts, calls, etc.
 */

package com.phoneintegration.app.vps

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.phoneintegration.app.SimManager
import com.google.gson.FieldNamingPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.util.concurrent.TimeUnit

// ==================== Data Classes ====================

data class VPSTokenPair(
    val accessToken: String,
    val refreshToken: String
)

data class VPSUser(
    val userId: String,
    val deviceId: String,
    val admin: Boolean = false
)

data class VPSAuthResponse(
    val userId: String,
    val deviceId: String,
    val accessToken: String,
    val refreshToken: String
)

data class VPSPairingRequest(
    val pairingToken: String,
    val deviceId: String,
    val tempUserId: String,
    val accessToken: String,
    val refreshToken: String
)

data class VPSPairingStatus(
    val status: String,
    val deviceName: String?,
    val approved: Boolean
)

data class VPSMessage(
    val id: String,
    val threadId: Int? = null,
    val address: String,
    val contactName: String? = null,
    val body: String = "",
    val date: Long,
    val type: Int,
    val read: Boolean = false,
    val isMms: Boolean = false,
    val mmsParts: List<Map<String, Any>>? = null,
    val encrypted: Boolean = false,
    val encryptedBody: String? = null,
    val encryptedNonce: String? = null,
    val keyMap: Map<String, String>? = null,
    val simSubscriptionId: Int? = null
)

data class VPSMessagesResponse(
    val messages: List<VPSMessage>,
    val hasMore: Boolean
)

data class VPSSyncResponse(
    val synced: Int,
    val skipped: Int,
    val total: Int
)

data class VPSContact(
    val id: String,
    val displayName: String = "",
    val phoneNumbers: List<String> = emptyList(),
    val emails: List<String> = emptyList(),
    val photoThumbnail: String? = null
)

data class VPSContactsResponse(
    val contacts: List<VPSContact>
)

data class VPSCallHistoryEntry(
    val id: String,
    val phoneNumber: String,
    val contactName: String? = null,
    val callType: String,
    val callDate: Long,
    val duration: Int = 0,
    val simSubscriptionId: Int? = null
)

data class VPSCallsResponse(
    val calls: List<VPSCallHistoryEntry>,
    val hasMore: Boolean
)

data class VPSDevice(
    val id: String,
    @SerializedName("name")
    val deviceName: String = "Unknown",
    val deviceType: String,
    val pairedAt: String? = null,
    val lastSeen: String? = null,
    val isCurrent: Boolean = false
)

data class VPSDevicesResponse(
    val devices: List<VPSDevice>
)

data class VPSOutgoingMessage(
    val id: String,
    val address: String,
    val body: String,
    val timestamp: Long,
    val simSubscriptionId: Int? = null,
    val isMms: Boolean = false,
    val attachments: List<Map<String, Any?>>? = null
)

data class VPSCallRequest(
    val id: String,
    val phoneNumber: String,
    val status: String,
    val requestedAt: Long,
    val simSubscriptionId: Int? = null
)

data class VPSActiveCall(
    val id: String,
    val phoneNumber: String,
    val contactName: String? = null,
    val state: String, // "ringing", "active", "ended"
    val callType: String, // "incoming", "outgoing"
    val timestamp: Long
)

data class VPSCallCommand(
    val id: String,
    val callId: String?,
    val command: String, // "answer", "reject", "end", "make_call"
    val phoneNumber: String?,
    val timestamp: Long,
    val processed: Boolean = false
)

// Account deletion data classes
data class VPSAccountDeletionStatus(
    val isScheduledForDeletion: Boolean,
    val scheduledDeletionAt: Long?,
    val daysRemaining: Int
)

data class VPSAccountDeletionResult(
    val success: Boolean,
    val scheduledDeletionAt: Long? = null,
    val error: String? = null
)

data class VPSCancelDeletionResult(
    val success: Boolean,
    val error: String? = null
)

// Usage data classes
data class VPSUsageData(
    val plan: String?,
    val planExpiresAt: Long?,
    val trialStartedAt: Long?,
    val storageBytes: Long,
    val monthlyUploadBytes: Long,
    val monthlyMmsBytes: Long,
    val monthlyFileBytes: Long,
    val monthlyPhotoBytes: Long = 0,
    val lastUpdatedAt: Long?
)

data class VPSUsageResponse(
    val success: Boolean,
    val usage: VPSUsageData?
)

data class VPSClearMmsResult(
    val success: Boolean,
    val deletedFiles: Int,
    val freedBytes: Long
)

// Phone registration
data class VPSRegisterPhoneResult(
    val success: Boolean,
    val error: String? = null
)

// Support chat
data class VPSSupportChatResult(
    val success: Boolean,
    val response: String? = null,
    val error: String? = null
)

// Read receipts
data class VPSReadReceipt(
    val messageKey: String,
    val readAt: Long,
    val readBy: String,
    val conversationAddress: String,
    val readDeviceName: String? = null,
    val sourceId: Long? = null,
    val sourceType: String? = null
)

// Continuity state
data class VPSContinuityState(
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val type: String,
    val address: String,
    val contactName: String?,
    val threadId: Long?,
    val draft: String?,
    val timestamp: Long
)

// Media control
data class VPSMediaCommand(
    val id: String,
    val action: String,
    val volume: Int? = null,
    val timestamp: Long
)

// DND command
data class VPSDndCommand(
    val id: String,
    val action: String,
    val timestamp: Long
)

// Clipboard data
data class VPSClipboardData(
    val text: String,
    val timestamp: Long,
    val source: String,
    val type: String = "text"
)

// Shared link
data class VPSSharedLink(
    val id: String,
    val url: String,
    val title: String? = null,
    val status: String? = null,
    val timestamp: Long
)

// Hotspot command
data class VPSHotspotCommand(
    val id: String,
    val action: String,
    val timestamp: Long
)

// Find phone request
data class VPSFindPhoneRequest(
    val id: String,
    val action: String,
    val status: String? = null,
    val timestamp: Long
)

// Scheduled message
data class VPSScheduledMessage(
    val id: String,
    val recipientNumber: String,
    val recipientName: String? = null,
    val message: String,
    val scheduledTime: Long,
    val createdAt: Long,
    val status: String,  // "pending", "sent", "failed", "cancelled"
    val simSlot: Int? = null
)

// Spam
data class VPSSpamMessage(
    val id: String,
    val address: String,
    val body: String?,
    val date: Long,
    val spamScore: Float?,
    val spamReason: String?
)

data class VPSSpamMessagesResponse(
    val messages: List<VPSSpamMessage>
)

data class VPSWhitelistEntry(
    val phoneNumber: String,
    val addedAt: Long?
)

data class VPSWhitelistResponse(
    val whitelist: List<VPSWhitelistEntry>
)

data class VPSBlocklistEntry(
    val phoneNumber: String,
    val addedAt: Long?
)

data class VPSBlocklistResponse(
    val blocklist: List<VPSBlocklistEntry>
)

// Response wrappers to avoid Gson type erasure with Map<String, List<T>>
data class VPSOutgoingMessagesResponse(val messages: List<VPSOutgoingMessage> = emptyList())
data class VPSCallRequestsResponse(val requests: List<VPSCallRequest> = emptyList())
data class VPSCallCommandsResponse(val commands: List<VPSCallCommand> = emptyList())
data class VPSReadReceiptsResponse(val receipts: List<VPSReadReceipt> = emptyList())
data class VPSContinuityStatesResponse(val states: List<VPSContinuityState> = emptyList())
data class VPSMediaCommandsResponse(val commands: List<VPSMediaCommand> = emptyList())
data class VPSDndCommandsResponse(val commands: List<VPSDndCommand> = emptyList())
data class VPSSharedLinksResponse(val links: List<VPSSharedLink> = emptyList())
data class VPSHotspotCommandsResponse(val commands: List<VPSHotspotCommand> = emptyList())
data class VPSFindPhoneRequestsResponse(val requests: List<VPSFindPhoneRequest> = emptyList())
data class VPSScheduledMessagesResponse(val messages: List<VPSScheduledMessage> = emptyList())

// File transfer
data class VPSFileTransfer(
    val id: String,
    val fileName: String = "",
    val fileSize: Long = 0,
    val contentType: String? = null,
    val downloadUrl: String? = null,
    val r2Key: String? = null,
    val source: String? = null,
    val status: String? = null,
    val timestamp: Long = 0
)

// File transfers response wrapper (needed for Gson to properly deserialize nested generics)
data class VPSFileTransfersResponse(
    val transfers: List<VPSFileTransfer> = emptyList()
)

// Upload URL response
data class VPSUploadUrlResponse(
    val uploadUrl: String,
    val fileKey: String
)

// Remote contact update
data class VPSRemoteContact(
    val id: String,
    val displayName: String,
    val phoneNumbers: Map<String, Map<String, Any>>? = null,
    val emails: Map<String, Map<String, Any>>? = null,
    val notes: String? = null,
    val photo: Map<String, Any>? = null,
    val androidContactId: Long? = null,
    val sync: Map<String, Any>? = null
)

// ==================== WebRTC Signal Listener ====================

interface WebRTCSignalListener {
    fun onWebRTCSignal(callId: String, signalType: String, signalData: Any, fromDevice: String)
    fun onIncomingSyncFlowCall(callId: String, callerId: String, callerName: String, callType: String)
    fun onSyncFlowCallStatus(callId: String, status: String)
}

// ==================== WebSocket Listener ====================

interface VPSWebSocketListener {
    fun onConnected()
    fun onDisconnected()
    fun onError(error: String)
    fun onMessageAdded(message: VPSMessage)
    fun onMessageUpdated(message: VPSMessage)
    fun onMessageDeleted(messageId: String)
    fun onContactAdded(contact: VPSContact)
    fun onContactUpdated(contact: VPSContact)
    fun onContactDeleted(contactId: String)
    fun onCallAdded(call: VPSCallHistoryEntry)
    fun onOutgoingMessage(message: VPSOutgoingMessage)
    fun onCallRequest(request: VPSCallRequest)
    fun onCallCommand(command: VPSCallCommand)
    fun onDeviceRemoved(deviceId: String)
    fun onDeviceAdded(deviceId: String, deviceName: String?, deviceType: String?)
    fun onE2eeKeyRequest(requestingDeviceId: String, requestingPublicKey: String?)
}

// ==================== VPS Client ====================

class VPSClient private constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "VPSClient"

        @Volatile
        private var instance: VPSClient? = null

        fun getInstance(context: Context): VPSClient {
            return instance ?: synchronized(this) {
                instance ?: VPSClient(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val gson = Gson()
    private val wsGson = GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .create()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val backendConfig = SyncBackendConfig.getInstance(context)
    @Volatile
    private var baseUrl: String = normalizeBaseUrl(backendConfig.vpsUrl.value)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            "syncflow_vps_auth",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private var accessToken: String? = null
    private var refreshToken: String? = null
    private var _userId: String? = null
    private var _deviceId: String? = null

    val userId: String? get() = _userId
    val deviceId: String? get() = _deviceId
    val isAuthenticated: Boolean get() = accessToken != null

    private var webSocket: WebSocket? = null
    private var wsListener: VPSWebSocketListener? = null
    var webrtcSignalListener: WebRTCSignalListener? = null
    private val subscriptions = mutableSetOf<String>()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // State flow for reactive updates
    private val _connectionState = MutableStateFlow(false)
    val connectionState: StateFlow<Boolean> = _connectionState.asStateFlow()

    init {
        // Restore tokens from storage
        accessToken = prefs.getString("accessToken", null)
        refreshToken = prefs.getString("refreshToken", null)
        _userId = prefs.getString("userId", null)
        _deviceId = prefs.getString("deviceId", null)

        // Register FCM token if already authenticated (restored session)
        if (isAuthenticated) {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                .addOnSuccessListener { fcmToken ->
                    scope.launch {
                        try {
                            registerFcmToken(fcmToken)
                        } catch (e: Exception) {
                            Log.w(TAG, "FCM token registration failed: ${e.message}")
                        }
                    }
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "FCM token fetch failed: ${e.message}")
                }
        }

        // Keep base URL in sync with settings
        scope.launch {
            backendConfig.vpsUrl.collect { url ->
                val normalized = normalizeBaseUrl(url)
                if (normalized != baseUrl) {
                    baseUrl = normalized
                    if (isAuthenticated) {
                        disconnectWebSocket()
                        connectWebSocket(wsListener)
                    }
                }
            }
        }
    }

    private fun normalizeBaseUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return SyncBackendConfig.DEFAULT_VPS_URL
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "http://$trimmed"
        }
        return withScheme.trimEnd('/')
    }

    private fun buildWsUrl(token: String): String? {
        val httpUrl = baseUrl.toHttpUrlOrNull() ?: return null
        val wsScheme = if (httpUrl.scheme == "https") "wss" else "ws"
        val wsHost = httpUrl.host
        val wsPort = httpUrl.port
        val wsPath = if (httpUrl.encodedPath.isNullOrBlank() || httpUrl.encodedPath == "/") "" else httpUrl.encodedPath
        // Use the same host:port as the REST API (WebSocket is mounted on the same server)
        val portPart = if ((wsScheme == "wss" && wsPort == 443) || (wsScheme == "ws" && wsPort == 80)) "" else ":$wsPort"
        return "$wsScheme://$wsHost$portPart$wsPath?token=$token"
    }

    // ==================== Authentication ====================

    suspend fun authenticateAnonymous(): VPSUser = withContext(Dispatchers.IO) {
        val response = post<VPSAuthResponse>("/api/auth/anonymous", null)
        saveTokens(response.accessToken, response.refreshToken, response.userId, response.deviceId)
        VPSUser(response.userId, response.deviceId)
    }

    /**
     * Authenticate with Firebase UID.
     * This ensures the VPS user ID matches the Firebase user ID, allowing
     * other devices to pair and see the same messages.
     */
    suspend fun authenticateWithFirebaseUid(
        firebaseUid: String,
        deviceName: String,
        deviceType: String = "android"
    ): VPSUser = withContext(Dispatchers.IO) {
        val body = mapOf(
            "firebaseUid" to firebaseUid,
            "deviceName" to deviceName,
            "deviceType" to deviceType
        )
        val response = post<VPSAuthResponse>("/api/auth/firebase", body, skipAuth = true)
        saveTokens(response.accessToken, response.refreshToken, response.userId, response.deviceId)
        Log.i(TAG, "Authenticated with Firebase UID: ${response.userId}")

        // Auto-register phone numbers from SIM for call lookup (fire-and-forget)
        scope.launch { autoRegisterPhoneNumbers() }

        // Register FCM token for push notifications (fire-and-forget)
        com.google.firebase.messaging.FirebaseMessaging.getInstance().token
            .addOnSuccessListener { fcmToken ->
                scope.launch {
                    try {
                        registerFcmToken(fcmToken)
                    } catch (e: Exception) {
                        Log.w(TAG, "FCM token registration failed: ${e.message}")
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "FCM token fetch failed: ${e.message}")
            }

        VPSUser(response.userId, response.deviceId)
    }

    suspend fun initiatePairing(deviceName: String, deviceType: String = "android"): VPSPairingRequest =
        withContext(Dispatchers.IO) {
            val body = mapOf("deviceName" to deviceName, "deviceType" to deviceType)
            val response = post<VPSPairingRequest>("/api/auth/pair/initiate", body)
            saveTokens(response.accessToken, response.refreshToken, response.tempUserId, response.deviceId)
            response
        }

    suspend fun checkPairingStatus(token: String): VPSPairingStatus = withContext(Dispatchers.IO) {
        get("/api/auth/pair/status/$token")
    }

    suspend fun completePairing(token: String): Unit = withContext(Dispatchers.IO) {
        post<Map<String, Any>>("/api/auth/pair/complete", mapOf("token" to token))
        Unit
    }

    suspend fun redeemPairing(token: String, deviceName: String? = null, deviceType: String? = null): VPSUser =
        withContext(Dispatchers.IO) {
            val body = mutableMapOf<String, Any>("token" to token)
            deviceName?.let { body["deviceName"] = it }
            deviceType?.let { body["deviceType"] = it }

            val response = post<VPSAuthResponse>("/api/auth/pair/redeem", body)
            saveTokens(response.accessToken, response.refreshToken, response.userId, response.deviceId)
            VPSUser(response.userId, response.deviceId)
        }

    suspend fun refreshAccessToken(): Unit = withContext(Dispatchers.IO) {
        val token = refreshToken ?: throw IllegalStateException("No refresh token")
        val body = mapOf("refreshToken" to token)
        val response = post<Map<String, String>>("/api/auth/refresh", body, skipAuth = true)
        accessToken = response["accessToken"]
        prefs.edit().putString("accessToken", accessToken).apply()
    }

    suspend fun getCurrentUser(): VPSUser = withContext(Dispatchers.IO) {
        get("/api/auth/me")
    }

    suspend fun initialize(): Boolean {
        if (accessToken != null && refreshToken != null) {
            return try {
                getCurrentUser()
                connectWebSocket()
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize: ${e.message}")
                false
            }
        }
        return false
    }

    fun logout() {
        disconnectWebSocket()
        accessToken = null
        refreshToken = null
        _userId = null
        _deviceId = null
        prefs.edit().clear().apply()
    }

    private fun saveTokens(access: String, refresh: String, userId: String, deviceId: String) {
        accessToken = access
        refreshToken = refresh
        _userId = userId
        _deviceId = deviceId
        prefs.edit()
            .putString("accessToken", access)
            .putString("refreshToken", refresh)
            .putString("userId", userId)
            .putString("deviceId", deviceId)
            .apply()
    }

    // ==================== Messages ====================

    suspend fun getMessages(
        limit: Int = 100,
        before: Long? = null,
        after: Long? = null,
        threadId: Int? = null
    ): VPSMessagesResponse = withContext(Dispatchers.IO) {
        val params = mutableListOf("limit=$limit")
        before?.let { params.add("before=$it") }
        after?.let { params.add("after=$it") }
        threadId?.let { params.add("threadId=$it") }
        get("/api/messages?${params.joinToString("&")}")
    }

    suspend fun syncMessages(messages: List<Map<String, Any?>>): VPSSyncResponse = withContext(Dispatchers.IO) {
        post("/api/messages/sync", mapOf("messages" to messages))
    }

    suspend fun sendMessage(address: String, body: String, simSubscriptionId: Int? = null): Map<String, String> =
        withContext(Dispatchers.IO) {
            val requestBody = mutableMapOf<String, Any>("address" to address, "body" to body)
            simSubscriptionId?.let { requestBody["simSubscriptionId"] = it }
            post("/api/messages/send", requestBody)
        }

    suspend fun getOutgoingMessages(): List<VPSOutgoingMessage> = withContext(Dispatchers.IO) {
        val response: VPSOutgoingMessagesResponse = get("/api/messages/outgoing")
        response.messages
    }

    suspend fun updateOutgoingStatus(id: String, status: String, error: String? = null): Unit =
        withContext(Dispatchers.IO) {
            val body = mutableMapOf<String, Any>("status" to status)
            error?.let { body["error"] = it }
            put("/api/messages/outgoing/$id/status", body)
        }

    suspend fun updateMessageDeliveryStatus(id: String, status: String): Unit =
        withContext(Dispatchers.IO) {
            put("/api/messages/$id/delivery-status", mapOf("status" to status))
        }

    suspend fun markMessageRead(id: String): Unit = withContext(Dispatchers.IO) {
        put("/api/messages/$id/read", null)
    }

    suspend fun deleteMessage(id: String): Unit = withContext(Dispatchers.IO) {
        try {
            delete("/api/messages/$id")
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting message: ${e.message}")
        }
    }

    // ==================== Contacts ====================

    suspend fun getContacts(search: String? = null, limit: Int = 500): VPSContactsResponse =
        withContext(Dispatchers.IO) {
            val params = mutableListOf("limit=$limit")
            search?.let { params.add("search=$it") }
            get("/api/contacts?${params.joinToString("&")}")
        }

    suspend fun syncContacts(contacts: List<Map<String, Any?>>): VPSSyncResponse = withContext(Dispatchers.IO) {
        post("/api/contacts/sync", mapOf("contacts" to contacts))
    }

    suspend fun getContact(id: String): VPSContact = withContext(Dispatchers.IO) {
        get("/api/contacts/$id")
    }

    // ==================== Call History ====================

    suspend fun getCallHistory(
        limit: Int = 100,
        before: Long? = null,
        after: Long? = null,
        type: String? = null
    ): VPSCallsResponse = withContext(Dispatchers.IO) {
        val params = mutableListOf("limit=$limit")
        before?.let { params.add("before=$it") }
        after?.let { params.add("after=$it") }
        type?.let { params.add("type=$it") }
        get("/api/calls?${params.joinToString("&")}")
    }

    suspend fun syncCallHistory(calls: List<Map<String, Any?>>): VPSSyncResponse =
        withContext(Dispatchers.IO) {
            val normalizedCalls = calls.map { call ->
                var updated = call
                val callDateValue = updated["callDate"] ?: updated["date"]
                if (callDateValue != null && !updated.containsKey("callDate")) {
                    updated = updated + mapOf("callDate" to callDateValue)
                }
                // Normalize phone number to E.164
                val phone = updated["phoneNumber"] as? String
                if (phone != null) {
                    updated = updated + mapOf("phoneNumber" to com.phoneintegration.app.PhoneNumberUtils.toE164(phone))
                }
                val typeValue = call["callType"]
                val normalizedType = when (typeValue) {
                    is String -> typeValue
                    is Number -> callTypeFromInt(typeValue.toInt())
                    else -> null
                }
                if (normalizedType != null) {
                    updated + mapOf("callType" to normalizedType)
                } else {
                    updated
                }
            }
            post("/api/calls/sync", mapOf("calls" to normalizedCalls))
        }

    suspend fun requestCall(phoneNumber: String, simSubscriptionId: Int? = null): Map<String, String> =
        withContext(Dispatchers.IO) {
            val body = mutableMapOf<String, Any>("phoneNumber" to com.phoneintegration.app.PhoneNumberUtils.toE164(phoneNumber))
            simSubscriptionId?.let { body["simSubscriptionId"] = it }
            post("/api/calls/request", body)
        }

    suspend fun getCallRequests(): List<VPSCallRequest> = withContext(Dispatchers.IO) {
        val response: VPSCallRequestsResponse = get("/api/calls/requests")
        response.requests
    }

    suspend fun updateCallRequestStatus(id: String, status: String): Unit =
        withContext(Dispatchers.IO) {
            put("/api/calls/requests/$id/status", mapOf("status" to status))
        }

    // ==================== Active Calls (Real-time) ====================

    suspend fun syncActiveCall(call: VPSActiveCall): Unit = withContext(Dispatchers.IO) {
        post<Map<String, Any>>("/api/calls/active", mapOf(
            "id" to call.id,
            "phoneNumber" to com.phoneintegration.app.PhoneNumberUtils.toE164(call.phoneNumber),
            "contactName" to (call.contactName ?: ""),
            "state" to call.state,
            "callType" to call.callType,
            "timestamp" to call.timestamp
        ))
    }

    suspend fun updateActiveCallState(callId: String, state: String): Unit =
        withContext(Dispatchers.IO) {
            put("/api/calls/active/$callId/state", mapOf("state" to state))
        }

    suspend fun clearActiveCall(callId: String): Unit = withContext(Dispatchers.IO) {
        delete("/api/calls/active/$callId")
    }

    suspend fun getCallCommands(): List<VPSCallCommand> = withContext(Dispatchers.IO) {
        try {
            val response: VPSCallCommandsResponse = get("/api/calls/commands")
            response.commands
        } catch (e: Exception) {
            Log.e(TAG, "Error getting call commands: ${e.message}")
            emptyList()
        }
    }

    suspend fun markCallCommandProcessed(commandId: String): Unit =
        withContext(Dispatchers.IO) {
            try {
                put("/api/calls/commands/$commandId/processed", mapOf("processed" to true))
            } catch (e: Exception) {
                Log.e(TAG, "Error marking call command processed: ${e.message}")
            }
        }

    // ==================== SyncFlow Calls & WebRTC Signaling ====================

    suspend fun getTurnCredentials(): Map<String, Any> = withContext(Dispatchers.IO) {
        get("/api/calls/turn-credentials")
    }

    suspend fun createSyncFlowCall(calleeId: String, calleeName: String, callType: String): Map<String, Any> =
        withContext(Dispatchers.IO) {
            post("/api/calls/syncflow", mapOf(
                "calleeId" to com.phoneintegration.app.PhoneNumberUtils.toE164(calleeId),
                "calleeName" to calleeName,
                "callerName" to (android.os.Build.MODEL ?: "Android"),
                "callerPlatform" to "android",
                "callType" to callType
            ))
        }

    suspend fun updateSyncFlowCallStatus(callId: String, status: String): Unit =
        withContext(Dispatchers.IO) {
            put("/api/calls/syncflow/$callId/status", mapOf("status" to status))
        }

    /**
     * Get pending ringing SyncFlow calls where the current user is the callee.
     * Used as fallback when FCM push notifications fail to wake the app.
     */
    suspend fun getPendingSyncFlowCalls(): List<Map<String, Any>> = withContext(Dispatchers.IO) {
        try {
            val type = object : TypeToken<Map<String, List<Map<String, Any>>>>() {}.type
            val body = executeRequestInternal(
                Request.Builder()
                    .url("${baseUrl}/api/calls/syncflow/pending")
                    .apply { accessToken?.let { addHeader("Authorization", "Bearer $it") } }
                    .build(),
                allowRetry = true
            )
            val response: Map<String, List<Map<String, Any>>> = gson.fromJson(body ?: "{}", type)
            response["calls"] ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting pending SyncFlow calls: ${e.message}")
            emptyList()
        }
    }

    suspend fun getSyncFlowCallStatus(callId: String): String? = withContext(Dispatchers.IO) {
        try {
            val response: Map<String, Any> = get("/api/calls/syncflow/$callId")
            response["status"] as? String
        } catch (e: Exception) {
            Log.e(TAG, "Error getting call status: ${e.message}")
            null
        }
    }

    suspend fun sendSignal(callId: String, signalType: String, signalData: Any, toDevice: String? = null): Unit =
        withContext(Dispatchers.IO) {
            val body = mutableMapOf<String, Any>(
                "callId" to callId,
                "signalType" to signalType,
                "signalData" to signalData
            )
            toDevice?.let { body["toDevice"] = it }
            post<Map<String, Any>>("/api/calls/signaling", body)
        }

    suspend fun getSignals(callId: String): List<Map<String, Any>> = withContext(Dispatchers.IO) {
        try {
            val type = object : TypeToken<Map<String, List<Map<String, Any>>>>() {}.type
            val body = executeRequestInternal(
                Request.Builder()
                    .url("${baseUrl}/api/calls/signaling/$callId")
                    .apply { accessToken?.let { addHeader("Authorization", "Bearer $it") } }
                    .build(),
                allowRetry = true
            )
            val response: Map<String, List<Map<String, Any>>> = gson.fromJson(body ?: "{}", type)
            response["signals"] ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting signals: ${e.message}")
            emptyList()
        }
    }

    suspend fun deleteSignals(callId: String): Unit = withContext(Dispatchers.IO) {
        delete("/api/calls/signaling/$callId")
    }

    fun sendWebSocketSignal(callId: String, signalType: String, signalData: Any, toDevice: String? = null) {
        val msg = mutableMapOf<String, Any>(
            "type" to "webrtc_signal",
            "callId" to callId,
            "signalType" to signalType,
            "signalData" to signalData
        )
        toDevice?.let { msg["toDevice"] = it }
        webSocket?.send(gson.toJson(msg))
    }

    // ==================== Devices ====================

    suspend fun getDevices(): VPSDevicesResponse = withContext(Dispatchers.IO) {
        get("/api/devices")
    }

    suspend fun updateDevice(id: String, name: String? = null, fcmToken: String? = null): Unit =
        withContext(Dispatchers.IO) {
            val body = mutableMapOf<String, Any>()
            name?.let { body["name"] = it }
            fcmToken?.let { body["fcmToken"] = it }
            put("/api/devices/$id", body)
        }

    suspend fun removeDevice(id: String): Unit = withContext(Dispatchers.IO) {
        delete("/api/devices/$id")
    }

    // ==================== Account Deletion ====================

    suspend fun getAccountDeletionStatus(): VPSAccountDeletionStatus = withContext(Dispatchers.IO) {
        try {
            get("/api/account/deletion-status")
        } catch (e: Exception) {
            Log.e(TAG, "Error getting deletion status: ${e.message}")
            // Return not scheduled if API not implemented yet
            VPSAccountDeletionStatus(
                isScheduledForDeletion = false,
                scheduledDeletionAt = null,
                daysRemaining = 0
            )
        }
    }

    suspend fun requestAccountDeletion(reason: String): VPSAccountDeletionResult = withContext(Dispatchers.IO) {
        try {
            post("/api/account/delete", mapOf("reason" to reason))
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting deletion: ${e.message}")
            VPSAccountDeletionResult(success = false, error = e.message)
        }
    }

    suspend fun cancelAccountDeletion(): VPSCancelDeletionResult = withContext(Dispatchers.IO) {
        try {
            post("/api/account/cancel-deletion", null)
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling deletion: ${e.message}")
            VPSCancelDeletionResult(success = false, error = e.message)
        }
    }

    // ==================== Usage & Limits ====================

    suspend fun getUserUsage(): VPSUsageResponse = withContext(Dispatchers.IO) {
        try {
            get("/api/usage")
        } catch (e: Exception) {
            Log.e(TAG, "Error getting usage: ${e.message}")
            // Return default values if API not implemented
            VPSUsageResponse(
                success = true,
                usage = VPSUsageData(
                    plan = null,
                    planExpiresAt = null,
                    trialStartedAt = null,
                    storageBytes = 0,
                    monthlyUploadBytes = 0,
                    monthlyMmsBytes = 0,
                    monthlyFileBytes = 0,
                    monthlyPhotoBytes = 0,
                    lastUpdatedAt = null
                )
            )
        }
    }

    suspend fun clearMmsData(): VPSClearMmsResult = withContext(Dispatchers.IO) {
        try {
            post("/api/usage/reset-storage", null)
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing MMS data: ${e.message}")
            VPSClearMmsResult(success = false, deletedFiles = 0, freedBytes = 0)
        }
    }

    suspend fun recordUsage(bytes: Long, category: String, countsTowardStorage: Boolean): Unit =
        withContext(Dispatchers.IO) {
            try {
                post<Map<String, Any>>("/api/usage/record", mapOf(
                    "bytes" to bytes,
                    "category" to category,
                    "countsTowardStorage" to countsTowardStorage
                ))
            } catch (e: Exception) {
                Log.e(TAG, "Error recording usage: ${e.message}")
            }
        }

    suspend fun clearSyncedMessages(): Unit = withContext(Dispatchers.IO) {
        try {
            delete("/api/messages/all")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing synced messages: ${e.message}")
        }
    }

    suspend fun resetStorageUsage(): Unit = withContext(Dispatchers.IO) {
        try {
            post<Map<String, Any>>("/api/usage/reset-storage", null)
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting storage usage: ${e.message}")
        }
    }

    suspend fun resetMonthlyUsage(): Unit = withContext(Dispatchers.IO) {
        try {
            post<Map<String, Any>>("/api/usage/reset-monthly", null)
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting monthly usage: ${e.message}")
        }
    }

    // ==================== Phone Registration ====================

    suspend fun registerPhoneNumber(phoneNumber: String): VPSRegisterPhoneResult = withContext(Dispatchers.IO) {
        try {
            post("/api/calls/register", mapOf("phoneNumber" to com.phoneintegration.app.PhoneNumberUtils.toE164(phoneNumber)))
        } catch (e: Exception) {
            Log.e(TAG, "Error registering phone: ${e.message}")
            VPSRegisterPhoneResult(success = false, error = e.message)
        }
    }

    /**
     * Auto-register phone numbers from SIM cards for call lookup.
     * Safe to call multiple times — the server uses ON CONFLICT DO UPDATE.
     */
    suspend fun autoRegisterPhoneNumbers() {
        try {
            val simManager = SimManager(context)
            val sims = simManager.getActiveSims()
            for (sim in sims) {
                val phone = sim.phoneNumber
                if (!phone.isNullOrEmpty() && phone != "Unknown") {
                    try {
                        registerPhoneNumber(phone)
                        Log.d(TAG, "Auto-registered phone $phone for call lookup")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to auto-register phone $phone: ${e.message}")
                    }
                }
            }
            if (sims.isEmpty() || sims.all { it.phoneNumber.isNullOrEmpty() || it.phoneNumber == "Unknown" }) {
                Log.w(TAG, "No phone numbers available from SIM to auto-register")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Auto phone registration failed: ${e.message}")
        }
    }

    /**
     * Register FCM token with the VPS server for push notifications.
     */
    suspend fun registerFcmToken(token: String) = withContext(Dispatchers.IO) {
        post<Map<String, Any>>("/api/devices/fcm-token", mapOf(
            "token" to token,
            "platform" to "android"
        ))
        Log.d(TAG, "FCM token registered with VPS server")
    }

    /**
     * Get the current user's registered phone number from the VPS server.
     * Returns null if no phone number is registered.
     */
    suspend fun getMyPhoneNumber(): String? = withContext(Dispatchers.IO) {
        try {
            val result = get<Map<String, Any?>>("/api/calls/my-phone")
            result["phoneNumber"] as? String
        } catch (e: Exception) {
            Log.e(TAG, "Error getting phone number: ${e.message}")
            null
        }
    }

    // ==================== Support Chat ====================

    suspend fun supportChat(message: String, conversationHistory: List<Map<String, String>>): VPSSupportChatResult =
        withContext(Dispatchers.IO) {
            try {
                post("/api/support/chat", mapOf(
                    "message" to message,
                    "conversationHistory" to conversationHistory
                ))
            } catch (e: Exception) {
                Log.e(TAG, "Error in support chat: ${e.message}")
                VPSSupportChatResult(success = false, error = e.message)
            }
        }

    // ==================== Read Receipts ====================

    suspend fun syncReadReceipt(receiptData: Map<String, Any?>): Unit = withContext(Dispatchers.IO) {
        try {
            post<Map<String, Any>>("/api/read-receipts", receiptData)
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing read receipt: ${e.message}")
        }
    }

    suspend fun syncReadReceipts(receipts: List<Map<String, Any?>>): Unit = withContext(Dispatchers.IO) {
        try {
            post<Map<String, Any>>("/api/read-receipts/batch", mapOf("receipts" to receipts))
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing read receipts: ${e.message}")
        }
    }

    suspend fun getReadReceipts(): List<VPSReadReceipt> = withContext(Dispatchers.IO) {
        try {
            val response: VPSReadReceiptsResponse = get("/api/read-receipts")
            response.receipts
        } catch (e: Exception) {
            Log.e(TAG, "Error getting read receipts: ${e.message}")
            emptyList()
        }
    }

    suspend fun cleanupOldReadReceipts(): Unit = withContext(Dispatchers.IO) {
        try {
            delete("/api/read-receipts/cleanup")
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up read receipts: ${e.message}")
        }
    }

    // ==================== Typing Indicators ====================

    suspend fun updateTypingStatus(typingData: Map<String, Any?>): Unit = withContext(Dispatchers.IO) {
        try {
            post<Map<String, Any>>("/api/typing", typingData)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating typing status: ${e.message}")
        }
    }

    suspend fun clearTypingStatus(conversationAddress: String): Unit = withContext(Dispatchers.IO) {
        try {
            delete("/api/typing/${conversationAddress.replace(Regex("[.#\$\\[\\]]"), "_")}")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing typing status: ${e.message}")
        }
    }

    suspend fun clearAllTypingStatus(): Unit = withContext(Dispatchers.IO) {
        try {
            delete("/api/typing")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing all typing status: ${e.message}")
        }
    }

    // ==================== Continuity State ====================

    suspend fun updateContinuityState(
        deviceId: String,
        deviceName: String,
        platform: String,
        type: String,
        address: String,
        contactName: String,
        threadId: Long,
        draft: String
    ): Unit = withContext(Dispatchers.IO) {
        try {
            post<Map<String, Any>>("/api/continuity", mapOf(
                "deviceId" to deviceId,
                "deviceName" to deviceName,
                "platform" to platform,
                "type" to type,
                "address" to address,
                "contactName" to contactName,
                "threadId" to threadId,
                "draft" to draft,
                "timestamp" to System.currentTimeMillis()
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Error updating continuity state: ${e.message}")
        }
    }

    suspend fun getContinuityStates(): List<VPSContinuityState> = withContext(Dispatchers.IO) {
        try {
            val response: VPSContinuityStatesResponse = get("/api/continuity")
            response.states
        } catch (e: Exception) {
            Log.e(TAG, "Error getting continuity states: ${e.message}")
            emptyList()
        }
    }

    // ==================== Data Cleanup ====================

    suspend fun performDataCleanup(): Unit = withContext(Dispatchers.IO) {
        try {
            post<Map<String, Any>>("/api/cleanup", null)
        } catch (e: Exception) {
            Log.e(TAG, "Error performing data cleanup: ${e.message}")
        }
    }

    suspend fun cleanupUnpairedDevice(deviceId: String): Unit = withContext(Dispatchers.IO) {
        try {
            delete("/api/devices/$deviceId/cleanup")
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up unpaired device: ${e.message}")
        }
    }

    suspend fun getCleanupStats(): Map<String, Any> = withContext(Dispatchers.IO) {
        try {
            get("/api/cleanup/stats")
        } catch (e: Exception) {
            Log.e(TAG, "Error getting cleanup stats: ${e.message}")
            emptyMap()
        }
    }

    // ==================== Phone Status ====================

    suspend fun syncPhoneStatus(statusData: Map<String, Any?>): Unit = withContext(Dispatchers.IO) {
        try {
            post<Map<String, Any>>("/api/phone-status", statusData)
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing phone status: ${e.message}")
        }
    }

    // ==================== Media Control ====================

    suspend fun getMediaCommands(): List<VPSMediaCommand> = withContext(Dispatchers.IO) {
        try {
            val response: VPSMediaCommandsResponse = get("/api/media/commands")
            response.commands
        } catch (e: Exception) {
            Log.e(TAG, "Error getting media commands: ${e.message}")
            emptyList()
        }
    }

    suspend fun markMediaCommandProcessed(commandId: String): Unit = withContext(Dispatchers.IO) {
        try {
            put("/api/media/commands/$commandId/processed", mapOf("processed" to true))
        } catch (e: Exception) {
            Log.e(TAG, "Error marking media command processed: ${e.message}")
        }
    }

    suspend fun syncMediaStatus(statusData: Map<String, Any?>): Unit = withContext(Dispatchers.IO) {
        try {
            post<Map<String, Any>>("/api/media/status", statusData)
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing media status: ${e.message}")
        }
    }

    // ==================== DND Sync ====================

    suspend fun getDndCommands(): List<VPSDndCommand> = withContext(Dispatchers.IO) {
        try {
            val response: VPSDndCommandsResponse = get("/api/dnd/commands")
            response.commands
        } catch (e: Exception) {
            Log.e(TAG, "Error getting DND commands: ${e.message}")
            emptyList()
        }
    }

    suspend fun markDndCommandProcessed(commandId: String): Unit = withContext(Dispatchers.IO) {
        try {
            put("/api/dnd/commands/$commandId/processed", mapOf("processed" to true))
        } catch (e: Exception) {
            Log.e(TAG, "Error marking DND command processed: ${e.message}")
        }
    }

    suspend fun syncDndStatus(statusData: Map<String, Any?>): Unit = withContext(Dispatchers.IO) {
        try {
            post<Map<String, Any>>("/api/dnd/status", statusData)
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing DND status: ${e.message}")
        }
    }

    // ==================== Clipboard Sync ====================

    suspend fun getClipboard(): VPSClipboardData? = withContext(Dispatchers.IO) {
        try {
            get("/api/clipboard")
        } catch (e: Exception) {
            Log.e(TAG, "Error getting clipboard: ${e.message}")
            null
        }
    }

    suspend fun syncClipboard(clipboardData: Map<String, Any?>): Unit = withContext(Dispatchers.IO) {
        try {
            post<Map<String, Any>>("/api/clipboard", clipboardData)
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing clipboard: ${e.message}")
        }
    }

    // ==================== Link Sharing ====================

    suspend fun getSharedLinks(): List<VPSSharedLink> = withContext(Dispatchers.IO) {
        try {
            val response: VPSSharedLinksResponse = get("/api/links/shared")
            response.links
        } catch (e: Exception) {
            Log.e(TAG, "Error getting shared links: ${e.message}")
            emptyList()
        }
    }

    suspend fun updateSharedLinkStatus(linkId: String, status: String): Unit = withContext(Dispatchers.IO) {
        try {
            put("/api/links/shared/$linkId/status", mapOf("status" to status))
        } catch (e: Exception) {
            Log.e(TAG, "Error updating shared link status: ${e.message}")
        }
    }

    // ==================== Hotspot Control ====================

    suspend fun getHotspotCommands(): List<VPSHotspotCommand> = withContext(Dispatchers.IO) {
        try {
            val response: VPSHotspotCommandsResponse = get("/api/hotspot/commands")
            response.commands
        } catch (e: Exception) {
            Log.e(TAG, "Error getting hotspot commands: ${e.message}")
            emptyList()
        }
    }

    suspend fun markHotspotCommandProcessed(commandId: String): Unit = withContext(Dispatchers.IO) {
        try {
            put("/api/hotspot/commands/$commandId/processed", mapOf("processed" to true))
        } catch (e: Exception) {
            Log.e(TAG, "Error marking hotspot command processed: ${e.message}")
        }
    }

    suspend fun syncHotspotStatus(statusData: Map<String, Any?>): Unit = withContext(Dispatchers.IO) {
        try {
            post<Map<String, Any>>("/api/hotspot/status", statusData)
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing hotspot status: ${e.message}")
        }
    }

    // ==================== Find My Phone ====================

    suspend fun getFindPhoneRequests(): List<VPSFindPhoneRequest> = withContext(Dispatchers.IO) {
        try {
            val response: VPSFindPhoneRequestsResponse = get("/api/find-phone/requests")
            response.requests
        } catch (e: Exception) {
            Log.e(TAG, "Error getting find phone requests: ${e.message}")
            emptyList()
        }
    }

    suspend fun updateFindPhoneRequestStatus(requestId: String, status: String): Unit = withContext(Dispatchers.IO) {
        try {
            put("/api/find-phone/requests/$requestId/status", mapOf("status" to status))
        } catch (e: Exception) {
            Log.e(TAG, "Error updating find phone request status: ${e.message}")
        }
    }

    // ==================== Notification Mirror ====================

    suspend fun syncMirroredNotification(notificationData: Map<String, Any>): Unit = withContext(Dispatchers.IO) {
        try {
            post<Map<String, Any>>("/api/notifications/mirror", notificationData)
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing mirrored notification: ${e.message}")
        }
    }

    suspend fun removeMirroredNotification(notificationId: String): Unit = withContext(Dispatchers.IO) {
        try {
            delete("/api/notifications/mirror/$notificationId")
        } catch (e: Exception) {
            Log.e(TAG, "Error removing mirrored notification: ${e.message}")
        }
    }

    // ==================== Device-Aware Storage ====================

    suspend fun storeDeviceMessage(deviceId: String, messageId: String, messageData: Map<String, Any>): Unit =
        withContext(Dispatchers.IO) {
            try {
                post<Map<String, Any>>("/api/device-data/$deviceId/messages/$messageId", messageData)
            } catch (e: Exception) {
                Log.e(TAG, "Error storing device message: ${e.message}")
            }
        }

    suspend fun getDeviceMessages(deviceId: String): List<Map<String, Any>> = withContext(Dispatchers.IO) {
        try {
            val type = object : TypeToken<Map<String, List<Map<String, Any>>>>() {}.type
            val body = executeRequestInternal(
                Request.Builder()
                    .url("${baseUrl}/api/device-data/$deviceId/messages")
                    .apply { accessToken?.let { addHeader("Authorization", "Bearer $it") } }
                    .build(),
                allowRetry = true
            )
            val response: Map<String, List<Map<String, Any>>> = gson.fromJson(body ?: "{}", type)
            response["messages"] ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting device messages: ${e.message}")
            emptyList()
        }
    }

    suspend fun storeDeviceSettings(deviceId: String, settings: Map<String, Any>): Unit = withContext(Dispatchers.IO) {
        try {
            post<Map<String, Any>>("/api/device-data/$deviceId/settings", settings)
        } catch (e: Exception) {
            Log.e(TAG, "Error storing device settings: ${e.message}")
        }
    }

    suspend fun getDeviceSettings(deviceId: String): Map<String, Any>? = withContext(Dispatchers.IO) {
        try {
            get("/api/device-data/$deviceId/settings")
        } catch (e: Exception) {
            Log.e(TAG, "Error getting device settings: ${e.message}")
            null
        }
    }

    suspend fun storeSharedData(path: String, data: Any): Unit = withContext(Dispatchers.IO) {
        try {
            post<Map<String, Any>>("/api/shared-data/$path", mapOf("data" to data))
        } catch (e: Exception) {
            Log.e(TAG, "Error storing shared data: ${e.message}")
        }
    }

    suspend fun getSharedData(path: String): Any? = withContext(Dispatchers.IO) {
        try {
            val response: Map<String, Any?> = get("/api/shared-data/$path")
            response["data"]
        } catch (e: Exception) {
            Log.e(TAG, "Error getting shared data: ${e.message}")
            null
        }
    }

    suspend fun cleanupDeviceData(deviceId: String): Unit = withContext(Dispatchers.IO) {
        try {
            delete("/api/device-data/$deviceId")
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up device data: ${e.message}")
        }
    }

    suspend fun migrateUserData(legacyUserId: String, mainUserId: String, deviceId: String): Unit =
        withContext(Dispatchers.IO) {
            try {
                post<Map<String, Any>>("/api/migrate", mapOf(
                    "legacyUserId" to legacyUserId,
                    "mainUserId" to mainUserId,
                    "deviceId" to deviceId
                ))
            } catch (e: Exception) {
                Log.e(TAG, "Error migrating user data: ${e.message}")
            }
        }

    suspend fun getDeviceStorageUsage(deviceId: String): Long = withContext(Dispatchers.IO) {
        try {
            val response: Map<String, Long> = get("/api/device-data/$deviceId/storage-usage")
            response["bytes"] ?: 0L
        } catch (e: Exception) {
            Log.e(TAG, "Error getting device storage usage: ${e.message}")
            0L
        }
    }

    // ==================== Voicemail Sync ====================

    suspend fun syncVoicemail(voicemailData: Map<String, Any?>): Unit = withContext(Dispatchers.IO) {
        try {
            post<Map<String, Any>>("/api/voicemails", voicemailData)
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing voicemail: ${e.message}")
        }
    }

    suspend fun markVoicemailAsRead(voicemailId: String): Unit = withContext(Dispatchers.IO) {
        try {
            put("/api/voicemails/$voicemailId/read", mapOf("isRead" to true))
        } catch (e: Exception) {
            Log.e(TAG, "Error marking voicemail as read: ${e.message}")
        }
    }

    suspend fun deleteVoicemail(voicemailId: String): Unit = withContext(Dispatchers.IO) {
        try {
            delete("/api/voicemails/$voicemailId")
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting voicemail: ${e.message}")
        }
    }

    // ==================== Scheduled Messages ====================

    suspend fun getScheduledMessages(): List<VPSScheduledMessage> = withContext(Dispatchers.IO) {
        try {
            val response: VPSScheduledMessagesResponse = get("/api/scheduled-messages")
            response.messages
        } catch (e: Exception) {
            Log.e(TAG, "Error getting scheduled messages: ${e.message}")
            emptyList()
        }
    }

    suspend fun updateScheduledMessageStatus(
        messageId: String,
        status: String,
        errorMessage: String? = null
    ): Unit = withContext(Dispatchers.IO) {
        try {
            val body = mutableMapOf<String, Any>(
                "status" to status,
                "updatedAt" to System.currentTimeMillis()
            )
            if (status == "sent") {
                body["sentAt"] = System.currentTimeMillis()
            }
            errorMessage?.let { body["errorMessage"] = it }
            put("/api/scheduled-messages/$messageId/status", body)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating scheduled message status: ${e.message}")
        }
    }

    // ==================== File Transfers ====================

    suspend fun getFileTransfers(): List<VPSFileTransfer> = withContext(Dispatchers.IO) {
        try {
            val response: VPSFileTransfersResponse = get("/api/file-transfers")
            response.transfers
        } catch (e: Exception) {
            Log.e(TAG, "Error getting file transfers: ${e.message}")
            emptyList()
        }
    }

    suspend fun getUploadUrl(
        fileName: String,
        contentType: String,
        fileSize: Long,
        transferType: String = "files"
    ): VPSUploadUrlResponse = withContext(Dispatchers.IO) {
        post("/api/file-transfers/upload-url", mapOf(
            "fileName" to fileName,
            "contentType" to contentType,
            "fileSize" to fileSize,
            "transferType" to transferType
        ))
    }

    suspend fun getDownloadUrl(fileKey: String): String = withContext(Dispatchers.IO) {
        val response: Map<String, String> = post("/api/file-transfers/download-url", mapOf("fileKey" to fileKey))
        response["downloadUrl"] ?: throw Exception("No download URL returned")
    }

    suspend fun confirmUpload(
        fileKey: String,
        fileSize: Long,
        transferType: String = "files"
    ): Unit = withContext(Dispatchers.IO) {
        try {
            post<Map<String, Any>>("/api/file-transfers/confirm-upload", mapOf(
                "fileKey" to fileKey,
                "fileSize" to fileSize,
                "transferType" to transferType
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Error confirming upload: ${e.message}")
        }
    }

    suspend fun createFileTransfer(
        fileName: String,
        fileSize: Long,
        contentType: String,
        r2Key: String,
        source: String = "android"
    ): Unit = withContext(Dispatchers.IO) {
        try {
            post<Map<String, Any>>("/api/file-transfers", mapOf(
                "fileName" to fileName,
                "fileSize" to fileSize,
                "contentType" to contentType,
                "r2Key" to r2Key,
                "source" to source,
                "status" to "pending",
                "timestamp" to System.currentTimeMillis()
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Error creating file transfer: ${e.message}")
        }
    }

    suspend fun updateFileTransferStatus(
        transferId: String,
        status: String,
        error: String? = null
    ): Unit = withContext(Dispatchers.IO) {
        try {
            val body = mutableMapOf<String, Any>("status" to status)
            error?.let { body["error"] = it }
            put("/api/file-transfers/$transferId/status", body)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating file transfer status: ${e.message}")
        }
    }

    suspend fun deleteFileTransfer(transferId: String): Unit = withContext(Dispatchers.IO) {
        try {
            delete("/api/file-transfers/$transferId")
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting file transfer: ${e.message}")
        }
    }

    suspend fun deleteR2File(fileKey: String): Unit = withContext(Dispatchers.IO) {
        try {
            post<Map<String, Any>>("/api/file-transfers/delete-file", mapOf("fileKey" to fileKey))
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting R2 file: ${e.message}")
        }
    }

    suspend fun getUserSubscription(): Map<String, Any?> = withContext(Dispatchers.IO) {
        try {
            get("/api/user/subscription")
        } catch (e: Exception) {
            Log.e(TAG, "Error getting subscription: ${e.message}")
            emptyMap()
        }
    }

    // ==================== Remote Contacts (Desktop → Android) ====================

    suspend fun getRemoteContacts(pendingOnly: Boolean = true): List<Map<String, Any?>> = withContext(Dispatchers.IO) {
        try {
            val params = if (pendingOnly) "?pendingAndroidSync=true" else ""
            val type = object : TypeToken<Map<String, List<Map<String, Any?>>>>() {}.type
            val body = executeRequestInternal(
                Request.Builder()
                    .url("${baseUrl}/api/contacts/remote$params")
                    .apply { accessToken?.let { addHeader("Authorization", "Bearer $it") } }
                    .build(),
                allowRetry = true
            )
            val response: Map<String, List<Map<String, Any?>>> = gson.fromJson(body ?: "{}", type)
            response["contacts"] ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting remote contacts: ${e.message}")
            emptyList()
        }
    }

    suspend fun getRemoteContact(contactId: String): Map<String, Any?>? = withContext(Dispatchers.IO) {
        try {
            get("/api/contacts/remote/$contactId")
        } catch (e: Exception) {
            Log.e(TAG, "Error getting remote contact: ${e.message}")
            null
        }
    }

    suspend fun markContactSyncComplete(
        contactId: String,
        androidContactId: Long,
        previousVersion: Long
    ): Unit = withContext(Dispatchers.IO) {
        try {
            put("/api/contacts/remote/$contactId/sync-complete", mapOf(
                "androidContactId" to androidContactId,
                "previousVersion" to previousVersion,
                "lastUpdatedBy" to "android"
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Error marking contact sync complete: ${e.message}")
        }
    }

    // ==================== Photo Sync ====================

    suspend fun getSyncedPhotoIds(): List<Long> = withContext(Dispatchers.IO) {
        try {
            val type = object : TypeToken<Map<String, List<Long>>>() {}.type
            val body = executeRequestInternal(
                Request.Builder()
                    .url("${baseUrl}/api/photos/synced-ids")
                    .apply { accessToken?.let { addHeader("Authorization", "Bearer $it") } }
                    .build(),
                allowRetry = true
            )
            val response: Map<String, List<Long>> = gson.fromJson(body ?: "{}", type)
            response["ids"] ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting synced photo IDs: ${e.message}")
            emptyList()
        }
    }

    suspend fun getPhotos(): List<Map<String, Any?>> = withContext(Dispatchers.IO) {
        try {
            val type = object : TypeToken<Map<String, List<Map<String, Any?>>>>() {}.type
            val body = executeRequestInternal(
                Request.Builder()
                    .url("${baseUrl}/api/photos")
                    .apply { accessToken?.let { addHeader("Authorization", "Bearer $it") } }
                    .build(),
                allowRetry = true
            )
            val response: Map<String, List<Map<String, Any?>>> = gson.fromJson(body ?: "{}", type)
            response["photos"] ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting photos: ${e.message}")
            emptyList()
        }
    }

    suspend fun confirmPhotoUpload(
        fileId: String,
        r2Key: String,
        fileName: String,
        fileSize: Int,
        photoMetadata: Map<String, Any>
    ): Unit = withContext(Dispatchers.IO) {
        try {
            post<Map<String, Any>>("/api/photos/confirm-upload", mapOf(
                "fileId" to fileId,
                "r2Key" to r2Key,
                "fileName" to fileName,
                "fileSize" to fileSize,
                "contentType" to "image/jpeg",
                "transferType" to "photo",
                "photoMetadata" to photoMetadata
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Error confirming photo upload: ${e.message}")
        }
    }

    suspend fun deletePhoto(photoId: String, r2Key: String?): Unit = withContext(Dispatchers.IO) {
        try {
            val body = mutableMapOf<String, Any>("photoId" to photoId)
            r2Key?.let { body["r2Key"] = it }
            post<Map<String, Any>>("/api/photos/delete", body)
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting photo: ${e.message}")
        }
    }

    // ==================== Spam ====================

    suspend fun getSpamMessages(limit: Int = 50): VPSSpamMessagesResponse = withContext(Dispatchers.IO) {
        get("/api/spam/messages?limit=$limit")
    }

    suspend fun syncSpamMessage(message: Map<String, Any?>): Map<String, Any> = withContext(Dispatchers.IO) {
        post("/api/spam/messages", message)
    }

    suspend fun deleteSpamMessage(messageId: String): Unit = withContext(Dispatchers.IO) {
        delete("/api/spam/messages/$messageId")
    }

    suspend fun clearAllSpamMessages(): Unit = withContext(Dispatchers.IO) {
        delete("/api/spam/messages")
    }

    suspend fun getWhitelist(): VPSWhitelistResponse = withContext(Dispatchers.IO) {
        get("/api/spam/whitelist")
    }

    suspend fun addToWhitelist(phoneNumber: String): Unit = withContext(Dispatchers.IO) {
        post<Map<String, Any>>("/api/spam/whitelist", mapOf("phoneNumber" to phoneNumber))
        Unit
    }

    suspend fun removeFromWhitelist(phoneNumber: String): Unit = withContext(Dispatchers.IO) {
        delete("/api/spam/whitelist/$phoneNumber")
    }

    suspend fun getBlocklist(): VPSBlocklistResponse = withContext(Dispatchers.IO) {
        get("/api/spam/blocklist")
    }

    suspend fun addToBlocklist(phoneNumber: String): Unit = withContext(Dispatchers.IO) {
        post<Map<String, Any>>("/api/spam/blocklist", mapOf("phoneNumber" to phoneNumber))
        Unit
    }

    suspend fun removeFromBlocklist(phoneNumber: String): Unit = withContext(Dispatchers.IO) {
        delete("/api/spam/blocklist/$phoneNumber")
    }

    // ==================== E2EE Key Management ====================

    suspend fun publishE2eePublicKey(keyData: Map<String, Any>): Unit = withContext(Dispatchers.IO) {
        try {
            post<Map<String, Any>>("/api/e2ee/public-key", keyData)
        } catch (e: Exception) {
            Log.e(TAG, "Error publishing E2EE public key: ${e.message}")
        }
    }

    suspend fun publishDeviceE2eeKey(deviceId: String, keyData: Map<String, Any>): Unit = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "publishDeviceE2eeKey: deviceId=$deviceId, keyData keys=${keyData.keys}")
            post<Map<String, Any>>("/api/e2ee/device-key/$deviceId", keyData)
            Log.d(TAG, "publishDeviceE2eeKey: success for deviceId=$deviceId")
        } catch (e: Exception) {
            Log.e(TAG, "Error publishing device E2EE key to $deviceId: ${e.message}", e)
            throw e  // Propagate so caller knows the push failed
        }
    }

    suspend fun getE2eePublicKey(userId: String): Map<String, Any?>? = withContext(Dispatchers.IO) {
        try {
            get("/api/e2ee/public-key/$userId")
        } catch (e: Exception) {
            Log.e(TAG, "Error getting E2EE public key: ${e.message}")
            null
        }
    }

    suspend fun getDeviceE2eeKeys(userId: String): Map<String, Map<String, Any?>> = withContext(Dispatchers.IO) {
        try {
            val response: Map<String, Map<String, Any?>> = get("/api/e2ee/device-keys/$userId")
            response
        } catch (e: Exception) {
            Log.e(TAG, "Error getting device E2EE keys: ${e.message}")
            emptyMap()
        }
    }

    suspend fun clearE2eeKeys(): Unit = withContext(Dispatchers.IO) {
        try {
            delete("/api/e2ee/keys")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing E2EE keys: ${e.message}")
        }
    }

    // ==================== WebSocket ====================

    fun connectWebSocket(listener: VPSWebSocketListener? = null) {
        val token = accessToken ?: run {
            Log.w(TAG, "Cannot connect WebSocket: not authenticated")
            return
        }
        // Only update wsListener if a non-null listener is provided.
        // Passing null (e.g. from SyncFlowCallService.ensureWebSocketConnected)
        // must NOT clobber the listener set by VPSSyncService.
        if (listener != null) {
            wsListener = listener
        }

        val wsUrl = buildWsUrl(token) ?: run {
            Log.w(TAG, "Cannot connect WebSocket: invalid base URL")
            return
        }
        val request = Request.Builder().url(wsUrl).build()

        webSocket = httpClient.newWebSocket(request, object : okhttp3.WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                _connectionState.value = true
                wsListener?.onConnected()
                // Subscribe to all channels
                subscriptions.forEach { channel ->
                    webSocket.send(gson.toJson(mapOf("type" to "subscribe", "channel" to channel)))
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val data = gson.fromJson(text, Map::class.java)
                    val type = data["type"] as? String ?: return
                    handleWebSocketMessage(type, data)
                } catch (e: Exception) {
                    Log.e(TAG, "WebSocket message parse error: ${e.message}")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code $reason")
                _connectionState.value = false
                wsListener?.onDisconnected()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error: ${t.message}")
                _connectionState.value = false
                wsListener?.onError(t.message ?: "WebSocket error")
                // Auto-reconnect
                scope.launch {
                    delay(3000)
                    if (isAuthenticated) {
                        connectWebSocket(wsListener)
                    }
                }
            }
        })

        // Subscribe to default channels
        subscribe("messages")
        subscribe("contacts")
        subscribe("calls")
        subscribe("devices")
        subscribe("outgoing")
    }

    fun disconnectWebSocket() {
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        wsListener = null
        _connectionState.value = false
    }

    fun subscribe(channel: String) {
        subscriptions.add(channel)
        webSocket?.send(gson.toJson(mapOf("type" to "subscribe", "channel" to channel)))
    }

    fun unsubscribe(channel: String) {
        subscriptions.remove(channel)
        webSocket?.send(gson.toJson(mapOf("type" to "unsubscribe", "channel" to channel)))
    }

    fun setWebSocketListener(listener: VPSWebSocketListener) {
        wsListener = listener
    }

    private fun handleWebSocketMessage(type: String, data: Map<*, *>) {
        // Handle call-related messages FIRST — these use webrtcSignalListener,
        // NOT wsListener, so they must not be gated behind wsListener != null.
        when (type) {
            "webrtc_signal" -> {
                val payload = extractDataPayload(data) as? Map<*, *> ?: return
                val callId = payload["callId"] as? String ?: return
                val signalType = payload["signalType"] as? String ?: return
                val signalData = payload["signalData"] ?: return
                val fromDevice = payload["fromDevice"] as? String ?: ""
                Log.d(TAG, "WebRTC signal received: type=$signalType, callId=$callId, from=$fromDevice")
                webrtcSignalListener?.onWebRTCSignal(callId, signalType, signalData, fromDevice)
                return
            }
            "syncflow_call_incoming" -> {
                val payload = extractDataPayload(data) as? Map<*, *> ?: return
                val callId = payload["callId"] as? String ?: return
                val callerId = payload["callerId"] as? String ?: ""
                val callerName = payload["callerName"] as? String ?: "Unknown"
                val callType = payload["callType"] as? String ?: "audio"
                Log.d(TAG, "Incoming SyncFlow call: callId=$callId, from=$callerName, listenerSet=${webrtcSignalListener != null}")
                if (webrtcSignalListener != null) {
                    webrtcSignalListener?.onIncomingSyncFlowCall(callId, callerId, callerName, callType)
                } else {
                    // Service not running — start it directly so the call isn't silently dropped
                    Log.w(TAG, "webrtcSignalListener is null, starting SyncFlowCallService directly")
                    val intent = Intent(context, com.phoneintegration.app.SyncFlowCallService::class.java).apply {
                        action = com.phoneintegration.app.SyncFlowCallService.ACTION_INCOMING_USER_CALL
                        putExtra(com.phoneintegration.app.SyncFlowCallService.EXTRA_CALL_ID, callId)
                        putExtra(com.phoneintegration.app.SyncFlowCallService.EXTRA_CALLER_NAME, callerName)
                        putExtra(com.phoneintegration.app.SyncFlowCallService.EXTRA_CALLER_PHONE, callerId)
                        putExtra(com.phoneintegration.app.SyncFlowCallService.EXTRA_IS_VIDEO, callType == "video")
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                }
                return
            }
            "syncflow_call_status" -> {
                val payload = extractDataPayload(data) as? Map<*, *> ?: return
                val callId = payload["callId"] as? String ?: return
                val status = payload["status"] as? String ?: return
                Log.d(TAG, "SyncFlow call status: callId=$callId, status=$status")
                webrtcSignalListener?.onSyncFlowCallStatus(callId, status)

                // Also dismiss incoming call notification if the call ended/was answered elsewhere
                if (status in listOf("ended", "rejected", "missed", "failed", "active")) {
                    val dismissIntent = Intent(context, com.phoneintegration.app.SyncFlowCallService::class.java).apply {
                        action = com.phoneintegration.app.SyncFlowCallService.ACTION_DISMISS_CALL_NOTIFICATION
                        putExtra(com.phoneintegration.app.SyncFlowCallService.EXTRA_CALL_ID, callId)
                    }
                    try {
                        context.startService(dismissIntent)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to dismiss call notification: ${e.message}")
                    }
                }
                return
            }
        }

        // All other message types require wsListener
        val listener = wsListener ?: return
        when (type) {
            "message_added", "message_insert" -> {
                val msg = wsGson.fromJson(wsGson.toJson(extractDataPayload(data)), VPSMessage::class.java)
                listener.onMessageAdded(msg)
            }
            "message_updated", "message_update" -> {
                val msg = wsGson.fromJson(wsGson.toJson(extractDataPayload(data)), VPSMessage::class.java)
                listener.onMessageUpdated(msg)
            }
            "message_deleted", "message_delete" -> {
                val id = (extractDataPayload(data) as? Map<*, *>)?.get("id") as? String ?: return
                listener.onMessageDeleted(id)
            }
            "contact_added", "contact_insert" -> {
                val contact = wsGson.fromJson(wsGson.toJson(extractDataPayload(data)), VPSContact::class.java)
                listener.onContactAdded(contact)
            }
            "contact_updated", "contact_update" -> {
                val contact = wsGson.fromJson(wsGson.toJson(extractDataPayload(data)), VPSContact::class.java)
                listener.onContactUpdated(contact)
            }
            "contact_deleted", "contact_delete" -> {
                val id = (extractDataPayload(data) as? Map<*, *>)?.get("id") as? String ?: return
                listener.onContactDeleted(id)
            }
            "call_added" -> {
                val call = wsGson.fromJson(wsGson.toJson(extractDataPayload(data)), VPSCallHistoryEntry::class.java)
                listener.onCallAdded(call)
            }
            "outgoing_message" -> {
                val msg = wsGson.fromJson(wsGson.toJson(extractDataPayload(data)), VPSOutgoingMessage::class.java)
                listener.onOutgoingMessage(msg)
            }
            "call_request" -> {
                val req = wsGson.fromJson(wsGson.toJson(extractDataPayload(data)), VPSCallRequest::class.java)
                listener.onCallRequest(req)
            }
            "call_command" -> {
                val cmd = wsGson.fromJson(wsGson.toJson(extractDataPayload(data)), VPSCallCommand::class.java)
                listener.onCallCommand(cmd)
            }
            "device_removed", "device_delete" -> {
                val payload = extractDataPayload(data) as? Map<*, *>
                val removedDeviceId = payload?.get("id") as? String
                    ?: payload?.get("deviceId") as? String
                    ?: return
                Log.w(TAG, "Device removed notification: $removedDeviceId")
                listener.onDeviceRemoved(removedDeviceId)
            }
            "device_added", "device_insert" -> {
                val payload = extractDataPayload(data) as? Map<*, *>
                val addedDeviceId = payload?.get("id") as? String
                    ?: payload?.get("deviceId") as? String
                    ?: return
                val deviceName = payload?.get("name") as? String
                    ?: payload?.get("device_name") as? String
                val deviceType = payload?.get("device_type") as? String
                    ?: payload?.get("deviceType") as? String
                Log.d(TAG, "Device added notification: $addedDeviceId (type=$deviceType)")
                listener.onDeviceAdded(addedDeviceId, deviceName, deviceType)
            }
            "e2ee_key_request" -> {
                val payload = extractDataPayload(data) as? Map<*, *>
                val requestingDevice = payload?.get("requestingDevice") as? String ?: return
                val requestingPublicKey = payload?.get("requestingPublicKey") as? String
                Log.d(TAG, "E2EE key request from device: $requestingDevice")
                listener.onE2eeKeyRequest(requestingDevice, requestingPublicKey)
            }
        }
    }

    private fun extractDataPayload(data: Map<*, *>): Any? {
        // Supports server payloads like { data: { action, id, data: {...} } }
        // and client payloads like { data: {...} }
        val outerData = data["data"]
        if (outerData is Map<*, *>) {
            val inner = outerData["data"]
            if (inner != null) return inner
        }
        return outerData
    }

    // ==================== HTTP Helpers ====================

    private inline fun <reified T> get(path: String): T {
        val request = Request.Builder()
            .url("${baseUrl}$path")
            .apply { accessToken?.let { addHeader("Authorization", "Bearer $it") } }
            .build()

        return executeRequest(request)
    }

    private inline fun <reified T> post(path: String, body: Any?, skipAuth: Boolean = false): T {
        val jsonBody = if (body != null) gson.toJson(body) else "{}"
        val request = Request.Builder()
            .url("${baseUrl}$path")
            .post(jsonBody.toRequestBody(jsonMediaType))
            .apply { if (!skipAuth) accessToken?.let { addHeader("Authorization", "Bearer $it") } }
            .build()

        return executeRequest(request)
    }

    private inline fun <reified T> put(path: String, body: Any?): T {
        val jsonBody = if (body != null) gson.toJson(body) else "{}"
        val request = Request.Builder()
            .url("${baseUrl}$path")
            .put(jsonBody.toRequestBody(jsonMediaType))
            .apply { accessToken?.let { addHeader("Authorization", "Bearer $it") } }
            .build()

        return executeRequest(request)
    }

    private fun delete(path: String) {
        val request = Request.Builder()
            .url("${baseUrl}$path")
            .delete()
            .apply { accessToken?.let { addHeader("Authorization", "Bearer $it") } }
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Request failed: ${response.code}")
            }
        }
    }

    private inline fun <reified T> executeRequest(request: Request): T {
        val body = executeRequestInternal(request, allowRetry = true)
        return gson.fromJson(body ?: "{}", T::class.java)
    }

    private fun executeRequestInternal(request: Request, allowRetry: Boolean): String? {
        httpClient.newCall(request).execute().use { response ->
            val bodyString = response.body?.string()
            if (response.isSuccessful) {
                return bodyString
            }

            if (response.code == 401 && allowRetry && refreshToken != null) {
                if (refreshAccessTokenBlocking()) {
                    val newRequest = request.newBuilder()
                        .removeHeader("Authorization")
                        .apply { accessToken?.let { addHeader("Authorization", "Bearer $it") } }
                        .build()
                    return executeRequestInternal(newRequest, allowRetry = false)
                }
            }

            throw IOException("Request failed: ${response.code} - ${bodyString}")
        }
    }

    private fun refreshAccessTokenBlocking(): Boolean {
        val token = refreshToken ?: return false
        val jsonBody = gson.toJson(mapOf("refreshToken" to token))
        val request = Request.Builder()
            .url("${baseUrl}/api/auth/refresh")
            .post(jsonBody.toRequestBody(jsonMediaType))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return false
            val bodyString = response.body?.string() ?: return false
            val parsed = gson.fromJson(bodyString, Map::class.java)
            val newAccessToken = parsed["accessToken"] as? String ?: return false
            accessToken = newAccessToken
            prefs.edit().putString("accessToken", newAccessToken).apply()
            return true
        }
    }

    private fun callTypeFromInt(value: Int): String {
        return when (value) {
            1 -> "incoming"
            2 -> "outgoing"
            3 -> "missed"
            5 -> "rejected"
            6 -> "blocked"
            4 -> "voicemail"
            else -> "missed"
        }
    }
}
