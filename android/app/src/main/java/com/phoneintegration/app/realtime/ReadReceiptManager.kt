package com.phoneintegration.app.realtime

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

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
 * Manager for read receipts - tracks when messages are read on desktop
 */
class ReadReceiptManager(private val context: Context) {

    companion object {
        private const val TAG = "ReadReceiptManager"
        private const val READ_RECEIPTS_PATH = "read_receipts"
        private const val USERS_PATH = "users"
    }

    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance()

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
            val userId = auth.currentUser?.uid ?: return
            val receiptRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child(READ_RECEIPTS_PATH)
                .child(payload.messageKey)

            val receiptData = mapOf(
                "messageId" to payload.messageKey,
                "sourceId" to payload.sourceId,
                "sourceType" to payload.sourceType,
                "readAt" to ServerValue.TIMESTAMP,
                "readBy" to "android",
                "readDeviceName" to getDeviceName(),
                "conversationAddress" to conversationAddress
            )

            receiptRef.setValue(receiptData).await()
            Log.d(TAG, "Marked message ${payload.messageKey} as read")
        } catch (e: Exception) {
            Log.e(TAG, "Error marking message as read", e)
        }
    }

    /**
     * Mark multiple messages as read
     */
    suspend fun markMultipleAsRead(payloads: List<ReadReceiptPayload>, conversationAddress: String) {
        try {
            val userId = auth.currentUser?.uid ?: return
            val updates = mutableMapOf<String, Any>()
            val deviceName = getDeviceName()

            for (payload in payloads) {
                updates["$USERS_PATH/$userId/$READ_RECEIPTS_PATH/${payload.messageKey}"] = mapOf(
                    "messageId" to payload.messageKey,
                    "sourceId" to payload.sourceId,
                    "sourceType" to payload.sourceType,
                    "readAt" to ServerValue.TIMESTAMP,
                    "readBy" to "android",
                    "readDeviceName" to deviceName,
                    "conversationAddress" to conversationAddress
                )
            }

            database.reference.updateChildren(updates).await()
            Log.d(TAG, "Marked ${payloads.size} messages as read")
        } catch (e: Exception) {
            Log.e(TAG, "Error marking messages as read", e)
        }
    }

    /**
     * Listen for read receipts (when desktop reads messages)
     */
    fun observeReadReceipts(): Flow<Map<String, ReadReceipt>> = callbackFlow {
        val userId = auth.currentUser?.uid
        if (userId == null) {
            trySend(emptyMap())
            close()
            return@callbackFlow
        }

        val receiptsRef = database.reference
            .child(USERS_PATH)
            .child(userId)
            .child(READ_RECEIPTS_PATH)

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val receipts = mutableMapOf<String, ReadReceipt>()

                snapshot.children.forEach { child ->
                    try {
                        val messageKey = child.child("messageId").getValue(String::class.java)
                            ?: child.child("messageId").getValue(Long::class.java)?.toString()
                            ?: child.key
                            ?: return@forEach
                        val readAt = child.child("readAt").getValue(Long::class.java)
                            ?: child.child("readAt").getValue(Double::class.java)?.toLong()
                            ?: 0L
                        val readBy = child.child("readBy").getValue(String::class.java) ?: "unknown"
                        val readDeviceName = child.child("readDeviceName").getValue(String::class.java)
                        val address = child.child("conversationAddress").getValue(String::class.java) ?: ""
                        val sourceId = child.child("sourceId").getValue(Long::class.java)
                        val sourceType = child.child("sourceType").getValue(String::class.java)

                        receipts[messageKey] = ReadReceipt(
                            messageKey = messageKey,
                            readAt = readAt,
                            readBy = readBy,
                            conversationAddress = address,
                            readDeviceName = readDeviceName,
                            sourceId = sourceId,
                            sourceType = sourceType
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing read receipt", e)
                    }
                }

                trySend(receipts)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Read receipts listener cancelled", error.toException())
            }
        }

        receiptsRef.addValueEventListener(listener)

        awaitClose {
            receiptsRef.removeEventListener(listener)
        }
    }

    /**
     * Observe read receipts for a specific conversation
     */
    fun observeReadReceiptsForConversation(address: String): Flow<List<ReadReceipt>> = callbackFlow {
        val userId = auth.currentUser?.uid
        if (userId == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val receiptsRef = database.reference
            .child(USERS_PATH)
            .child(userId)
            .child(READ_RECEIPTS_PATH)
            .orderByChild("conversationAddress")
            .equalTo(address)

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val receipts = mutableListOf<ReadReceipt>()

                snapshot.children.forEach { child ->
                    try {
                        val messageKey = child.child("messageId").getValue(String::class.java)
                            ?: child.child("messageId").getValue(Long::class.java)?.toString()
                            ?: child.key
                            ?: return@forEach
                        val readAt = child.child("readAt").getValue(Long::class.java)
                            ?: child.child("readAt").getValue(Double::class.java)?.toLong()
                            ?: 0L
                        val readBy = child.child("readBy").getValue(String::class.java) ?: "unknown"
                        val readDeviceName = child.child("readDeviceName").getValue(String::class.java)
                        val sourceId = child.child("sourceId").getValue(Long::class.java)
                        val sourceType = child.child("sourceType").getValue(String::class.java)

                        receipts.add(ReadReceipt(
                            messageKey = messageKey,
                            readAt = readAt,
                            readBy = readBy,
                            conversationAddress = address,
                            readDeviceName = readDeviceName,
                            sourceId = sourceId,
                            sourceType = sourceType
                        ))
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing read receipt", e)
                    }
                }

                trySend(receipts)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Read receipts listener cancelled", error.toException())
            }
        }

        receiptsRef.addValueEventListener(listener)

        awaitClose {
            receiptsRef.removeEventListener(listener)
        }
    }

    /**
     * Check if a message has been read
     */
    suspend fun isMessageRead(messageKey: String): Boolean {
        return try {
            val userId = auth.currentUser?.uid ?: return false
            val snapshot = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child(READ_RECEIPTS_PATH)
                .child(messageKey)
                .get()
                .await()

            snapshot.exists()
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
            val userId = auth.currentUser?.uid ?: return null
            val snapshot = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child(READ_RECEIPTS_PATH)
                .child(messageKey)
                .get()
                .await()

            if (!snapshot.exists()) return null

            ReadReceipt(
                messageKey = messageKey,
                readAt = snapshot.child("readAt").getValue(Long::class.java)
                    ?: snapshot.child("readAt").getValue(Double::class.java)?.toLong()
                    ?: 0L,
                readBy = snapshot.child("readBy").getValue(String::class.java) ?: "unknown",
                conversationAddress = snapshot.child("conversationAddress").getValue(String::class.java) ?: "",
                readDeviceName = snapshot.child("readDeviceName").getValue(String::class.java),
                sourceId = snapshot.child("sourceId").getValue(Long::class.java),
                sourceType = snapshot.child("sourceType").getValue(String::class.java)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting read receipt", e)
            null
        }
    }

    /**
     * Clear read receipts older than 30 days
     */
    suspend fun cleanupOldReceipts() {
        try {
            val userId = auth.currentUser?.uid ?: return
            val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)

            val snapshot = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child(READ_RECEIPTS_PATH)
                .orderByChild("readAt")
                .endAt(thirtyDaysAgo.toDouble())
                .get()
                .await()

            snapshot.children.forEach { child ->
                child.ref.removeValue()
            }

            Log.d(TAG, "Cleaned up ${snapshot.childrenCount} old read receipts")
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up read receipts", e)
        }
    }
}
