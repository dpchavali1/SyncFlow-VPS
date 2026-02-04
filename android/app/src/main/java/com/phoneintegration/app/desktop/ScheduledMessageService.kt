package com.phoneintegration.app.desktop

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.work.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

/**
 * Service to handle scheduled messages from macOS.
 * Messages are scheduled on macOS and sent via Android at the specified time.
 */
class ScheduledMessageService(context: Context) {
    private val context: Context = context.applicationContext
    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance()

    private var scheduledMessagesListener: ValueEventListener? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "ScheduledMsgService"
        private const val SCHEDULED_MESSAGES_PATH = "scheduled_messages"
        private const val USERS_PATH = "users"
    }

    data class ScheduledMessage(
        val id: String,
        val recipientNumber: String,
        val recipientName: String?,
        val message: String,
        val scheduledTime: Long,  // Unix timestamp in milliseconds
        val createdAt: Long,
        val status: String,       // "pending", "sent", "failed", "cancelled"
        val simSlot: Int?
    )

    /**
     * Start listening for scheduled messages
     */
    fun startListening() {
        Log.d(TAG, "Starting scheduled message service")
        database.goOnline()
        listenForScheduledMessages()
    }

    /**
     * Stop listening
     */
    fun stopListening() {
        Log.d(TAG, "Stopping scheduled message service")
        stopListeningForMessages()
        scope.cancel()
    }

    /**
     * Listen for scheduled messages from Firebase
     */
    private fun listenForScheduledMessages() {
        scope.launch {
            try {
                val currentUser = auth.currentUser ?: return@launch
                val userId = currentUser.uid

                val messagesRef = database.reference
                    .child(USERS_PATH)
                    .child(userId)
                    .child(SCHEDULED_MESSAGES_PATH)

                val listener = object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        snapshot.children.forEach { child ->
                            processScheduledMessage(child)
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {
                        Log.e(TAG, "Scheduled messages listener cancelled: ${error.message}")
                    }
                }

                scheduledMessagesListener = listener
                messagesRef.addValueEventListener(listener)
                Log.d(TAG, "Scheduled messages listener registered")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting scheduled messages listener", e)
            }
        }
    }

    private fun stopListeningForMessages() {
        scheduledMessagesListener?.let { listener ->
            scope.launch {
                try {
                    val userId = auth.currentUser?.uid ?: return@launch
                    database.reference
                        .child(USERS_PATH)
                        .child(userId)
                        .child(SCHEDULED_MESSAGES_PATH)
                        .removeEventListener(listener)
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing scheduled messages listener", e)
                }
            }
        }
        scheduledMessagesListener = null
        // Also stop optimized listener
        stopListeningOptimized()
    }

    // ==========================================
    // BANDWIDTH OPTIMIZED LISTENERS
    // ==========================================

    private var optimizedListener: ChildEventListener? = null

    /**
     * BANDWIDTH OPTIMIZED: Start listening using child events
     * Only downloads new/changed messages instead of full list on every change
     */
    fun startListeningOptimized() {
        Log.d(TAG, "Starting scheduled message service (optimized)")
        database.goOnline()
        listenForScheduledMessagesOptimized()
    }

    private fun listenForScheduledMessagesOptimized() {
        scope.launch {
            try {
                val currentUser = auth.currentUser ?: return@launch
                val userId = currentUser.uid

                val messagesRef = database.reference
                    .child(USERS_PATH)
                    .child(userId)
                    .child(SCHEDULED_MESSAGES_PATH)

                val listener = object : ChildEventListener {
                    override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                        processScheduledMessage(snapshot)
                    }

                    override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                        // Status changes - re-process to check if needs sending
                        processScheduledMessage(snapshot)
                    }

                    override fun onChildRemoved(snapshot: DataSnapshot) {
                        // Message cancelled/removed - cancel any pending work
                        snapshot.key?.let { messageId ->
                            WorkManager.getInstance(context).cancelUniqueWork("scheduled_message_$messageId")
                            Log.d(TAG, "Cancelled work for removed message: $messageId")
                        }
                    }

                    override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {
                        // Not used
                    }

                    override fun onCancelled(error: DatabaseError) {
                        Log.e(TAG, "Scheduled messages optimized listener cancelled: ${error.message}")
                    }
                }

                optimizedListener = listener
                messagesRef.addChildEventListener(listener)
                Log.d(TAG, "Scheduled messages optimized listener registered")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting scheduled messages optimized listener", e)
            }
        }
    }

    private fun stopListeningOptimized() {
        optimizedListener?.let { listener ->
            scope.launch {
                try {
                    val userId = auth.currentUser?.uid ?: return@launch
                    database.reference
                        .child(USERS_PATH)
                        .child(userId)
                        .child(SCHEDULED_MESSAGES_PATH)
                        .removeEventListener(listener)
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing scheduled messages optimized listener", e)
                }
            }
        }
        optimizedListener = null
    }

    /**
     * Process a scheduled message entry
     */
    private fun processScheduledMessage(snapshot: DataSnapshot) {
        try {
            val id = snapshot.key ?: return
            val status = snapshot.child("status").value as? String ?: return

            // Only process pending messages
            if (status != "pending") return

            val recipientNumber = snapshot.child("recipientNumber").value as? String ?: return
            val message = snapshot.child("message").value as? String ?: return
            val scheduledTime = snapshot.child("scheduledTime").value as? Long ?: return

            val now = System.currentTimeMillis()

            // If scheduled time has passed, send immediately
            if (scheduledTime <= now) {
                Log.d(TAG, "Scheduled time passed, sending immediately: $id")
                sendMessage(id, recipientNumber, message, snapshot.child("simSlot").value as? Long)
            } else {
                // Schedule with WorkManager for future delivery
                val delay = scheduledTime - now
                Log.d(TAG, "Scheduling message $id for ${delay}ms from now")
                scheduleMessage(id, recipientNumber, message, delay, snapshot.child("simSlot").value as? Long)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing scheduled message", e)
        }
    }

    /**
     * Schedule a message for future delivery using WorkManager
     */
    private fun scheduleMessage(
        messageId: String,
        recipientNumber: String,
        message: String,
        delayMs: Long,
        simSlot: Long?
    ) {
        val inputData = workDataOf(
            "messageId" to messageId,
            "recipientNumber" to recipientNumber,
            "message" to message,
            "simSlot" to (simSlot?.toInt() ?: -1)
        )

        val sendRequest = OneTimeWorkRequestBuilder<SendScheduledMessageWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(inputData)
            .addTag("scheduled_message_$messageId")
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "scheduled_message_$messageId",
                ExistingWorkPolicy.REPLACE,
                sendRequest
            )

        Log.d(TAG, "Message $messageId scheduled with WorkManager")
    }

    /**
     * Send a message immediately
     */
    private fun sendMessage(messageId: String, recipientNumber: String, message: String, simSlot: Long?) {
        scope.launch {
            try {
                val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }

                // Handle long messages by splitting
                val parts = smsManager.divideMessage(message)
                if (parts.size > 1) {
                    smsManager.sendMultipartTextMessage(recipientNumber, null, parts, null, null)
                } else {
                    smsManager.sendTextMessage(recipientNumber, null, message, null, null)
                }

                Log.d(TAG, "Sent scheduled message to $recipientNumber")
                updateMessageStatus(messageId, "sent")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending scheduled message", e)
                updateMessageStatus(messageId, "failed", e.message)
            }
        }
    }

    /**
     * Update message status in Firebase
     */
    private fun updateMessageStatus(messageId: String, status: String, errorMessage: String? = null) {
        scope.launch {
            try {
                val userId = auth.currentUser?.uid ?: return@launch

                val updates = mutableMapOf<String, Any?>(
                    "status" to status,
                    "updatedAt" to ServerValue.TIMESTAMP
                )
                if (status == "sent") {
                    updates["sentAt"] = ServerValue.TIMESTAMP
                }
                if (errorMessage != null) {
                    updates["errorMessage"] = errorMessage
                }

                database.reference
                    .child(USERS_PATH)
                    .child(userId)
                    .child(SCHEDULED_MESSAGES_PATH)
                    .child(messageId)
                    .updateChildren(updates)
                    .await()

                Log.d(TAG, "Updated message $messageId status to $status")
            } catch (e: Exception) {
                Log.e(TAG, "Error updating message status", e)
            }
        }
    }

    /**
     * Cancel a scheduled message
     */
    fun cancelScheduledMessage(messageId: String) {
        WorkManager.getInstance(context).cancelUniqueWork("scheduled_message_$messageId")
        updateMessageStatus(messageId, "cancelled")
        Log.d(TAG, "Cancelled scheduled message: $messageId")
    }
}

