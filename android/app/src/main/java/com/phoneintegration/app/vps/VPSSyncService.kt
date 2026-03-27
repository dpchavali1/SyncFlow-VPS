/**
 * VPS Sync Service - Central hub for syncing Android data (messages, contacts, calls) to the
 * VPS server via REST API and receiving real-time events via WebSocket. Handles MMS attachment
 * extraction and upload to Cloudflare R2, E2EE encryption of message bodies, and dispatches
 * incoming commands (outgoing messages, call requests) from paired desktop/web clients.
 */

package com.phoneintegration.app.vps

import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import com.phoneintegration.app.BuildConfig
import com.phoneintegration.app.SmsMessage
import com.phoneintegration.app.e2ee.SignalProtocolManager
import com.phoneintegration.app.data.PreferencesManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

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

    // Local read-thread timestamps from SmsViewModel (for non-default-app mode)
    private val localReadThreads: Map<Long, Long> by lazy {
        val prefs = appContext.getSharedPreferences("read_threads", android.content.Context.MODE_PRIVATE)
        val result = mutableMapOf<Long, Long>()
        for ((key, value) in prefs.all) {
            val threadId = key.toLongOrNull() ?: continue
            val ts = value as? Long ?: continue
            result[threadId] = ts
        }
        result
    }

    // Cache address → threadId lookups to avoid repeated content provider queries
    private val addressThreadIdCache = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // HTTP client for R2 uploads - reuses VPSClient's shared client with extended timeouts
    private val httpClient = vpsClient.fileTransferHttpClient

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
                if (BuildConfig.DEBUG) Log.d(TAG, "WebSocket connected")
            }

            override fun onDisconnected() {
                if (BuildConfig.DEBUG) Log.d(TAG, "WebSocket disconnected")
            }

            override fun onError(error: String) {
                Log.e(TAG, "WebSocket error: $error")
            }

            override fun onMessageAdded(message: VPSMessage) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Message added: ${message.id}")
            }

            override fun onMessageUpdated(message: VPSMessage) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Message updated: ${message.id}")
            }

            override fun onMessageDeleted(messageId: String) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Message deleted: $messageId")
            }

            override fun onContactAdded(contact: VPSContact) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Contact added: ${contact.id}")
            }

            override fun onContactUpdated(contact: VPSContact) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Contact updated: ${contact.id}")
            }

            override fun onContactDeleted(contactId: String) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Contact deleted: $contactId")
            }

            override fun onCallAdded(call: VPSCallHistoryEntry) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Call added: ${call.id}")
            }

            override fun onOutgoingMessage(message: VPSOutgoingMessage) {
                // Outgoing message from desktop - need to send via Android
                if (BuildConfig.DEBUG) Log.d(TAG, "Outgoing message received: ${message.id}")
                scope.launch {
                    _outgoingMessages.emit(message)
                }
            }

            override fun onCallRequest(request: VPSCallRequest) {
                // Call request from desktop
                if (BuildConfig.DEBUG) Log.d(TAG, "Call request received: ${request.id}")
                scope.launch {
                    _callRequests.emit(request)
                }
            }

            override fun onCallCommand(command: VPSCallCommand) {
                // Call command from desktop (answer/reject/end)
                if (BuildConfig.DEBUG) Log.d(TAG, "Call command received: ${command.command}")
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
                    if (BuildConfig.DEBUG) Log.d(TAG, "Another device was removed: $deviceId")
                    // Invalidate the in-memory cache so re-entering DesktopIntegrationScreen
                    // fetches fresh data from server instead of serving stale cached list
                    com.phoneintegration.app.ui.desktop.PairedDevicesCache.clear()
                    scope.launch {
                        _otherDeviceRemoved.emit(deviceId)
                    }
                }
            }

            override fun onDeviceAdded(deviceId: String, deviceName: String?, deviceType: String?) {
                val currentDeviceId = vpsClient.deviceId
                if (deviceId != currentDeviceId) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "New device added: $deviceId (name=$deviceName, type=$deviceType)")
                    scope.launch {
                        _deviceAdded.emit(deviceId)
                    }
                }
            }

            override fun onE2eeKeyRequest(requestingDeviceId: String, requestingPublicKey: String?) {
                if (BuildConfig.DEBUG) Log.d(TAG, "E2EE key request from device $requestingDeviceId (hasPublicKey=${requestingPublicKey != null})")
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

            override fun onSyncMessagesRequested() {
                Log.i(TAG, "Message re-sync requested by remote device (encryption repair)")
                scope.launch(Dispatchers.IO) {
                    try {
                        resyncAllMessages()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to re-sync messages for encryption repair: ${e.message}", e)
                    }
                }
            }
        })
    }

    // ==================== Authentication ====================

    suspend fun initialize(): Boolean {
        return try {
            val success = vpsClient.initialize()
            if (success && BuildConfig.DEBUG) {
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
            if (BuildConfig.DEBUG) Log.d(TAG, "Synced message: ${message.id}, response: synced=${response.synced}")

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

            // Process in chunks: build maps (incl. MMS upload) and sync each batch together
            // so that if interrupted, completed batches are already persisted on the server
            messages.chunked(50).forEach { batch ->
                val messageMaps = batch.map { buildMessageMap(it, skipAttachments = false) }
                val response = vpsClient.syncMessages(messageMaps)
                if (BuildConfig.DEBUG) Log.d(TAG, "Synced batch: synced=${response.synced}, skipped=${response.skipped}")
            }

            _syncState.value = SyncState.Success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync messages: ${e.message}")
            _syncState.value = SyncState.Error(e.message ?: "Unknown error")
            throw e
        }
    }

    /**
     * Batch sync with skip-attachments support and parallel MMS upload.
     * Used by bulk history sync to avoid one-at-a-time API calls.
     */
    suspend fun syncMessagesBatch(messages: List<SmsMessage>, skipAttachments: Boolean) {
        try {
            // Build message maps with limited parallelism for MMS uploads
            val messageMaps = if (skipAttachments) {
                messages.map { buildMessageMap(it, skipAttachments = true) }
            } else {
                // Parallelize MMS attachment uploads (up to 4 concurrent)
                val semaphore = kotlinx.coroutines.sync.Semaphore(4)
                coroutineScope {
                    messages.map { msg ->
                        async(Dispatchers.IO) {
                            semaphore.acquire()
                            try {
                                buildMessageMap(msg, skipAttachments = false)
                            } finally {
                                semaphore.release()
                            }
                        }
                    }.awaitAll()
                }
            }

            val response = vpsClient.syncMessages(messageMaps)
            Log.d(TAG, "Synced batch of ${messages.size}: synced=${response.synced}, skipped=${response.skipped}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync batch of ${messages.size}: ${e.message}")
            throw e
        }
    }

    /**
     * Re-sync messages from the device content provider, limited to the number
     * already stored on the VPS. Used by the "Repair Encryption" flow to
     * re-encrypt existing messages with current keys.
     */
    suspend fun resyncAllMessages() {
        Log.i(TAG, "Starting message re-sync for encryption repair")

        // Only re-sync as many messages as currently exist on the server
        val vpsCount = vpsClient.getMessageCount()
        if (vpsCount == 0) {
            Log.i(TAG, "No messages on VPS, nothing to re-sync")
            return
        }
        Log.i(TAG, "VPS has $vpsCount messages, reading that many from content provider")

        val smsRepository = com.phoneintegration.app.SmsRepository(appContext)
        val messages = smsRepository.getAllMessages(limit = vpsCount)
        Log.i(TAG, "Read ${messages.size} messages from content provider for re-sync")

        if (messages.isEmpty()) return

        _syncState.value = SyncState.Syncing
        try {
            messages.chunked(100).forEach { batch ->
                syncMessagesBatch(batch, skipAttachments = false)
            }
            _syncState.value = SyncState.Success
            Log.i(TAG, "Message re-sync completed: ${messages.size} messages")
        } catch (e: Exception) {
            Log.e(TAG, "Message re-sync failed: ${e.message}", e)
            _syncState.value = SyncState.Error(e.message ?: "Re-sync failed")
            throw e
        }
    }

    private suspend fun buildMessageMap(message: SmsMessage, skipAttachments: Boolean): Map<String, Any?> {
        val isE2eeEnabled = preferencesManager.e2eeEnabled.value

        var body = message.body
        var encryptedBody: String? = null
        var encryptedNonce: String? = null
        var keyMap: Map<String, String>? = null

        // E2EE encryption: generate per-message data key, encrypt body with AES-GCM,
        // then wrap the data key with the sync group's ECDH public key so all paired devices can decrypt.
        if (isE2eeEnabled && message.body.isNotEmpty()) {
            try {
                // Ensure ECDH keys are initialized (idempotent - returns immediately if already set up)
                e2eeManager.initializeKeys()

                // Check if key rotation is due (> 30 days)
                e2eeManager.checkAndRotateKeys()

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
                            keyMap = mapOf(
                                "syncGroup" to encryptedDataKey,
                                "keyVersion" to e2eeManager.getCurrentKeyVersion().toString()
                            )
                            // Clear plaintext body only when fully encrypted with keyMap
                            body = ""
                            if (BuildConfig.DEBUG) Log.d(TAG, "Message encrypted with E2EE")
                        } else {
                            Log.w(TAG, "E2EE: encryptDataKeyForDevice failed, sending unencrypted")
                        }
                    }
                } else {
                    Log.w(TAG, "E2EE keys unavailable - message will be sent unencrypted")
                }
            } catch (e: Exception) {
                Log.e(TAG, "E2EE encryption failed, sending unencrypted", e)
            }
        }

        // Upload MMS media parts (images/video/audio) to R2 via presigned URLs.
        // Only send mmsParts when we have actual data to prevent overwriting existing
        // DB entries via ON CONFLICT (COALESCE on server preserves existing if null).
        var mmsParts: List<Map<String, Any>>? = null
        if (message.isMms && !skipAttachments) {
            try {
                val extracted = extractAndUploadMmsAttachments(message.id)
                if (extracted.isNotEmpty()) {
                    mmsParts = extracted
                    if (BuildConfig.DEBUG) Log.d(TAG, "MMS ${message.id}: ${extracted.size} attachments uploaded to R2")
                } else {
                    if (BuildConfig.DEBUG) Log.d(TAG, "MMS ${message.id}: no media parts extracted, preserving existing DB data")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to upload MMS attachments for ${message.id}", e)
                // mmsParts stays null → server COALESCE preserves existing DB data
            }
        }

        // Prefix MMS IDs with "mms_" to avoid collision with SMS IDs
        // (SMS and MMS use separate ContentProviders with independent ID spaces)
        val messageId = if (message.isMms) "mms_${message.id}" else message.id.toString()

        // Cross-reference local read status: when not the default SMS app,
        // the content provider may still show messages as unread even though the
        // user has already viewed them in SyncFlow. Check our local overrides.
        var isRead = message.isRead
        if (!isRead && localReadThreads.isNotEmpty()) {
            val threadId = addressThreadIdCache[message.address] ?: try {
                Telephony.Threads.getOrCreateThreadId(appContext, setOf(message.address))?.also {
                    addressThreadIdCache[message.address] = it
                }
            } catch (e: Exception) {
                null
            }
            if (threadId != null) {
                val readAt = localReadThreads[threadId]
                if (readAt != null && readAt >= message.date) {
                    isRead = true
                }
            }
        }

        return mapOf(
            "id" to messageId,
            "threadId" to 0, // Not directly available on SmsMessage, will be resolved on server
            "address" to message.address,
            "contactName" to message.contactName,
            "body" to body,
            "date" to message.date,
            "type" to message.type,
            "read" to isRead,
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
     * Extract non-text MMS parts (images, audio, video) from the MMS content provider and upload
     * them to Cloudflare R2 via presigned URLs. Uses deterministic filenames so re-syncs overwrite
     * rather than duplicate. Returns a list of attachment metadata with R2 keys for mmsParts.
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
        )

        if (cursor == null) {
            Log.w(TAG, "MMS $mmsId: cursor null for parts query")
            return emptyList()
        }

        if (BuildConfig.DEBUG) Log.d(TAG, "MMS $mmsId: found ${cursor.count} parts")

        cursor.use {
            while (it.moveToNext()) {
                val partId = it.getLong(0)
                val contentType = it.getString(1) ?: continue
                val name = it.getString(2)

                // Skip text, SMIL, and RCS bot message parts - only process media attachments
                if (contentType.startsWith("text/") ||
                    contentType == "application/smil" ||
                    contentType.startsWith("application/vnd.gsma.")) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "MMS $mmsId part $partId: skipping $contentType")
                    continue
                }

                // Read part bytes
                val partUri = Uri.parse("content://mms/part/$partId")
                val bytes = try {
                    resolver.openInputStream(partUri)?.use { stream -> stream.readBytes() }
                } catch (e: Exception) {
                    Log.e(TAG, "MMS $mmsId part $partId: failed to read bytes", e)
                    null
                }

                if (bytes == null) {
                    Log.w(TAG, "MMS $mmsId part $partId: openInputStream returned null ($contentType)")
                    continue
                }

                // Skip empty parts
                if (bytes.isEmpty()) {
                    Log.w(TAG, "MMS $mmsId part $partId: empty bytes ($contentType)")
                    continue
                }

                if (BuildConfig.DEBUG) Log.d(TAG, "MMS $mmsId part $partId: ${bytes.size} bytes, type=$contentType, name=$name")

                // Upload to R2
                try {
                    val ext = contentType.substringAfter("/").substringBefore(";")
                    // Always use deterministic filename so re-uploads overwrite the same R2 object
                    val fileName = "mms_${mmsId}_${partId}.$ext"
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
                    if (BuildConfig.DEBUG) Log.d(TAG, "MMS attachment uploaded: $fileName (${bytes.size} bytes)")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to upload MMS attachment part $partId", e)
                }
            }
        }

        if (BuildConfig.DEBUG) Log.d(TAG, "MMS $mmsId: extracted ${attachments.size} attachments")
        return attachments
    }

    /**
     * Upload raw bytes directly to R2 via presigned PUT URL. Uses a separate OkHttp client
     * with generous timeouts since MMS media can be several MB over mobile data.
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
                if (BuildConfig.DEBUG) Log.d(TAG, "Synced contacts batch: synced=${response.synced}")
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
                if (BuildConfig.DEBUG) Log.d(TAG, "Synced call history batch: synced=${response.synced}")
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
            if (BuildConfig.DEBUG) Log.d(TAG, "Active call synced: $callId - $state")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync active call: ${e.message}")
        }
    }

    suspend fun updateActiveCallState(callId: String, state: String) {
        try {
            vpsClient.updateActiveCallState(callId, state)
            if (BuildConfig.DEBUG) Log.d(TAG, "Active call state updated: $callId - $state")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update active call state: ${e.message}")
        }
    }

    suspend fun clearActiveCall(callId: String) {
        try {
            vpsClient.clearActiveCall(callId)
            if (BuildConfig.DEBUG) Log.d(TAG, "Active call cleared: $callId")
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
            if (BuildConfig.DEBUG) Log.d(TAG, "Device removed successfully: $deviceId")
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
