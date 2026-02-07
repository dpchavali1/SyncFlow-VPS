package com.phoneintegration.app.desktop

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.work.*
import com.phoneintegration.app.vps.VPSClient
import com.phoneintegration.app.vps.VPSScheduledMessage
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

/**
 * Service to handle scheduled messages from macOS.
 * Messages are scheduled on macOS and sent via Android at the specified time.
 *
 * VPS Backend Only - Uses VPS API instead of Firebase.
 */
class ScheduledMessageService(context: Context) {
    private val context: Context = context.applicationContext
    private val vpsClient = VPSClient.getInstance(context)

    private var pollingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val processedMessageIds = mutableSetOf<String>()

    companion object {
        private const val TAG = "ScheduledMsgService"
        private const val POLLING_INTERVAL_MS = 10000L // Poll every 10 seconds
    }

    /**
     * Start listening for scheduled messages
     */
    fun startListening() {
        Log.d(TAG, "Starting scheduled message service")
        startPolling()
    }

    /**
     * Stop listening
     */
    fun stopListening() {
        Log.d(TAG, "Stopping scheduled message service")
        stopPolling()
        scope.cancel()
    }

    /**
     * Start polling for scheduled messages from VPS
     */
    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                try {
                    if (vpsClient.isAuthenticated) {
                        pollScheduledMessages()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error polling scheduled messages", e)
                }
                delay(POLLING_INTERVAL_MS)
            }
        }
        Log.d(TAG, "Scheduled messages polling started")
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
        Log.d(TAG, "Scheduled messages polling stopped")
    }

    /**
     * Poll for scheduled messages from VPS API
     */
    private suspend fun pollScheduledMessages() {
        try {
            val messages = vpsClient.getScheduledMessages()
            for (message in messages) {
                processScheduledMessage(message)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching scheduled messages", e)
        }
    }

    /**
     * BANDWIDTH OPTIMIZED: Start listening using polling
     * Same as startListening for VPS implementation
     */
    fun startListeningOptimized() {
        Log.d(TAG, "Starting scheduled message service (optimized)")
        startPolling()
    }

    /**
     * Process a scheduled message entry
     */
    private fun processScheduledMessage(message: VPSScheduledMessage) {
        try {
            val id = message.id

            // Skip if already processed
            if (processedMessageIds.contains(id)) return

            // Only process pending messages
            if (message.status != "pending") return

            val recipientNumber = message.recipientNumber
            val messageBody = message.message
            val scheduledTime = message.scheduledTime

            val now = System.currentTimeMillis()

            // Mark as processed
            processedMessageIds.add(id)

            // If scheduled time has passed, send immediately
            if (scheduledTime <= now) {
                Log.d(TAG, "Scheduled time passed, sending immediately: $id")
                sendMessage(id, recipientNumber, messageBody, message.simSlot?.toLong())
            } else {
                // Schedule with WorkManager for future delivery
                val delay = scheduledTime - now
                Log.d(TAG, "Scheduling message $id for ${delay}ms from now")
                scheduleMessage(id, recipientNumber, messageBody, delay, message.simSlot?.toLong())
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
     * Update message status via VPS API
     */
    private fun updateMessageStatus(messageId: String, status: String, errorMessage: String? = null) {
        scope.launch {
            try {
                if (!vpsClient.isAuthenticated) return@launch

                vpsClient.updateScheduledMessageStatus(messageId, status, errorMessage)
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
 *
 * VPS Backend Only - Uses VPS API instead of Firebase.
 */
class SendScheduledMessageWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val vpsClient = VPSClient.getInstance(context)

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

            // Update status via VPS API
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
            if (!vpsClient.isAuthenticated) return

            vpsClient.updateScheduledMessageStatus(messageId, status, errorMessage)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating message status in worker", e)
        }
    }
}
