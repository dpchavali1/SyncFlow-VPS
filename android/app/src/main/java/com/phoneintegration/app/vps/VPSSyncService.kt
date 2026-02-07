/**
 * VPS Sync Service - Replacement for Firebase-based DesktopSyncService
 *
 * This service handles syncing messages, contacts, and calls to the VPS server
 * instead of Firebase. It provides similar functionality to DesktopSyncService
 * but uses REST API and WebSocket instead of Firebase Realtime Database.
 */

package com.phoneintegration.app.vps

import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import com.phoneintegration.app.SmsMessage
import com.phoneintegration.app.e2ee.SignalProtocolManager
import com.phoneintegration.app.data.PreferencesManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class VPSSyncService(context: Context) {

    companion object {
        private const val TAG = "VPSSyncService"
        private const val MMS_UPLOAD_MAX_ATTEMPTS = 3
        private const val MMS_UPLOAD_RETRY_BASE_DELAY_MS = 500L
        private const val MMS_UPLOAD_RETRY_MAX_DELAY_MS = 4000L

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

    // HTTP client for R2 uploads
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // State flows
    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val _outgoingMessages = MutableSharedFlow<VPSOutgoingMessage>()
    val outgoingMessages: SharedFlow<VPSOutgoingMessage> = _outgoingMessages.asSharedFlow()

    private val _callRequests = MutableSharedFlow<VPSCallRequest>()
    val callRequests: SharedFlow<VPSCallRequest> = _callRequests.asSharedFlow()

    private val _callCommands = MutableSharedFlow<VPSCallCommand>()
    val callCommands: SharedFlow<VPSCallCommand> = _callCommands.asSharedFlow()

    private val _deviceUnpaired = MutableSharedFlow<Unit>()
    val deviceUnpaired: SharedFlow<Unit> = _deviceUnpaired.asSharedFlow()

    private val _deviceAdded = MutableSharedFlow<String>()
    val deviceAdded: SharedFlow<String> = _deviceAdded.asSharedFlow()

    private val _otherDeviceRemoved = MutableSharedFlow<String>()
    val otherDeviceRemoved: SharedFlow<String> = _otherDeviceRemoved.asSharedFlow()

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

            override fun onCallCommand(command: VPSCallCommand) {
                // Call command from desktop (answer/reject/end)
                Log.d(TAG, "Call command received: ${command.command}")
                scope.launch {
                    _callCommands.emit(command)
                }
            }

            override fun onDeviceRemoved(deviceId: String) {
                val currentDeviceId = vpsClient.deviceId
                Log.w(TAG, "Device removed: $deviceId (current: $currentDeviceId)")
                if (deviceId == currentDeviceId) {
                    Log.w(TAG, "THIS device was unpaired remotely! Logging out...")
                    com.phoneintegration.app.desktop.DesktopSyncService.updatePairedDevicesCache(appContext, false, 0)
                    scope.launch {
                        _deviceUnpaired.emit(Unit)
                    }
                    vpsClient.logout()
                } else {
                    Log.d(TAG, "Another device was removed: $deviceId")
                    scope.launch {
                        _otherDeviceRemoved.emit(deviceId)
                    }
                }
            }

            override fun onDeviceAdded(deviceId: String, deviceName: String?, deviceType: String?) {
                val currentDeviceId = vpsClient.deviceId
                if (deviceId != currentDeviceId) {
                    Log.d(TAG, "New device added: $deviceId (name=$deviceName, type=$deviceType)")
                    scope.launch {
                        _deviceAdded.emit(deviceId)
                    }
                }
            }

            override fun onE2eeKeyRequest(requestingDeviceId: String, requestingPublicKey: String?) {
                Log.d(TAG, "E2EE key request from device $requestingDeviceId (hasPublicKey=${requestingPublicKey != null})")
                if (requestingPublicKey != null) {
                    scope.launch(Dispatchers.IO) {
                        try {
                            val desktopSyncService = com.phoneintegration.app.desktop.DesktopSyncService(appContext)
                            desktopSyncService.pushE2EEKeysToDevice(
                                targetDeviceId = requestingDeviceId,
                                targetPublicKeyX963 = requestingPublicKey
                            )
                            Log.i(TAG, "Auto-pushed E2EE keys to requesting device $requestingDeviceId")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to auto-push E2EE keys to $requestingDeviceId: ${e.message}", e)
                        }
                    }
                } else {
                    Log.w(TAG, "E2EE key request without public key from $requestingDeviceId - cannot push keys")
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

    /**
     * Check if this device is still registered on the VPS server.
     * Returns false if the device was removed remotely (triggers logout).
     */
    suspend fun validateDeviceRegistration(): Boolean {
        return try {
            val currentDeviceId = vpsClient.deviceId ?: return false
            val response = vpsClient.getDevices()
            val stillRegistered = response.devices.any { it.id == currentDeviceId }
            if (!stillRegistered) {
                Log.w(TAG, "Device $currentDeviceId no longer registered on server - logging out")
                com.phoneintegration.app.desktop.DesktopSyncService.updatePairedDevicesCache(appContext, false, 0)
                _deviceUnpaired.emit(Unit)
                vpsClient.logout()
            }
            stillRegistered
        } catch (e: Exception) {
            Log.e(TAG, "Failed to validate device registration: ${e.message}")
            true // Assume still registered on network error
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

            val messageMaps = messages.map { buildMessageMap(it, skipAttachments = false) }

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
        val isE2eeEnabled = preferencesManager.e2eeEnabled.value

        var body = message.body
        var encryptedBody: String? = null
        var encryptedNonce: String? = null
        var keyMap: Map<String, String>? = null

        // Encrypt if E2EE is enabled and message has content
        if (isE2eeEnabled && message.body.isNotEmpty()) {
            try {
                // Ensure ECDH keys are initialized (idempotent - returns immediately if already set up)
                e2eeManager.initializeKeys()

                // Get sync group public key first - if not available, skip encryption entirely
                val publicKeyX963 = e2eeManager.getSyncGroupPublicKeyX963()
                if (publicKeyX963 != null) {
                    // Generate a random data key for this message
                    val dataKey = ByteArray(32).apply {
                        java.security.SecureRandom().nextBytes(this)
                    }

                    // Encrypt the message body with the data key
                    val encrypted = e2eeManager.encryptMessageBody(dataKey, message.body)
                    if (encrypted != null) {
                        val (ciphertext, nonce) = encrypted
                        val encryptedDataKey = e2eeManager.encryptDataKeyForDevice(publicKeyX963, dataKey)
                        if (encryptedDataKey != null) {
                            encryptedBody = android.util.Base64.encodeToString(ciphertext, android.util.Base64.NO_WRAP)
                            encryptedNonce = android.util.Base64.encodeToString(nonce, android.util.Base64.NO_WRAP)
                            keyMap = mapOf("syncGroup" to encryptedDataKey)
                            // Clear plaintext body only when fully encrypted with keyMap
                            body = ""
                            Log.d(TAG, "Message encrypted with E2EE")
                        } else {
                            Log.w(TAG, "E2EE: encryptDataKeyForDevice failed, sending unencrypted")
                        }
                    }
                } else {
                    Log.w(TAG, "E2EE: sync group keys not available, sending unencrypted")
                }
            } catch (e: Exception) {
                Log.e(TAG, "E2EE encryption failed, sending unencrypted", e)
            }
        }

        // Handle MMS attachments - upload to R2
        var mmsParts: List<Map<String, Any>>? = null
        if (message.isMms && !skipAttachments) {
            try {
                mmsParts = extractAndUploadMmsAttachments(message.id)
                if (!mmsParts.isNullOrEmpty()) {
                    Log.d(TAG, "MMS ${message.id}: ${mmsParts.size} attachments uploaded to R2")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to upload MMS attachments for ${message.id}", e)
            }
        }

        // Prefix MMS IDs with "mms_" to avoid collision with SMS IDs
        // (SMS and MMS use separate ContentProviders with independent ID spaces)
        val messageId = if (message.isMms) "mms_${message.id}" else message.id.toString()

        return mapOf(
            "id" to messageId,
            "threadId" to 0, // Not directly available on SmsMessage, will be resolved on server
            "address" to message.address,
            "body" to body,
            "date" to message.date,
            "type" to message.type,
            "read" to message.isRead,
            "isMms" to message.isMms,
            "mmsParts" to mmsParts,
            "encrypted" to (encryptedBody != null),
            "encryptedBody" to encryptedBody,
            "encryptedNonce" to encryptedNonce,
            "keyMap" to keyMap,
            "simSubscriptionId" to message.subId
        )
    }

    /**
     * Extract non-text MMS parts (images, audio, video) and upload them to R2.
     * Returns a list of attachment metadata with R2 keys for storage in mmsParts.
     */
    private suspend fun extractAndUploadMmsAttachments(mmsId: Long): List<Map<String, Any>> {
        val attachments = mutableListOf<Map<String, Any>>()
        val resolver = appContext.contentResolver
        val uri = Uri.parse("content://mms/part")

        val cursor = resolver.query(
            uri,
            arrayOf(
                Telephony.Mms.Part._ID,
                Telephony.Mms.Part.CONTENT_TYPE,
                Telephony.Mms.Part.NAME,
                Telephony.Mms.Part._DATA
            ),
            "${Telephony.Mms.Part.MSG_ID} = ?",
            arrayOf(mmsId.toString()),
            null
        ) ?: return emptyList()

        cursor.use {
            while (it.moveToNext()) {
                val partId = it.getLong(0)
                val contentType = it.getString(1) ?: continue
                val name = it.getString(2)

                // Skip text and SMIL parts - only process media attachments
                if (contentType.startsWith("text/") || contentType == "application/smil") continue

                // Read part bytes
                val partUri = Uri.parse("content://mms/part/$partId")
                val bytes = try {
                    resolver.openInputStream(partUri)?.use { stream -> stream.readBytes() }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to read MMS part $partId", e)
                    null
                } ?: continue

                // Skip empty parts
                if (bytes.isEmpty()) continue

                // Upload to R2
                try {
                    val ext = contentType.substringAfter("/").substringBefore(";")
                    val fileName = name ?: "mms_${mmsId}_${partId}.$ext"
                    val uploadResponse = retryWithBackoff("MMS part $partId upload") {
                        val response = vpsClient.getUploadUrl(
                            fileName = fileName,
                            contentType = contentType,
                            fileSize = bytes.size.toLong(),
                            transferType = "mms"
                        )

                        val success = uploadToR2(response.uploadUrl, bytes, contentType)
                        if (!success) {
                            throw IllegalStateException("R2 upload failed for MMS part $partId")
                        }

                        response
                    }

                    vpsClient.confirmUpload(
                        fileKey = uploadResponse.fileKey,
                        fileSize = bytes.size.toLong(),
                        transferType = "mms"
                    )

                    attachments.add(mapOf(
                        "r2Key" to uploadResponse.fileKey,
                        "fileName" to fileName,
                        "contentType" to contentType,
                        "fileSize" to bytes.size
                    ))
                    Log.d(TAG, "MMS attachment uploaded: $fileName (${bytes.size} bytes)")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to upload MMS attachment part $partId", e)
                }
            }
        }

        return attachments
    }

    /**
     * Upload raw bytes directly to R2 via presigned URL.
     */
    private fun uploadToR2(uploadUrl: String, data: ByteArray, contentType: String): Boolean {
        return try {
            val requestBody = data.toRequestBody(contentType.toMediaType())
            val request = Request.Builder()
                .url(uploadUrl)
                .put(requestBody)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val responseBody = response.body?.string()
                    Log.e(TAG, "R2 upload failed: HTTP ${response.code} ${response.message} - ${responseBody?.take(512)}")
                }
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading to R2", e)
            false
        }
    }

    private suspend fun <T> retryWithBackoff(
        operation: String,
        maxAttempts: Int = MMS_UPLOAD_MAX_ATTEMPTS,
        initialDelayMs: Long = MMS_UPLOAD_RETRY_BASE_DELAY_MS,
        maxDelayMs: Long = MMS_UPLOAD_RETRY_MAX_DELAY_MS,
        block: suspend () -> T
    ): T {
        var attempt = 1
        var delayMs = initialDelayMs
        var lastError: Exception? = null

        while (attempt <= maxAttempts) {
            try {
                return block()
            } catch (e: Exception) {
                lastError = e
                if (attempt >= maxAttempts) break
                Log.w(TAG, "$operation failed (attempt $attempt/$maxAttempts): ${e.message}. Retrying in ${delayMs}ms")
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(maxDelayMs)
                attempt++
            }
        }

        throw lastError ?: IllegalStateException("$operation failed after $maxAttempts attempts")
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

    // ==================== Active Calls ====================

    suspend fun syncActiveCall(
        callId: String,
        phoneNumber: String,
        contactName: String?,
        state: String,
        callType: String = "incoming"
    ) {
        try {
            val activeCall = VPSActiveCall(
                id = callId,
                phoneNumber = phoneNumber,
                contactName = contactName,
                state = state,
                callType = callType,
                timestamp = System.currentTimeMillis()
            )
            vpsClient.syncActiveCall(activeCall)
            Log.d(TAG, "Active call synced: $callId - $state")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync active call: ${e.message}")
        }
    }

    suspend fun updateActiveCallState(callId: String, state: String) {
        try {
            vpsClient.updateActiveCallState(callId, state)
            Log.d(TAG, "Active call state updated: $callId - $state")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update active call state: ${e.message}")
        }
    }

    suspend fun clearActiveCall(callId: String) {
        try {
            vpsClient.clearActiveCall(callId)
            Log.d(TAG, "Active call cleared: $callId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear active call: ${e.message}")
        }
    }

    suspend fun getCallCommands(): List<VPSCallCommand> {
        return try {
            vpsClient.getCallCommands()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get call commands: ${e.message}")
            emptyList()
        }
    }

    suspend fun markCallCommandProcessed(commandId: String) {
        try {
            vpsClient.markCallCommandProcessed(commandId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mark call command processed: ${e.message}")
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
            Log.d(TAG, "Device removed successfully: $deviceId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove device: ${e.message}", e)
            throw e  // Propagate so caller can show error to user
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
