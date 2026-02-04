package com.phoneintegration.app.desktop

import android.content.Context
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Background worker to sync contacts to Firebase periodically
 */
class ContactsSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ContactsSyncWorker"
        private const val WORK_NAME = "contacts_sync_work"

        /**
         * Schedule periodic contacts sync (every 6 hours)
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = PeriodicWorkRequestBuilder<ContactsSyncWorker>(
                6, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    15, TimeUnit.MINUTES
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest
            )

            Log.d(TAG, "Contacts sync worker scheduled")
        }

        /**
         * Trigger an immediate one-time sync
         */
        fun syncNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = OneTimeWorkRequestBuilder<ContactsSyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "contacts_sync_now",
                ExistingWorkPolicy.REPLACE,
                syncRequest
            )

            Log.d(TAG, "Immediate contacts sync triggered")
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Skip sync if no devices are paired - prevents unnecessary Cloud Function calls
        if (!DesktopSyncService.hasPairedDevices(applicationContext)) {
            Log.d(TAG, "Skipping contacts sync - no paired devices")
            return@withContext Result.success()
        }

        try {
            Log.d(TAG, "Starting contacts sync...")
            val contactsSyncService = ContactsSyncService(applicationContext)
            contactsSyncService.syncContacts()
            Log.d(TAG, "Contacts sync completed successfully")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing contacts", e)
            Result.retry()
        }
    }
}
