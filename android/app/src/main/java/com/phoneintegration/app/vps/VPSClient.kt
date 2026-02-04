/**
 * VPS Client - Replacement for Firebase Realtime Database
 *
 * This client connects to the SyncFlow VPS server instead of Firebase.
 * It provides similar functionality for message sync, contacts, calls, etc.
 */

package com.phoneintegration.app.vps

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
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
    val callType: Int,
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
    val simSubscriptionId: Int? = null
)

data class VPSCallRequest(
    val id: String,
    val phoneNumber: String,
    val status: String,
    val requestedAt: Long,
    val simSubscriptionId: Int? = null
)

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
}

// ==================== VPS Client ====================

class VPSClient private constructor(
    private val context: Context,
    private val baseUrl: String = "http://5.78.188.206"
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
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

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
    }

    // ==================== Authentication ====================

    suspend fun authenticateAnonymous(): VPSUser = withContext(Dispatchers.IO) {
        val response = post<VPSAuthResponse>("/api/auth/anonymous", null)
        saveTokens(response.accessToken, response.refreshToken, response.userId, response.deviceId)
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
        val response: Map<String, List<VPSOutgoingMessage>> = get("/api/messages/outgoing")
        response["messages"] ?: emptyList()
    }

    suspend fun updateOutgoingStatus(id: String, status: String, error: String? = null): Unit =
        withContext(Dispatchers.IO) {
            val body = mutableMapOf<String, Any>("status" to status)
            error?.let { body["error"] = it }
            put("/api/messages/outgoing/$id/status", body)
        }

    suspend fun markMessageRead(id: String): Unit = withContext(Dispatchers.IO) {
        put("/api/messages/$id/read", null)
    }

    suspend fun deleteMessage(id: String): Unit = withContext(Dispatchers.IO) {
        delete("/api/messages/$id")
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
            post("/api/calls/sync", mapOf("calls" to calls))
        }

    suspend fun requestCall(phoneNumber: String, simSubscriptionId: Int? = null): Map<String, String> =
        withContext(Dispatchers.IO) {
            val body = mutableMapOf<String, Any>("phoneNumber" to phoneNumber)
            simSubscriptionId?.let { body["simSubscriptionId"] = it }
            post("/api/calls/request", body)
        }

    suspend fun getCallRequests(): List<VPSCallRequest> = withContext(Dispatchers.IO) {
        val response: Map<String, List<VPSCallRequest>> = get("/api/calls/requests")
        response["requests"] ?: emptyList()
    }

    suspend fun updateCallRequestStatus(id: String, status: String): Unit =
        withContext(Dispatchers.IO) {
            put("/api/calls/requests/$id/status", mapOf("status" to status))
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

    // ==================== WebSocket ====================

    fun connectWebSocket(listener: VPSWebSocketListener? = null) {
        val token = accessToken ?: run {
            Log.w(TAG, "Cannot connect WebSocket: not authenticated")
            return
        }
        wsListener = listener

        val wsUrl = baseUrl.replace("http", "ws") + ":3001?token=$token"
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
        subscribe("call_requests")
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
        val listener = wsListener ?: return
        when (type) {
            "message_added" -> {
                val msg = gson.fromJson(gson.toJson(data["data"]), VPSMessage::class.java)
                listener.onMessageAdded(msg)
            }
            "message_updated" -> {
                val msg = gson.fromJson(gson.toJson(data["data"]), VPSMessage::class.java)
                listener.onMessageUpdated(msg)
            }
            "message_deleted" -> {
                val id = (data["data"] as? Map<*, *>)?.get("id") as? String ?: return
                listener.onMessageDeleted(id)
            }
            "contact_added" -> {
                val contact = gson.fromJson(gson.toJson(data["data"]), VPSContact::class.java)
                listener.onContactAdded(contact)
            }
            "contact_updated" -> {
                val contact = gson.fromJson(gson.toJson(data["data"]), VPSContact::class.java)
                listener.onContactUpdated(contact)
            }
            "contact_deleted" -> {
                val id = (data["data"] as? Map<*, *>)?.get("id") as? String ?: return
                listener.onContactDeleted(id)
            }
            "call_added" -> {
                val call = gson.fromJson(gson.toJson(data["data"]), VPSCallHistoryEntry::class.java)
                listener.onCallAdded(call)
            }
            "outgoing_message" -> {
                val msg = gson.fromJson(gson.toJson(data["data"]), VPSOutgoingMessage::class.java)
                listener.onOutgoingMessage(msg)
            }
            "call_request" -> {
                val req = gson.fromJson(gson.toJson(data["data"]), VPSCallRequest::class.java)
                listener.onCallRequest(req)
            }
        }
    }

    // ==================== HTTP Helpers ====================

    private inline fun <reified T> get(path: String): T {
        val request = Request.Builder()
            .url("$baseUrl$path")
            .apply { accessToken?.let { addHeader("Authorization", "Bearer $it") } }
            .build()

        return executeRequest(request)
    }

    private inline fun <reified T> post(path: String, body: Any?, skipAuth: Boolean = false): T {
        val jsonBody = if (body != null) gson.toJson(body) else "{}"
        val request = Request.Builder()
            .url("$baseUrl$path")
            .post(jsonBody.toRequestBody(jsonMediaType))
            .apply { if (!skipAuth) accessToken?.let { addHeader("Authorization", "Bearer $it") } }
            .build()

        return executeRequest(request)
    }

    private inline fun <reified T> put(path: String, body: Any?): T {
        val jsonBody = if (body != null) gson.toJson(body) else "{}"
        val request = Request.Builder()
            .url("$baseUrl$path")
            .put(jsonBody.toRequestBody(jsonMediaType))
            .apply { accessToken?.let { addHeader("Authorization", "Bearer $it") } }
            .build()

        return executeRequest(request)
    }

    private fun delete(path: String) {
        val request = Request.Builder()
            .url("$baseUrl$path")
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
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Request failed: ${response.code} - ${response.body?.string()}")
            }
            val body = response.body?.string() ?: "{}"
            return gson.fromJson(body, T::class.java)
        }
    }
}
