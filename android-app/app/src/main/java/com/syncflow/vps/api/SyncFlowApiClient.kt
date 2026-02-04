/**
 * SyncFlow VPS Client - Android/Kotlin
 */

package com.syncflow.vps.api

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

// ==================== Data Classes ====================

data class TokenPair(
    val accessToken: String,
    val refreshToken: String
)

data class User(
    val userId: String,
    val deviceId: String,
    val admin: Boolean = false
)

data class AuthResponse(
    val userId: String,
    val deviceId: String,
    val accessToken: String,
    val refreshToken: String
)

data class PairingRequest(
    val pairingToken: String,
    val deviceId: String,
    val tempUserId: String,
    val accessToken: String,
    val refreshToken: String
)

data class PairingStatus(
    val status: String,
    val deviceName: String?,
    val approved: Boolean
)

data class Message(
    val id: String,
    val threadId: Int? = null,
    val address: String,
    val contactName: String? = null,
    val body: String = "",
    val date: Long,
    val type: Int,
    val read: Boolean = false,
    val isMms: Boolean = false,
    val mmsParts: Any? = null,
    val encrypted: Boolean = false
)

data class MessagesResponse(
    val messages: List<Message>,
    val hasMore: Boolean
)

data class SyncResponse(
    val synced: Int,
    val skipped: Int,
    val total: Int
)

data class Contact(
    val id: String,
    val displayName: String = "",
    val phoneNumbers: List<String> = emptyList(),
    val emails: List<String> = emptyList(),
    val photoThumbnail: String? = null
)

data class ContactsResponse(
    val contacts: List<Contact>
)

data class CallHistoryEntry(
    val id: String,
    val phoneNumber: String,
    val contactName: String? = null,
    val callType: Int,
    val callDate: Long,
    val duration: Int = 0,
    val simSubscriptionId: Int? = null
)

data class CallsResponse(
    val calls: List<CallHistoryEntry>,
    val hasMore: Boolean
)

data class Device(
    val id: String,
    val deviceName: String = "Unknown",
    val deviceType: String,
    val pairedAt: String? = null,
    val lastSeen: String? = null,
    val isCurrent: Boolean = false
)

data class DevicesResponse(
    val devices: List<Device>
)

data class OutgoingMessage(
    val id: String,
    val address: String,
    val body: String,
    val timestamp: Long,
    val simSubscriptionId: Int? = null
)

data class CallRequest(
    val id: String,
    val phoneNumber: String,
    val status: String,
    val requestedAt: Long,
    val simSubscriptionId: Int? = null
)

// ==================== WebSocket Listener ====================

interface SyncFlowWebSocketListener {
    fun onConnected()
    fun onDisconnected()
    fun onError(error: String)
    fun onMessageAdded(message: Message)
    fun onMessageUpdated(message: Message)
    fun onMessageDeleted(messageId: String)
    fun onContactAdded(contact: Contact)
    fun onContactUpdated(contact: Contact)
    fun onContactDeleted(contactId: String)
    fun onCallAdded(call: CallHistoryEntry)
    fun onOutgoingMessage(message: OutgoingMessage)
    fun onCallRequest(request: CallRequest)
}

// ==================== API Client ====================