/**
 * WorkManager Worker to send scheduled messages
 */
class SendScheduledMessageWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "SendScheduledMsgWorker"
    }

    override suspend fun doWork(): Result {
        val messageId = inputData.getString("messageId") ?: return Result.failure()
        val recipientNumber = inputData.getString("recipientNumber") ?: return Result.failure()
        val message = inputData.getString("message") ?: return Result.failure()
        val simSlot = inputData.getInt("simSlot", -1)

        Log.d(TAG, "Sending scheduled message: $messageId to $recipientNumber")

        return try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                applicationContext.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            val parts = smsManager.divideMessage(message)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(recipientNumber, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(recipientNumber, null, message, null, null)
            }

            // Update status in Firebase
            updateMessageStatus(messageId, "sent")

            Log.d(TAG, "Scheduled message sent successfully: $messageId")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send scheduled message: $messageId", e)
            updateMessageStatus(messageId, "failed", e.message)
            Result.failure()
        }
    }

    private suspend fun updateMessageStatus(messageId: String, status: String, errorMessage: String? = null) {
        try {
            val auth = FirebaseAuth.getInstance()
            val database = FirebaseDatabase.getInstance()
            val userId = auth.currentUser?.uid ?: return

            val updates = mutableMapOf<String, Any?>(
                "status" to status,
                "updatedAt" to ServerValue.TIMESTAMP
            )
            if (status == "sent") {
                updates["sentAt"] = ServerValue.TIMESTAMP
            }
            if (errorMessage != null) {
                updates["errorMessage"] = errorMessage
            }

            database.reference
                .child("users")
                .child(userId)
                .child("scheduled_messages")
                .child(messageId)
                .updateChildren(updates)
                .await()
        } catch (e: Exception) {
            Log.e(TAG, "Error updating message status in worker", e)
        }
    }
}
