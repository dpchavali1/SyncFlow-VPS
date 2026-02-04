package com.phoneintegration.app.workers

import android.content.Context
import android.telephony.SmsManager
import android.util.Log
import androidx.work.*
import com.phoneintegration.app.data.database.ScheduledMessage
import com.phoneintegration.app.data.database.ScheduledStatus
import com.phoneintegration.app.data.database.SyncFlowDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Worker for sending scheduled messages
 */
class ScheduledMessageWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ScheduledMessageWorker"
        const val WORK_NAME = "scheduled_message_check"
        const val KEY_MESSAGE_ID = "message_id"

        /**
         * Schedule periodic check for due messages
         */
        fun schedulePeriodicCheck(context: Context) {
            val request = PeriodicWorkRequestBuilder<ScheduledMessageWorker>(
                15, TimeUnit.MINUTES // Check every 15 minutes
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )

            Log.d(TAG, "Scheduled periodic message check")
        }

        /**
         * Schedule a specific message
         */
        fun scheduleMessage(context: Context, messageId: Long, delayMs: Long) {
            val request = OneTimeWorkRequestBuilder<ScheduledMessageWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_MESSAGE_ID to messageId))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .build()
                )
                .addTag("scheduled_message_$messageId")
                .build()

            WorkManager.getInstance(context).enqueue(request)
            Log.d(TAG, "Scheduled message $messageId to send in ${delayMs}ms")
        }

        /**
         * Cancel a scheduled message
         */
        fun cancelMessage(context: Context, messageId: Long) {
            WorkManager.getInstance(context).cancelAllWorkByTag("scheduled_message_$messageId")
            Log.d(TAG, "Cancelled scheduled message $messageId")
        }
    }

    private val database = SyncFlowDatabase.getInstance(applicationContext)
    private val scheduledDao = database.scheduledMessageDao()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "Running scheduled message worker")

        try {
            // Check if this is for a specific message
            val specificMessageId = inputData.getLong(KEY_MESSAGE_ID, -1)

            if (specificMessageId > 0) {
                // Send specific message
                val message = scheduledDao.getById(specificMessageId)
                if (message != null && message.status == ScheduledStatus.PENDING) {
                    sendMessage(message)
                }
            } else {
                // Check for all due messages
                val now = System.currentTimeMillis()
                val dueMessages = scheduledDao.getDueMessages(now, ScheduledStatus.PENDING)

                Log.d(TAG, "Found ${dueMessages.size} due messages")

                for (message in dueMessages) {
                    sendMessage(message)
                }
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error in scheduled message worker", e)
            Result.retry()
        }
    }

    private suspend fun sendMessage(message: ScheduledMessage) {
        try {
            Log.d(TAG, "Sending scheduled message ${message.id} to ${message.address}")

            // Update status to sending
            scheduledDao.updateStatus(message.id, ScheduledStatus.SENDING)

            // Send the SMS
            val smsManager = SmsManager.getDefault()

            // Split long messages
            val parts = smsManager.divideMessage(message.body)

            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(
                    message.address,
                    null,
                    parts,
                    null,
                    null
                )
            } else {
                smsManager.sendTextMessage(
                    message.address,
                    null,
                    message.body,
                    null,
                    null
                )
            }

            // Mark as sent
            scheduledDao.updateStatus(message.id, ScheduledStatus.SENT)
            Log.d(TAG, "Successfully sent scheduled message ${message.id}")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to send scheduled message ${message.id}", e)

            if (message.retryCount < 3) {
                scheduledDao.markFailed(message.id, ScheduledStatus.PENDING, e.message)
                // Re-schedule for retry in 5 minutes
                scheduleMessage(applicationContext, message.id, 5 * 60 * 1000)
            } else {
                scheduledDao.markFailed(message.id, ScheduledStatus.FAILED, e.message)
            }
        }
    }
}
