package com.phoneintegration.app.desktop

import android.content.Context
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Background worker to sync call history to Firebase periodically
 */
class CallHistorySyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "CallHistorySyncWorker"
        private const val WORK_NAME = "call_history_sync_work"

        /**
         * Schedule periodic call history sync (every 1 hour)
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = PeriodicWorkRequestBuilder<CallHistorySyncWorker>(
                1, TimeUnit.HOURS
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

            Log.d(TAG, "Call history sync worker scheduled")
        }

        /**
         * Trigger an immediate one-time sync
         */
        fun syncNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = OneTimeWorkRequestBuilder<CallHistorySyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "call_history_sync_now",
                ExistingWorkPolicy.REPLACE,
                syncRequest
            )

            Log.d(TAG, "Immediate call history sync triggered")
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Skip sync if no devices are paired - prevents unnecessary Cloud Function calls
        if (!DesktopSyncService.hasPairedDevices(applicationContext)) {
            Log.d(TAG, "Skipping call history sync - no paired devices")
            return@withContext Result.success()
        }

        try {
            Log.d(TAG, "Starting call history sync...")
            val callHistorySyncService = CallHistorySyncService(applicationContext)
            callHistorySyncService.syncCallHistory()
            Log.d(TAG, "Call history sync completed successfully")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing call history", e)
            Result.retry()
        }
    }
}
