package com.phoneintegration.app.spam

import android.content.Context
import android.util.Log
import androidx.work.*
import com.phoneintegration.app.SmsRepository
import com.phoneintegration.app.data.PreferencesManager
import com.phoneintegration.app.data.database.SyncFlowDatabase
import com.phoneintegration.app.data.database.SpamMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Background worker for automatic spam filter management:
 * - Daily filter updates (WiFi only)
 * - First-run message scan
 * - Periodic maintenance
 */
class SpamFilterWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "SpamFilterWorker"

        // Work names
        const val WORK_FILTER_UPDATE = "spam_filter_update"
        const val WORK_FIRST_RUN_SCAN = "spam_first_run_scan"

        // Input data keys
        const val KEY_WORK_TYPE = "work_type"
        const val TYPE_UPDATE_FILTERS = "update_filters"
        const val TYPE_SCAN_MESSAGES = "scan_messages"

        // Preferences keys
        private const val PREF_FIRST_SCAN_DONE = "spam_first_scan_done"
        private const val PREF_LAST_FILTER_UPDATE = "spam_last_filter_update"

        /**
         * Schedule daily filter updates (WiFi only, battery-efficient)
         */
        fun scheduleDailyUpdates(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED) // WiFi only
                .setRequiresBatteryNotLow(true)
                .build()

            val updateRequest = PeriodicWorkRequestBuilder<SpamFilterWorker>(
                1, TimeUnit.DAYS,
                6, TimeUnit.HOURS // Flex interval
            )
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_WORK_TYPE to TYPE_UPDATE_FILTERS))
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_FILTER_UPDATE,
                ExistingPeriodicWorkPolicy.KEEP,
                updateRequest
            )
        }

        /**
         * Schedule first-run scan (runs once, no network required for scanning)
         */
        fun scheduleFirstRunScan(context: Context) {
            val prefs = context.getSharedPreferences("spam_filter", Context.MODE_PRIVATE)

            // Only schedule if not done before
            if (prefs.getBoolean(PREF_FIRST_SCAN_DONE, false)) {
                Log.i(TAG, "First scan already completed, skipping")
                return
            }

            // No constraints - scan can run offline using existing filters
            val scanRequest = OneTimeWorkRequestBuilder<SpamFilterWorker>()
                .setInputData(workDataOf(KEY_WORK_TYPE to TYPE_SCAN_MESSAGES))
                .setInitialDelay(2, TimeUnit.MINUTES) // Delay to let app fully initialize and permissions settle
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_FIRST_RUN_SCAN,
                ExistingWorkPolicy.KEEP,
                scanRequest
            )
            Log.i(TAG, "First-run scan scheduled to run in 2 minutes")
        }

        /**
         * Trigger immediate filter update (user-initiated)
         */
        fun updateNow(context: Context) {
            val updateRequest = OneTimeWorkRequestBuilder<SpamFilterWorker>()
                .setInputData(workDataOf(KEY_WORK_TYPE to TYPE_UPDATE_FILTERS))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            WorkManager.getInstance(context).enqueue(updateRequest)
        }

        /**
         * Trigger immediate scan (user-initiated)
         */
        fun scanNow(context: Context) {
            val scanRequest = OneTimeWorkRequestBuilder<SpamFilterWorker>()
                .setInputData(workDataOf(KEY_WORK_TYPE to TYPE_SCAN_MESSAGES))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            WorkManager.getInstance(context).enqueue(scanRequest)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val workType = inputData.getString(KEY_WORK_TYPE) ?: TYPE_UPDATE_FILTERS

        try {
            Log.i(TAG, "Starting work: $workType (attempt ${runAttemptCount + 1})")
            when (workType) {
                TYPE_UPDATE_FILTERS -> {
                    updateFilters()
                    Log.i(TAG, "Filter update completed successfully")
                }
                TYPE_SCAN_MESSAGES -> {
                    scanExistingMessages()
                    Log.i(TAG, "Message scan completed successfully")
                }
            }
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Work failed: ${e.message}", e)
            if (runAttemptCount < 3) {
                Log.w(TAG, "Will retry (attempt ${runAttemptCount + 1}/3)")
                Result.retry()
            } else {
                Log.e(TAG, "Max retries reached, marking as failed")
                Result.failure()
            }
        }
    }

    /**
     * Update spam filters from remote sources
     */
    private suspend fun updateFilters() {
        val spamService = SpamFilterService.getInstance(applicationContext)
        val success = spamService.updateFilters { _, _ -> }

        if (success) {
            // Save last update time
            applicationContext.getSharedPreferences("spam_filter", Context.MODE_PRIVATE)
                .edit()
                .putLong(PREF_LAST_FILTER_UPDATE, System.currentTimeMillis())
                .apply()
        }
    }

    /**
     * Scan existing messages for spam
     */
    private suspend fun scanExistingMessages() {
        Log.i(TAG, "Starting spam scan...")
        val prefsManager = PreferencesManager(applicationContext)

        // Check if spam filter is enabled
        if (!prefsManager.spamFilterEnabled.value) {
            Log.i(TAG, "Spam filter disabled, skipping scan")
            markFirstScanDone()
            return
        }

        // Show "scanning started" notification so user knows what's happening
        showScanStartedNotification()
        Log.i(TAG, "Scan notification shown")

        // Try to update filters first (but don't fail if no network)
        try {
            updateFilters()
            Log.i(TAG, "Filters updated successfully")
        } catch (e: Exception) {
            Log.w(TAG, "Could not update filters (no network?): ${e.message}")
        }

        val smsRepository = SmsRepository(applicationContext)
        val spamService = SpamFilterService.getInstance(applicationContext)
        val database = SyncFlowDatabase.getInstance(applicationContext)
        val spamDao = database.spamMessageDao()

        var spamFound = 0
        val spamThreshold = prefsManager.getSpamThreshold()
        val currentTime = System.currentTimeMillis()
        val scannedThreads = mutableSetOf<Long>()

        // STEP 1: Scan UNREAD conversations first (these are likely ignored spam)
        // Uses thread-level read status which works on Samsung
        Log.i(TAG, "Fetching unread conversations...")
        val unreadConversations = smsRepository.getUnreadConversations(limit = 100)
        Log.i(TAG, "Found ${unreadConversations.size} unread conversations")

        for (conversation in unreadConversations) {
            try {
                scannedThreads.add(conversation.threadId)

                val messageAgeHours = (currentTime - conversation.timestamp) / (1000 * 60 * 60)
                val isFromContact = conversation.contactName != null

                // Scan the conversation's last message
                val result = spamService.checkMessage(
                    address = conversation.address,
                    body = conversation.lastMessage,
                    isRead = false,  // Unread conversation
                    messageAgeHours = messageAgeHours,
                    isFromContact = isFromContact
                )

                if (result.isSpam && result.confidence >= spamThreshold) {
                    // Check if already marked as spam (use negative threadId as pseudo message ID for conversations)
                    val pseudoId = -conversation.threadId
                    val existing = spamDao.getByMessageId(pseudoId)
                    if (existing == null) {
                        val spamMessage = SpamMessage(
                            messageId = pseudoId,
                            address = conversation.address,
                            body = conversation.lastMessage,
                            date = conversation.timestamp,
                            contactName = conversation.contactName,
                            spamConfidence = result.confidence,
                            spamReasons = result.reasons.joinToString(", ") { it.description },
                            isUserMarked = false
                        )
                        spamDao.insert(spamMessage)
                        spamFound++
                        Log.d(TAG, "Spam found from ${conversation.address}: confidence=${result.confidence}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error scanning conversation from ${conversation.address}: ${e.message}", e)
            }
        }

        // STEP 2: Also scan recent messages (for content-based detection)
        Log.i(TAG, "Fetching recent messages...")
        val messages = smsRepository.getAllMessages(limit = 500)
        val receivedMessages = messages.filter { it.type == 1 }
        Log.i(TAG, "Found ${receivedMessages.size} received messages to scan")

        for (message in receivedMessages) {
            try {
                val messageAgeHours = (currentTime - message.date) / (1000 * 60 * 60)
                val isFromContact = message.contactName != null

                val result = spamService.checkMessage(
                    address = message.address,
                    body = message.body,
                    isRead = message.isRead,
                    messageAgeHours = messageAgeHours,
                    isFromContact = isFromContact
                )

                if (result.isSpam && result.confidence >= spamThreshold) {
                    val existing = spamDao.getByMessageId(message.id)
                    if (existing == null) {
                        val spamMessage = SpamMessage(
                            messageId = message.id,
                            address = message.address,
                            body = message.body,
                            date = message.date,
                            contactName = null,
                            spamConfidence = result.confidence,
                            spamReasons = result.reasons.joinToString(", ") { it.description },
                            isUserMarked = false
                        )
                        spamDao.insert(spamMessage)
                        spamFound++
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error scanning message ${message.id}: ${e.message}", e)
            }
        }

        val totalScanned = unreadConversations.size + receivedMessages.size
        Log.i(TAG, "Scan complete: $spamFound spam found out of $totalScanned messages scanned")
        spamService.incrementStats(totalScanned, spamFound)

        dismissScanningNotification()
        showScanCompleteNotification(spamFound, totalScanned)

        markFirstScanDone()
        Log.i(TAG, "First scan marked as complete")
    }

    private fun markFirstScanDone() {
        applicationContext.getSharedPreferences("spam_filter", Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_FIRST_SCAN_DONE, true)
            .apply()
    }

    private fun showScanStartedNotification() {
        try {
            val notificationHelper = com.phoneintegration.app.NotificationHelper(applicationContext)
            notificationHelper.showSpamScanStartedNotification()
        } catch (_: Exception) { }
    }

    private fun dismissScanningNotification() {
        try {
            val notificationHelper = com.phoneintegration.app.NotificationHelper(applicationContext)
            notificationHelper.dismissSpamScanningNotification()
        } catch (_: Exception) { }
    }

    private fun showScanCompleteNotification(spamCount: Int, totalScanned: Int) {
        try {
            val notificationHelper = com.phoneintegration.app.NotificationHelper(applicationContext)
            notificationHelper.showSpamScanNotification(spamCount, totalScanned)
        } catch (_: Exception) { }
    }

    private fun showProtectionActivatedNotification() {
        try {
            val notificationHelper = com.phoneintegration.app.NotificationHelper(applicationContext)
            notificationHelper.showSpamProtectionActivatedNotification()
        } catch (_: Exception) { }
    }
}
