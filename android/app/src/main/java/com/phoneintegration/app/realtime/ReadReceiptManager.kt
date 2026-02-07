package com.phoneintegration.app.realtime

import android.content.Context
import android.util.Log
import com.phoneintegration.app.vps.VPSClient
import com.phoneintegration.app.vps.VPSReadReceipt
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Read status for a message
 */
data class ReadReceipt(
    val messageKey: String,
    val readAt: Long,
    val readBy: String, // "android", "macos", "web"
    val conversationAddress: String,
    val readDeviceName: String? = null,
    val sourceId: Long? = null,
    val sourceType: String? = null
)

data class ReadReceiptPayload(
    val messageKey: String,
    val sourceId: Long? = null,
    val sourceType: String? = null
)

/**
 * Manager for read receipts - VPS Backend Only
 *
 * In VPS mode, read receipts are synced via REST API and WebSocket.
 * Real-time updates come through the WebSocket connection managed by VPSClient.
 */
class ReadReceiptManager(private val context: Context) {

    companion object {
        private const val TAG = "ReadReceiptManager"
    }

    private val vpsClient = VPSClient.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Local cache of read receipts
    private val _readReceipts = MutableStateFlow<Map<String, ReadReceipt>>(emptyMap())
    private val _conversationReceipts = MutableStateFlow<Map<String, List<ReadReceipt>>>(emptyMap())

    private fun getDeviceName(): String {
        val model = android.os.Build.MODEL ?: "Android"
        val manufacturer = android.os.Build.MANUFACTURER ?: ""
        return if (manufacturer.isBlank()) {
            model
        } else {
            "${manufacturer.trim()} ${model.trim()}".trim()
        }
    }

    /**
     * Mark a message as read on this device
     */
    suspend fun markAsRead(payload: ReadReceiptPayload, conversationAddress: String) {
        try {
            val userId = vpsClient.userId ?: return

            val receiptData = mapOf(
                "messageId" to payload.messageKey,
                "sourceId" to payload.sourceId,
                "sourceType" to payload.sourceType,
                "readAt" to System.currentTimeMillis(),
                "readBy" to "android",
                "readDeviceName" to getDeviceName(),
                "conversationAddress" to conversationAddress
            )

            vpsClient.syncReadReceipt(receiptData)
            Log.d(TAG, "Marked message ${payload.messageKey} as read")

            // Update local cache
            val receipt = ReadReceipt(
                messageKey = payload.messageKey,
                readAt = System.currentTimeMillis(),
                readBy = "android",
                conversationAddress = conversationAddress,
                readDeviceName = getDeviceName(),
                sourceId = payload.sourceId,
                sourceType = payload.sourceType
            )
            _readReceipts.value = _readReceipts.value + (payload.messageKey to receipt)
        } catch (e: Exception) {
            Log.e(TAG, "Error marking message as read", e)
        }
    }

    /**
     * Mark multiple messages as read
     */
    suspend fun markMultipleAsRead(payloads: List<ReadReceiptPayload>, conversationAddress: String) {
        try {
            val userId = vpsClient.userId ?: return
            val deviceName = getDeviceName()
            val timestamp = System.currentTimeMillis()

            val receipts = payloads.map { payload ->
                mapOf(
                    "messageId" to payload.messageKey,
                    "sourceId" to payload.sourceId,
                    "sourceType" to payload.sourceType,
                    "readAt" to timestamp,
                    "readBy" to "android",
                    "readDeviceName" to deviceName,
                    "conversationAddress" to conversationAddress
                )
            }

            vpsClient.syncReadReceipts(receipts)
            Log.d(TAG, "Marked ${payloads.size} messages as read")

            // Update local cache
            val newReceipts = payloads.associate { payload ->
                payload.messageKey to ReadReceipt(
                    messageKey = payload.messageKey,
                    readAt = timestamp,
                    readBy = "android",
                    conversationAddress = conversationAddress,
                    readDeviceName = deviceName,
                    sourceId = payload.sourceId,
                    sourceType = payload.sourceType
                )
            }
            _readReceipts.value = _readReceipts.value + newReceipts
        } catch (e: Exception) {
            Log.e(TAG, "Error marking messages as read", e)
        }
    }

    /**
     * Listen for read receipts (when desktop reads messages)
     */
    fun observeReadReceipts(): Flow<Map<String, ReadReceipt>> = _readReceipts.asStateFlow()

    /**
     * Observe read receipts for a specific conversation
     */
    fun observeReadReceiptsForConversation(address: String): Flow<List<ReadReceipt>> {
        // Return a filtered flow based on conversation address
        return MutableStateFlow(
            _readReceipts.value.values.filter { it.conversationAddress == address }
        ).asStateFlow()
    }

    /**
     * Check if a message has been read
     */
    suspend fun isMessageRead(messageKey: String): Boolean {
        return try {
            _readReceipts.value.containsKey(messageKey)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking read status", e)
            false
        }
    }

    /**
     * Get read status for a message
     */
    suspend fun getReadReceipt(messageKey: String): ReadReceipt? {
        return try {
            _readReceipts.value[messageKey]
        } catch (e: Exception) {
            Log.e(TAG, "Error getting read receipt", e)
            null
        }
    }

    /**
     * Load read receipts from server
     */
    suspend fun loadReadReceipts() {
        try {
            val receipts = vpsClient.getReadReceipts()
            _readReceipts.value = receipts
                .map { it.toReadReceipt() }
                .associateBy { it.messageKey }
            Log.d(TAG, "Loaded ${receipts.size} read receipts")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading read receipts", e)
        }
    }

    /**
     * Clear read receipts older than 30 days
     * Note: In VPS mode, this is handled server-side
     */
    suspend fun cleanupOldReceipts() {
        try {
            vpsClient.cleanupOldReadReceipts()
            Log.d(TAG, "Requested cleanup of old read receipts")
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up read receipts", e)
        }
    }

    private fun VPSReadReceipt.toReadReceipt(): ReadReceipt {
        return ReadReceipt(
            messageKey = messageKey,
            readAt = readAt,
            readBy = readBy,
            conversationAddress = conversationAddress,
            readDeviceName = readDeviceName,
            sourceId = sourceId,
            sourceType = sourceType
        )
    }
}
