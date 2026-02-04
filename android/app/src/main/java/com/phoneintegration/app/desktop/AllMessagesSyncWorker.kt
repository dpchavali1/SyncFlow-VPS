package com.phoneintegration.app.desktop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.phoneintegration.app.MmsHelper
import com.phoneintegration.app.R
import com.phoneintegration.app.SmsMessage

class AllMessagesSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    private val diagnosticsStore = SyncDiagnosticsStore(context.applicationContext)

    companion object {
        const val WORK_NAME = "sync_all_messages"
        const val PROGRESS_TOTAL = "total"
        const val PROGRESS_DONE = "done"
        const val PROGRESS_STATUS = "status"

        private const val CHANNEL_ID = "syncflow_message_sync"
        private const val CHANNEL_NAME = "Message Sync"
        private const val NOTIFICATION_ID = 1207

        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<AllMessagesSyncWorker>()
                .setConstraints(constraints)
                .addTag(WORK_NAME)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }

    override suspend fun doWork(): Result {
        createNotificationChannel()
        setForeground(createForegroundInfo("Starting sync..."))
        diagnosticsStore.markStart()

        return try {
            val syncService = DesktopSyncService(applicationContext)

            setProgress(workDataOf(PROGRESS_STATUS to "Counting messages..."))
            updateNotification("Counting messages...")

            val smsCount = queryCount(Telephony.Sms.CONTENT_URI)
            val mmsCount = queryCount(Uri.parse("content://mms"))
            val total = smsCount + mmsCount

            if (total == 0) {
                updateProgress(0, 0, "No messages to sync")
                diagnosticsStore.markComplete(0, 0)
                return Result.success(
                    workDataOf(
                        PROGRESS_TOTAL to 0,
                        PROGRESS_DONE to 0,
                        PROGRESS_STATUS to "No messages to sync"
                    )
                )
            }

            updateProgress(0, total, "Syncing 0 of $total")

            var done = 0
            done = syncAllSms(syncService, done, total)
            if (isStopped) {
                updateProgress(done, total, "Sync cancelled")
                diagnosticsStore.markCancelled(done, total)
                return Result.failure()
            }

            done = syncAllMms(syncService, done, total)
            if (isStopped) {
                updateProgress(done, total, "Sync cancelled")
                diagnosticsStore.markCancelled(done, total)
                return Result.failure()
            }

            updateProgress(total, total, "Sync complete")
            diagnosticsStore.markComplete(total, total)
            Result.success(
                workDataOf(
                    PROGRESS_TOTAL to total,
                    PROGRESS_DONE to total,
                    PROGRESS_STATUS to "Sync complete"
                )
            )
        } catch (e: Exception) {
            updateProgress(0, 0, "Sync failed: ${e.message}")
            diagnosticsStore.markFailed(e.message)
            Result.failure(
                workDataOf(PROGRESS_STATUS to (e.message ?: "Sync failed"))
            )
        } finally {
            cancelNotification()
        }
    }

    private fun queryCount(uri: Uri): Int {
        val cursor = applicationContext.contentResolver.query(
            uri,
            arrayOf("_id"),
            null,
            null,
            null
        ) ?: return 0

        cursor.use {
            return it.count
        }
    }

    private suspend fun syncAllSms(
        syncService: DesktopSyncService,
        startDone: Int,
        total: Int
    ): Int {
        var done = startDone
        val cursor = applicationContext.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE
            ),
            null,
            null,
            "${Telephony.Sms.DATE} DESC"
        ) ?: return done

        cursor.use { c ->
            while (c.moveToNext()) {
                if (isStopped) {
                    return done
                }

                val address = c.getString(1) ?: continue
                if (address.contains("@rbm.goog", ignoreCase = true) || isRcsAddress(address)) {
                    continue
                }

                val sms = SmsMessage(
                    id = c.getLong(0),
                    address = address,
                    body = c.getString(2) ?: "",
                    date = c.getLong(3),
                    type = c.getInt(4)
                )

                syncService.syncMessage(sms)
                done += 1

                if (done % 20 == 0 || done == total) {
                    updateProgress(done, total, "Syncing $done of $total")
                }
            }
        }

        return done
    }

    private suspend fun syncAllMms(
        syncService: DesktopSyncService,
        startDone: Int,
        total: Int
    ): Int {
        var done = startDone
        val cursor = applicationContext.contentResolver.query(
            Uri.parse("content://mms"),
            arrayOf("_id", "date", "msg_box"),
            null,
            null,
            "date DESC"
        ) ?: return done

        cursor.use { c ->
            while (c.moveToNext()) {
                if (isStopped) {
                    return done
                }

                val mmsId = c.getLong(0)
                val dateSec = c.getLong(1)
                val msgBox = c.getInt(2)

                var address = MmsHelper.getMmsAddress(applicationContext.contentResolver, mmsId)
                if (msgBox == 2 && (address.isNullOrBlank() ||
                        address.contains("insert-address-token", ignoreCase = true))) {
                    val recipients = MmsHelper.getMmsAllRecipients(applicationContext.contentResolver, mmsId)
                        .filter { it.isNotBlank() && !it.contains("insert-address-token", ignoreCase = true) }
                    address = recipients.firstOrNull() ?: address
                }

                if (address.isNullOrBlank()) {
                    continue
                }
                if (address.contains("@rbm.goog", ignoreCase = true) || isRcsAddress(address)) {
                    continue
                }

                val body = MmsHelper.getMmsText(applicationContext.contentResolver, mmsId) ?: ""
                val message = SmsMessage(
                    id = mmsId,
                    address = address,
                    body = body,
                    date = dateSec * 1000L,
                    type = if (msgBox == 2) 2 else 1,
                    isMms = true
                )

                syncService.syncMessage(message)
                done += 1

                if (done % 20 == 0 || done == total) {
                    updateProgress(done, total, "Syncing $done of $total")
                }
            }
        }

        return done
    }

    private fun isRcsAddress(address: String): Boolean {
        val lower = address.lowercase()
        return lower.contains("@rcs") ||
            lower.contains("rcs.google") ||
            lower.contains("rcs.goog") ||
            lower.startsWith("rcs:") ||
            lower.startsWith("rcs://")
    }
    private fun cancelNotification() {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
    }

    private suspend fun updateProgress(done: Int, total: Int, status: String) {
        setProgress(
            workDataOf(
                PROGRESS_TOTAL to total,
                PROGRESS_DONE to done,
                PROGRESS_STATUS to status
            )
        )
        updateNotification(status, done, total)
        diagnosticsStore.updateProgress(done, total, status)
    }

    private fun createForegroundInfo(message: String): ForegroundInfo {
        val notification = buildNotification(message, 0, 0, indeterminate = true)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(
        message: String,
        done: Int = 0,
        total: Int = 0
    ) {
        val notification = if (total > 0) {
            buildNotification(message, done, total, indeterminate = false)
        } else {
            buildNotification(message, 0, 0, indeterminate = true)
        }
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(
        message: String,
        done: Int,
        total: Int,
        indeterminate: Boolean
    ): Notification {
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("SyncFlow - Syncing Messages")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (!indeterminate && total > 0) {
            builder.setProgress(total, done, false)
        } else {
            builder.setProgress(0, 0, true)
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background message sync progress"
                setShowBadge(false)
            }

            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