class SyncFlowApiClient(
    private val baseUrl: String = "http://5.78.188.206"
) {
    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var accessToken: String? = null
    private var refreshToken: String? = null
    private var userId: String? = null
    private var deviceId: String? = null

    private var webSocket: WebSocket? = null
    private var wsListener: SyncFlowWebSocketListener? = null
    private val subscriptions = mutableSetOf<String>()

    // ==================== Authentication ====================

    suspend fun authenticateAnonymous(): User = withContext(Dispatchers.IO) {
        val response = post<AuthResponse>("/api/auth/anonymous", null)
        setTokens(response.accessToken, response.refreshToken)
        userId = response.userId
        deviceId = response.deviceId
        User(response.userId, response.deviceId)
    }

    suspend fun initiatePairing(deviceName: String, deviceType: String): PairingRequest =
        withContext(Dispatchers.IO) {
            val body = mapOf("deviceName" to deviceName, "deviceType" to deviceType)
            val response = post<PairingRequest>("/api/auth/pair/initiate", body)
            setTokens(response.accessToken, response.refreshToken)
            deviceId = response.deviceId
            response
        }

    suspend fun checkPairingStatus(token: String): PairingStatus = withContext(Dispatchers.IO) {
        get("/api/auth/pair/status/$token")
    }

    suspend fun completePairing(token: String): Unit = withContext(Dispatchers.IO) {
        post<Map<String, Any>>("/api/auth/pair/complete", mapOf("token" to token))
        Unit
    }

    suspend fun redeemPairing(token: String, deviceName: String?, deviceType: String?): User =
        withContext(Dispatchers.IO) {
            val body = mutableMapOf<String, Any>("token" to token)
            deviceName?.let { body["deviceName"] = it }
            deviceType?.let { body["deviceType"] = it }

            val response = post<AuthResponse>("/api/auth/pair/redeem", body)
            setTokens(response.accessToken, response.refreshToken)
            userId = response.userId
            deviceId = response.deviceId
            User(response.userId, response.deviceId)
        }

    suspend fun refreshAccessToken(): Unit = withContext(Dispatchers.IO) {
        val token = refreshToken ?: throw IllegalStateException("No refresh token")
        val body = mapOf("refreshToken" to token)
        val response = post<Map<String, String>>("/api/auth/refresh", body, skipAuth = true)
        accessToken = response["accessToken"]
    }

    suspend fun getCurrentUser(): User = withContext(Dispatchers.IO) {
        get("/api/auth/me")
    }

    fun setTokens(access: String, refresh: String) {
        accessToken = access
        refreshToken = refresh
    }

    fun getTokens(): TokenPair? {
        val access = accessToken ?: return null
        val refresh = refreshToken ?: return null
        return TokenPair(access, refresh)
    }

    // ==================== Messages ====================

    suspend fun getMessages(
        limit: Int = 100,
        before: Long? = null,
        after: Long? = null,
        threadId: Int? = null
    ): MessagesResponse = withContext(Dispatchers.IO) {
        val params = mutableListOf("limit=$limit")
        before?.let { params.add("before=$it") }
        after?.let { params.add("after=$it") }
        threadId?.let { params.add("threadId=$it") }
        get("/api/messages?${params.joinToString("&")}")
    }

    suspend fun syncMessages(messages: List<Message>): SyncResponse = withContext(Dispatchers.IO) {
        post("/api/messages/sync", mapOf("messages" to messages))
    }

    suspend fun sendMessage(address: String, body: String, simSubscriptionId: Int? = null): Map<String, String> =
        withContext(Dispatchers.IO) {
            val requestBody = mutableMapOf<String, Any>("address" to address, "body" to body)
            simSubscriptionId?.let { requestBody["simSubscriptionId"] = it }
            post("/api/messages/send", requestBody)
        }

    suspend fun getOutgoingMessages(): List<OutgoingMessage> = withContext(Dispatchers.IO) {
        val response: Map<String, List<OutgoingMessage>> = get("/api/messages/outgoing")
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

    // ==================== Contacts ====================

    suspend fun getContacts(search: String? = null, limit: Int = 500): ContactsResponse =
        withContext(Dispatchers.IO) {
            val params = mutableListOf("limit=$limit")
            search?.let { params.add("search=$it") }
            get("/api/contacts?${params.joinToString("&")}")
        }

    suspend fun syncContacts(contacts: List<Contact>): SyncResponse = withContext(Dispatchers.IO) {
        post("/api/contacts/sync", mapOf("contacts" to contacts))
    }

    suspend fun getContact(id: String): Contact = withContext(Dispatchers.IO) {
        get("/api/contacts/$id")
    }

    // ==================== Call History ====================

    suspend fun getCallHistory(
        limit: Int = 100,
        before: Long? = null,
        after: Long? = null,
        type: String? = null
    ): CallsResponse = withContext(Dispatchers.IO) {
        val params = mutableListOf("limit=$limit")
        before?.let { params.add("before=$it") }
        after?.let { params.add("after=$it") }
        type?.let { params.add("type=$it") }
        get("/api/calls?${params.joinToString("&")}")
    }

    suspend fun syncCallHistory(calls: List<CallHistoryEntry>): SyncResponse =
        withContext(Dispatchers.IO) {
            post("/api/calls/sync", mapOf("calls" to calls))
        }

    suspend fun requestCall(phoneNumber: String, simSubscriptionId: Int? = null): Map<String, String> =
        withContext(Dispatchers.IO) {
            val body = mutableMapOf<String, Any>("phoneNumber" to phoneNumber)
            simSubscriptionId?.let { body["simSubscriptionId"] = it }
            post("/api/calls/request", body)
        }

    suspend fun getCallRequests(): List<CallRequest> = withContext(Dispatchers.IO) {
        val response: Map<String, List<CallRequest>> = get("/api/calls/requests")
        response["requests"] ?: emptyList()
    }

    suspend fun updateCallRequestStatus(id: String, status: String): Unit =
        withContext(Dispatchers.IO) {
            put("/api/calls/requests/$id/status", mapOf("status" to status))
        }

    // ==================== Devices ====================

    suspend fun getDevices(): DevicesResponse = withContext(Dispatchers.IO) {
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

    fun connectWebSocket(listener: SyncFlowWebSocketListener) {
        val token = accessToken ?: throw IllegalStateException("Must authenticate first")
        wsListener = listener

        val wsUrl = baseUrl.replace("http", "ws") + ":3001?token=$token"
        val request = Request.Builder().url(wsUrl).build()

        webSocket = httpClient.newWebSocket(request, object : okhttp3.WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                listener.onConnected()
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
                    // Ignore parse errors
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onDisconnected()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onError(t.message ?: "WebSocket error")
            }
        })
    }

    fun disconnectWebSocket() {
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        wsListener = null
    }

    fun subscribe(channel: String) {
        subscriptions.add(channel)
        webSocket?.send(gson.toJson(mapOf("type" to "subscribe", "channel" to channel)))
    }

    fun unsubscribe(channel: String) {
        subscriptions.remove(channel)
        webSocket?.send(gson.toJson(mapOf("type" to "unsubscribe", "channel" to channel)))
    }

    private fun handleWebSocketMessage(type: String, data: Map<*, *>) {
        val listener = wsListener ?: return
        when (type) {
            "message_added" -> {
                val msg = gson.fromJson(gson.toJson(data["data"]), Message::class.java)
                listener.onMessageAdded(msg)
            }
            "message_updated" -> {
                val msg = gson.fromJson(gson.toJson(data["data"]), Message::class.java)
                listener.onMessageUpdated(msg)
            }
            "message_deleted" -> {
                val id = (data["data"] as? Map<*, *>)?.get("id") as? String ?: return
                listener.onMessageDeleted(id)
            }
            "contact_added" -> {
                val contact = gson.fromJson(gson.toJson(data["data"]), Contact::class.java)
                listener.onContactAdded(contact)
            }
            "contact_updated" -> {
                val contact = gson.fromJson(gson.toJson(data["data"]), Contact::class.java)
                listener.onContactUpdated(contact)
            }
            "contact_deleted" -> {
                val id = (data["data"] as? Map<*, *>)?.get("id") as? String ?: return
                listener.onContactDeleted(id)
            }
            "call_added" -> {
                val call = gson.fromJson(gson.toJson(data["data"]), CallHistoryEntry::class.java)
                listener.onCallAdded(call)
            }
            "outgoing_message" -> {
                val msg = gson.fromJson(gson.toJson(data["data"]), OutgoingMessage::class.java)
                listener.onOutgoingMessage(msg)
            }
            "call_request" -> {
                val req = gson.fromJson(gson.toJson(data["data"]), CallRequest::class.java)
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
                throw IOException("Request failed: ${response.code}")
            }
            val body = response.body?.string() ?: "{}"
            return gson.fromJson(body, T::class.java)
        }
    }
}
