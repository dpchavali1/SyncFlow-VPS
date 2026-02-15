package com.phoneintegration.app.desktop

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.phoneintegration.app.MainActivity
import com.phoneintegration.app.MmsHelper
import com.phoneintegration.app.PhoneNumberUtils
import com.phoneintegration.app.R
import com.phoneintegration.app.SmsRepository
import com.phoneintegration.app.e2ee.SignalProtocolManager
import com.phoneintegration.app.vps.VPSClient
import com.phoneintegration.app.vps.VPSSyncService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import okhttp3.OkHttpClient
import java.util.Collections
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Foreground service that continuously listens for outgoing messages from desktop
 * and sends them via SMS in real-time
 */
class OutgoingMessageService : Service() {

    companion object {
        private const val TAG = "OutgoingMessageService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "desktop_sync_channel"
        private const val CHANNEL_NAME = "Desktop Sync"

        // Idle timeout - stop service after this period of inactivity
        private const val IDLE_TIMEOUT_MS = 30_000L // 30 seconds

        fun start(context: Context) {
            val intent = Intent(context, OutgoingMessageService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, OutgoingMessageService::class.java)
            context.stopService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var listeningJob: Job? = null
    private var idleTimeoutJob: Job? = null
    private var lastActivityTime = System.currentTimeMillis()
    private lateinit var vpsClient: VPSClient

    // Dedup: prevent duplicate SMS sends when both WebSocket and polling deliver the same message
    private val processedMessageIds: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        vpsClient = VPSClient.getInstance(applicationContext)
        createNotificationChannel()
        startListening()
        startIdleTimeout()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started (on-demand via FCM)")

        // Start foreground service with silent notification
        val notification = createNotification("Processing messages...")
        startForeground(NOTIFICATION_ID, notification)

        // Reset idle timeout on each start command
        resetIdleTimeout()

        return START_NOT_STICKY // Don't restart automatically - rely on FCM
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        idleTimeoutJob?.cancel()
        listeningJob?.cancel()
        serviceScope.cancel()
    }

    private fun startIdleTimeout() {
        idleTimeoutJob?.cancel()
        idleTimeoutJob = serviceScope.launch {
            while (true) {
                delay(10_000L) // Check every 10 seconds
                val idleTime = System.currentTimeMillis() - lastActivityTime
                if (idleTime > IDLE_TIMEOUT_MS) {
                    Log.d(TAG, "Service idle for ${idleTime}ms, stopping self")
                    stopSelf()
                    break
                }
            }
        }
    }

    private fun resetIdleTimeout() {
        lastActivityTime = System.currentTimeMillis()
    }

    private fun markActivity() {
        lastActivityTime = System.currentTimeMillis()
    }

    private fun startListening() {
        listeningJob = serviceScope.launch {
            val syncService = DesktopSyncService(applicationContext)
            val smsRepository = SmsRepository(applicationContext)

            // Source 1: WebSocket — instant delivery when VPSSyncService is connected
            launch {
                try {
                    Log.d(TAG, "Starting WebSocket source for outgoing messages...")
                    VPSSyncService.getInstance(applicationContext).outgoingMessages
                        .collect { message ->
                            val messageData = mapOf<String, Any?>(
                                "_messageId" to message.id,
                                "address" to message.address,
                                "body" to message.body,
                                "isMms" to message.isMms,
                                "attachments" to message.attachments,
                                "simSubscriptionId" to message.simSubscriptionId
                            )
                            processOutgoingMessage(syncService, smsRepository, messageData, "WebSocket")
                        }
                } catch (e: Exception) {
                    Log.e(TAG, "Fatal error in WebSocket outgoing listener", e)
                }
            }

            // Source 2: Polling fallback — catches anything WebSocket missed
            launch {
                try {
                    Log.d(TAG, "Starting polling source for outgoing messages...")
                    syncService.listenForOutgoingMessages()
                        .catch { e -> Log.e(TAG, "Error in polling message flow", e) }
                        .collect { messageData ->
                            processOutgoingMessage(syncService, smsRepository, messageData, "Poll")
                        }
                } catch (e: Exception) {
                    Log.e(TAG, "Fatal error in polling outgoing listener", e)
                }
            }
        }
    }

    /**
     * Process a single outgoing message from either WebSocket or polling source.
     * Deduplicates by message ID to prevent duplicate SMS sends.
     */
    private suspend fun processOutgoingMessage(
        syncService: DesktopSyncService,
        smsRepository: SmsRepository,
        messageData: Map<String, Any?>,
        source: String
    ) {
        try {
            markActivity()

            val messageId = messageData["_messageId"] as? String ?: return
            val address = messageData["address"] as? String ?: return
            val body = messageData["body"] as? String ?: ""
            val isMms = messageData["isMms"] as? Boolean ?: false
            @Suppress("UNCHECKED_CAST")
            val attachments = messageData["attachments"] as? List<Map<String, Any?>>

            // Dedup: skip if already processed by the other source
            if (!processedMessageIds.add(messageId)) {
                Log.d(TAG, "[$source] Skipping duplicate message: $messageId")
                return
            }

            Log.d(TAG, "[$source][AndroidReceive] Received message - id: $messageId, address: $address, isMms: $isMms, body length: ${body.length}, attachments: ${attachments?.size ?: 0}")

            // Update status to 'sending' so web knows we're processing
            try {
                vpsClient.updateOutgoingStatus(messageId, "sending")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update status to sending", e)
            }

            val sendSuccess: Boolean

            if (isMms && !attachments.isNullOrEmpty()) {
                Log.d(TAG, "[AndroidSend] Processing MMS to address: \"$address\" (normalized: \"${PhoneNumberUtils.normalizeForConversation(address)}\"), body length: ${body.length}, attachments: ${attachments.size}")

                for (i in attachments.indices) {
                    val attachment = attachments[i]
                    val filename = attachment["fileName"] as? String ?: "unknown"
                    val contentType = attachment["contentType"] as? String ?: "unknown"
                    val inlineData = attachment["inlineData"] as? String
                    val url = attachment["url"] as? String
                    val dataSize = inlineData?.let { Base64.decode(it, Base64.DEFAULT).size } ?: 0
                    Log.d(TAG, "[AndroidSend] Attachment $i: $filename, $contentType, size: $dataSize, hasUrl: ${!url.isNullOrEmpty()}")
                }

                val sendStartTime = System.currentTimeMillis()
                sendSuccess = sendMmsWithAttachments(address, body, attachments)
                val sendDuration = System.currentTimeMillis() - sendStartTime

                Log.d(TAG, "[AndroidSend] MMS send result: $sendSuccess (took ${sendDuration}ms)")
            } else if (body.isNotEmpty()) {
                // Handle regular SMS — pass outgoingId for delivery tracking
                updateNotification("Sending SMS to $address...")
                sendSuccess = smsRepository.sendSms(address, body, messageId)
            } else {
                Log.w(TAG, "Empty message body and no attachments")
                sendSuccess = false
            }

            if (sendSuccess) {
                Log.d(TAG, "Message sent successfully to $address")

                // Update status to sent via VPS API
                try {
                    vpsClient.updateOutgoingStatus(messageId, "sent")
                    Log.d(TAG, "Outgoing message marked as sent via VPS")
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating outgoing message status", e)
                }

                // Wait for the message to be written to SMS provider
                delay(1000)

                // Get the actual sent message from SMS/MMS provider and sync it
                try {
                    val latestMessage = if (isMms && !attachments.isNullOrEmpty()) {
                        // For MMS, wait a bit longer as MMS takes more time to be recorded
                        delay(1500)
                        smsRepository.getLatestMmsMessage(address)
                    } else {
                        smsRepository.getLatestMessage(address)
                    }

                    if (latestMessage != null) {
                        // For SMS, verify the body matches
                        val bodyMatches = if (latestMessage.isMms) true else latestMessage.body == body
                        if (bodyMatches) {
                            // Sync the real message from provider (has correct ID)
                            syncService.syncMessage(latestMessage)
                            Log.d(TAG, "Sent message synced with provider ID: ${latestMessage.id} (isMms=${latestMessage.isMms})")

                            // Store outgoingId → syncedMessageId mapping for delivery callback
                            storeDeliveryMapping(messageId, latestMessage.id.toString())
                        } else {
                            // Body doesn't match, use fallback
                            if (isMms && !attachments.isNullOrEmpty()) {
                                syncService.writeSentMmsMessage(messageId, address, body, attachments)
                            } else {
                                syncService.writeSentMessage(messageId, address, body)
                            }
                            Log.d(TAG, "Sent message written with desktop ID (body mismatch fallback)")
                        }
                    } else {
                        // Fallback: write with desktop message ID
                        if (isMms && !attachments.isNullOrEmpty()) {
                            syncService.writeSentMmsMessage(messageId, address, body, attachments)
                        } else {
                            syncService.writeSentMessage(messageId, address, body)
                        }
                        Log.d(TAG, "Sent message written with desktop ID (no provider message found)")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error syncing sent message", e)
                    // Fallback: write with desktop message ID
                    try {
                        if (isMms && !attachments.isNullOrEmpty()) {
                            syncService.writeSentMmsMessage(messageId, address, body, attachments)
                        } else {
                            syncService.writeSentMessage(messageId, address, body)
                        }
                    } catch (e2: Exception) {
                        Log.e(TAG, "Error writing fallback sent message", e2)
                    }
                }
            } else {
                Log.e(TAG, "Failed to send message to $address")
                // Update status to 'failed' so web knows
                try {
                    vpsClient.updateOutgoingStatus(messageId, "failed", "Failed to send message")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update status to failed", e)
                }
            }

            updateNotification("Listening for messages from desktop")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message", e)
            // Try to update status to failed
            try {
                val msgId = messageData["_messageId"] as? String
                if (msgId != null) {
                    vpsClient.updateOutgoingStatus(msgId, "failed", e.message ?: "Unknown error")
                }
            } catch (updateError: Exception) {
                Log.e(TAG, "Failed to update status to failed after exception", updateError)
            }
            updateNotification("Error sending: ${e.message}")
            delay(3000)
            updateNotification("Listening for messages from desktop")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_MIN // Silent notification - no sound or vibration
            ).apply {
                description = "Processes messages sent from your computer"
                setShowBadge(false)
                setSound(null, null) // No sound
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(message: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SyncFlow")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN) // Minimize visibility
            .setSilent(true) // No sound
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(message: String) {
        val notification = createNotification(message)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Download attachment from Firebase Storage and send as MMS
     */
    private suspend fun sendMmsWithAttachments(
        address: String,
        body: String,
        attachments: List<Map<String, Any?>>
    ): Boolean {
        return try {
            // Get the first attachment (MMS typically handles one attachment at a time)
            val attachment = attachments.firstOrNull() ?: return false

            val url = attachment["url"] as? String
            val inlineData = attachment["inlineData"] as? String
            val fileName = attachment["fileName"] as? String ?: "attachment"
            val contentType = attachment["contentType"] as? String ?: "application/octet-stream"
            val isEncrypted = attachment["encrypted"] as? Boolean ?: false

            Log.d(TAG, "Processing MMS attachment: $fileName ($contentType)")

            // Get attachment data - either from URL or inline base64
            val attachmentData: ByteArray? = when {
                !inlineData.isNullOrEmpty() -> {
                    Log.d(TAG, "Using inline data for attachment")
                    Base64.decode(inlineData, Base64.DEFAULT)
                }
                !url.isNullOrEmpty() -> {
                    Log.d(TAG, "Downloading attachment from: ${url.take(50)}...")
                    downloadAttachment(url)
                }
                else -> null
            }

            if (attachmentData == null) {
                Log.e(TAG, "Failed to get attachment data")
                return false
            }

            Log.d(TAG, "Attachment data size: ${attachmentData.size} bytes, encrypted: $isEncrypted")

            // Decrypt attachment if encrypted
            val finalAttachmentData = if (isEncrypted) {
                Log.d(TAG, "Decrypting encrypted attachment...")
                try {
                    val e2eeManager = SignalProtocolManager(applicationContext)
                    val decrypted = e2eeManager.decryptBytes(attachmentData)
                    if (decrypted != null) {
                        Log.d(TAG, "Attachment decrypted successfully: ${decrypted.size} bytes")
                        decrypted
                    } else {
                        Log.e(TAG, "Decryption returned null, using original data")
                        attachmentData
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decrypt attachment, using original data", e)
                    attachmentData
                }
            } else {
                attachmentData
            }

            // Save to cache file
            val cacheDir = File(applicationContext.cacheDir, "mms_outgoing")
            cacheDir.mkdirs()
            val cacheFile = File(cacheDir, fileName)
            cacheFile.writeBytes(finalAttachmentData)

            Log.d(TAG, "Saved attachment to: ${cacheFile.absolutePath}")

            // Get content URI via FileProvider
            val contentUri = FileProvider.getUriForFile(
                applicationContext,
                "${applicationContext.packageName}.provider",
                cacheFile
            )

            Log.d(TAG, "Content URI: $contentUri")

            // Send MMS
            Log.d(TAG, "[AndroidSend] Calling MmsHelper.sendMms - address: $address, hasBody: ${!body.isNullOrEmpty()}, contentUri: $contentUri")
            val sendStart = System.currentTimeMillis()
            val success = MmsHelper.sendMms(applicationContext, address, contentUri, body.ifEmpty { null })
            val sendDuration = System.currentTimeMillis() - sendStart
            Log.d(TAG, "[AndroidSend] MmsHelper.sendMms returned: $success (took ${sendDuration}ms)")

            // Clean up cache file
            cacheFile.delete()

            if (success) {
                Log.d(TAG, "MMS sent successfully to $address")
            } else {
                Log.e(TAG, "MMS send failed to $address")
            }

            success
        } catch (e: Exception) {
            Log.e(TAG, "Error sending MMS with attachments", e)
            false
        }
    }

    /**
     * Download attachment from URL (R2 or VPS storage)
     */
    private suspend fun downloadAttachment(url: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url(url)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Error downloading attachment: ${response.code}")
                    return@withContext null
                }

                val bytes = response.body?.bytes()
                val maxSize = 25L * 1024 * 1024 // 25MB max for MMS attachments

                if (bytes != null && bytes.size > maxSize) {
                    Log.e(TAG, "Attachment too large: ${bytes.size} bytes (max: $maxSize)")
                    return@withContext null
                }

                bytes
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading attachment", e)
            null
        }
    }

    /**
     * Store outgoingId → syncedMessageId mapping so delivery callbacks
     * can update the correct synced message in the VPS server.
     */
    private fun storeDeliveryMapping(outgoingId: String, syncedMessageId: String) {
        try {
            val prefs = getSharedPreferences("delivery_mappings", Context.MODE_PRIVATE)
            prefs.edit().putString(outgoingId, syncedMessageId).apply()
            Log.d(TAG, "Stored delivery mapping: $outgoingId -> $syncedMessageId")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to store delivery mapping: ${e.message}")
        }
    }
}
