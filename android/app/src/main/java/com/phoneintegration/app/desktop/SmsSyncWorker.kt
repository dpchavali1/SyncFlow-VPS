package com.phoneintegration.app.desktop

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.core.content.FileProvider
import androidx.work.*
import com.google.firebase.functions.FirebaseFunctions
import com.phoneintegration.app.MmsHelper
import com.phoneintegration.app.SmsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * SmsSyncWorker.kt - Background SMS Synchronization Workers for SyncFlow
 *
 * This file contains WorkManager-based background workers responsible for synchronizing
 * SMS/MMS messages between the Android device and Firebase/desktop clients. It implements
 * a dual-worker architecture:
 *
 * 1. [SmsSyncWorker] - Handles uploading SMS messages FROM Android TO Firebase
 * 2. [OutgoingMessageWorker] - Handles sending messages FROM desktop TO Android
 *
 * ## Sync Architecture Overview
 *
 * The SMS sync system operates in conjunction with [IntelligentSyncManager] which handles
 * real-time updates. These workers serve as:
 * - Backup sync mechanism when real-time sync fails
 * - Batch processing for bulk operations
 * - Periodic full sync to ensure data consistency
 *
 * ## Firebase Interaction Patterns
 *
 * **Inbound Sync (SmsSyncWorker):**
 * - Reads SMS messages from Android's ContentProvider (content://sms/)
 * - Uploads messages to Firebase Realtime Database via [DesktopSyncService]
 * - Filters to last 7 days of messages to balance freshness with efficiency
 *
 * **Outbound Sync (OutgoingMessageWorker):**
 * - Listens for pending messages in Firebase `outgoing_messages` collection
 * - Sends SMS/MMS via Android's native messaging APIs
 * - Updates Firebase with sent status and writes to `messages` collection
 * - Supports MMS attachments via Cloudflare R2 storage
 *
 * ## WorkManager Configuration
 *
 * Both workers use:
 * - [NetworkType.CONNECTED] constraint - requires network connectivity
 * - Exponential backoff for retry logic
 * - Unique work names to prevent duplicate scheduling
 * - [ExistingPeriodicWorkPolicy.KEEP] to avoid redundant workers
 *
 * ## Battery Optimization Considerations
 *
 * - Uses PeriodicWorkRequest with extended intervals (30-60 minutes) as IntelligentSyncManager
 *   handles frequent updates, reducing battery drain
 * - Workers yield to system battery optimization policies
 * - Network constraint prevents unnecessary wake-ups when offline
 * - OneTimeWorkRequest available for user-triggered immediate sync
 *
 * ## Threading Model
 *
 * Both workers extend [CoroutineWorker] and execute on [Dispatchers.IO] to:
 * - Avoid blocking the main thread
 * - Handle network I/O efficiently
 * - Support coroutine-based Firebase SDK calls with await()
 *
 * @see IntelligentSyncManager For real-time sync coordination
 * @see DesktopSyncService For Firebase data operations
 * @see SmsRepository For SMS ContentProvider access
 */

// =============================================================================
// REGION: SmsSyncWorker - Inbound SMS Sync (Android -> Firebase)
// =============================================================================

/**
 * Background worker that periodically syncs SMS messages to Firebase.
 *
 * This worker performs periodic backup synchronization of SMS messages from the Android
 * device to Firebase. It operates as a complement to [IntelligentSyncManager]'s real-time
 * sync, ensuring data consistency even when real-time listeners fail.
 *
 * ## Scheduling Behavior
 *
 * - Default interval: 60 minutes (backup to real-time sync)
 * - Requires network connectivity
 * - Uses exponential backoff on failure
 * - Supports immediate sync via [syncNow]
 *
 * ## Message Selection
 *
 * Syncs messages from the last 7 days only, balancing:
 * - Data freshness for desktop clients
 * - Network bandwidth efficiency
 * - Firebase storage costs
 *
 * @param context Application context for accessing system services
 * @param params WorkManager parameters including input data and run attempt count
 */
class SmsSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    // -------------------------------------------------------------------------
    // Companion Object: Static Configuration and Work Scheduling
    // -------------------------------------------------------------------------

    companion object {
        private const val TAG = "SmsSyncWorker"

        /** Unique work name for WorkManager to identify this periodic work */
        const val WORK_NAME = "sms_sync_work"

        /**
         * Schedule adaptive SMS sync as a periodic background task.
         *
         * This method registers a periodic work request with WorkManager that runs
         * every 60 minutes as a backup to [IntelligentSyncManager]'s real-time sync.
         *
         * ## WorkManager Configuration
         *
         * - **Interval:** 60 minutes (minimum for periodic work is 15 minutes)
         * - **Constraints:** Requires network connectivity
         * - **Backoff:** Exponential backoff starting at MIN_BACKOFF_MILLIS (10 seconds)
         * - **Policy:** KEEP - won't replace existing scheduled work
         *
         * ## Battery Optimization
         *
         * The 60-minute interval is intentionally long because:
         * 1. IntelligentSyncManager handles real-time updates via Firebase listeners
         * 2. This worker serves as backup/consistency check only
         * 3. Reduces battery drain from frequent wake-ups
         *
         * @param context Application context for WorkManager access
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            // Use longer intervals since IntelligentSyncManager handles real-time updates
            val syncRequest = PeriodicWorkRequestBuilder<SmsSyncWorker>(
                repeatInterval = 60, // Sync every hour as backup (IntelligentSyncManager handles frequent updates)
                repeatIntervalTimeUnit = TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest
            )

            Log.d(TAG, "Adaptive SMS sync worker scheduled (backup to IntelligentSyncManager)")
        }

        /**
         * Cancel any scheduled SMS sync work.
         *
         * Cancels both the periodic work and any pending one-time work requests.
         * Call this when:
         * - User disables desktop sync feature
         * - User signs out
         * - App is being uninstalled/cleared
         *
         * @param context Application context for WorkManager access
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "SMS sync worker cancelled")
        }

        /**
         * Trigger an immediate one-time SMS sync.
         *
         * Creates and enqueues a [OneTimeWorkRequest] for immediate execution.
         * Use this for:
         * - User-initiated manual sync (pull-to-refresh)
         * - IntelligentSyncManager batch operations
         * - Initial sync after app installation
         *
         * Note: This does not affect the periodic schedule; both can run independently.
         *
         * @param context Application context for WorkManager access
         */
        fun syncNow(context: Context) {
            val syncRequest = OneTimeWorkRequestBuilder<SmsSyncWorker>()
                .build()

            WorkManager.getInstance(context).enqueue(syncRequest)
            Log.d(TAG, "Immediate SMS sync triggered by IntelligentSyncManager")
        }
    }

    // -------------------------------------------------------------------------
    // Worker Execution: Main Sync Logic
    // -------------------------------------------------------------------------

    /**
     * Main worker execution method called by WorkManager.
     *
     * ## Execution Flow
     *
     * 1. Creates [DesktopSyncService] and [SmsRepository] instances
     * 2. Queries messages from the last 7 days via ContentProvider
     * 3. Uploads messages to Firebase via DesktopSyncService.syncMessages()
     * 4. Returns [Result.success] or [Result.retry] based on outcome
     *
     * ## Firebase Interaction
     *
     * Messages are synced to: `users/{userId}/messages/{messageId}`
     * Each message document contains: address, body, timestamp, type, read status
     *
     * ## Error Handling
     *
     * - Returns [Result.retry] on failure, triggering exponential backoff
     * - Exceptions are logged but not propagated to avoid worker crashes
     * - WorkManager will retry up to default maximum attempts
     *
     * @return [Result.success] on successful sync, [Result.retry] on failure
     */
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting SMS sync...")

            val syncService = DesktopSyncService(applicationContext)
            val smsRepository = SmsRepository(applicationContext)

            // Get messages from last 7 days for periodic sync (balance between freshness and efficiency)
            val messages = smsRepository.getMessagesFromLastDays(days = 7)

            Log.d(TAG, "Syncing ${messages.size} messages from last 7 days")

            // Sync each message
            syncService.syncMessages(messages)

            Log.d(TAG, "SMS sync completed successfully")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error during SMS sync", e)
            Result.retry()
        }
    }
}

// =============================================================================
// REGION: OutgoingMessageWorker - Outbound Message Sync (Desktop -> Android)
// =============================================================================

/**
 * Background worker that processes outgoing messages initiated from desktop clients.
 *
 * This worker monitors Firebase for pending outgoing messages created by macOS/desktop
 * clients and sends them via the Android device's SMS/MMS capabilities. It bridges
 * the gap between desktop UI and Android's native messaging APIs.
 *
 * ## Message Flow
 *
 * 1. Desktop client writes message to `users/{userId}/outgoing_messages/{messageId}`
 * 2. This worker polls Firebase for pending messages (or real-time via IntelligentSyncManager)
 * 3. Worker sends SMS/MMS using Android's native APIs
 * 4. On success: writes to `messages` collection and deletes from `outgoing_messages`
 * 5. Desktop client receives confirmation via Firebase listener
 *
 * ## MMS Attachment Handling
 *
 * For MMS messages with attachments:
 * 1. Attachments are stored in Cloudflare R2 (or inline as Base64)
 * 2. Worker downloads attachment via presigned URL from `getR2DownloadUrl` Cloud Function
 * 3. Saves to local cache, creates content URI via FileProvider
 * 4. Sends via [MmsHelper.sendMms]
 * 5. Cleans up cached attachment file
 *
 * ## Firebase Data Structure
 *
 * **Input (outgoing_messages):**
 * ```
 * {
 *   address: "+1234567890",
 *   body: "Message text",
 *   isMms: true/false,
 *   attachments: [{ url: "r2://...", fileName: "...", contentType: "..." }]
 * }
 * ```
 *
 * **Output (messages):**
 * Written via [DesktopSyncService.writeSentMessage] or [writeSentMmsMessage]
 *
 * ## Battery Optimization
 *
 * - 30-minute periodic interval (real-time handled by IntelligentSyncManager)
 * - Requires network connectivity constraint
 * - Processes all pending messages in single wake cycle
 *
 * @param context Application context for system service access
 * @param params WorkManager parameters
 */
class OutgoingMessageWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    // -------------------------------------------------------------------------
    // Companion Object: Static Configuration and Work Scheduling
    // -------------------------------------------------------------------------

    companion object {
        private const val TAG = "OutgoingMessageWorker"

        /** Unique work name for WorkManager to identify this periodic work */
        const val WORK_NAME = "outgoing_message_work"

        /**
         * Schedule periodic polling for outgoing messages from desktop.
         *
         * Registers a 30-minute periodic work request. The relatively short interval
         * compared to [SmsSyncWorker] is because:
         * 1. Users expect quick delivery when sending from desktop
         * 2. IntelligentSyncManager handles real-time, this is backup
         * 3. Initial delay of 0 ensures first check happens immediately
         *
         * ## WorkManager Configuration
         *
         * - **Interval:** 30 minutes
         * - **Initial Delay:** 0 seconds (runs immediately on schedule)
         * - **Constraints:** Requires network connectivity
         * - **Policy:** KEEP - preserves existing schedule
         *
         * @param context Application context for WorkManager access
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            // IntelligentSyncManager handles real-time outgoing messages via listeners
            // This worker serves as backup and for bulk operations
            val workRequest = PeriodicWorkRequestBuilder<OutgoingMessageWorker>(
                repeatInterval = 30, // Less frequent since IntelligentSyncManager handles real-time
                repeatIntervalTimeUnit = TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setInitialDelay(0, TimeUnit.SECONDS) // Run immediately
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )

            Log.d(TAG, "Adaptive outgoing message worker scheduled (backup to IntelligentSyncManager)")
        }

        /**
         * Trigger an immediate check for pending outgoing messages.
         *
         * Use this when:
         * - User sends a message from desktop and wants immediate delivery
         * - IntelligentSyncManager detects new outgoing message
         * - Manual sync requested by user
         *
         * @param context Application context for WorkManager access
         */
        fun checkNow(context: Context) {
            val workRequest = OneTimeWorkRequestBuilder<OutgoingMessageWorker>()
                .build()

            WorkManager.getInstance(context).enqueue(workRequest)
            Log.d(TAG, "Immediate outgoing message check triggered")
        }

        /**
         * Cancel all scheduled outgoing message work.
         *
         * @param context Application context for WorkManager access
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Outgoing message worker cancelled")
        }
    }

    // -------------------------------------------------------------------------
    // Worker Execution: Outgoing Message Processing
    // -------------------------------------------------------------------------

    /**
     * Main worker execution - processes all pending outgoing messages.
     *
     * ## Execution Flow
     *
     * 1. Fetch pending messages from `outgoing_messages` via DesktopSyncService
     * 2. For each message:
     *    - Parse address, body, and attachment info
     *    - Send via SMS or MMS based on content type
     *    - Write sent message to `messages` collection
     *    - Delete from `outgoing_messages` on success
     * 3. Return success or retry based on overall outcome
     *
     * ## Error Handling
     *
     * - Individual message failures don't stop processing of other messages
     * - Logs errors for debugging but continues with next message
     * - Returns [Result.retry] only on catastrophic failures (auth, network)
     *
     * ## Firebase Operations
     *
     * - Read: `users/{userId}/outgoing_messages`
     * - Write: `users/{userId}/messages/{messageId}`
     * - Delete: `users/{userId}/outgoing_messages/{messageId}`
     *
     * @return [Result.success] on completion, [Result.retry] on failure
     */
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Checking for outgoing messages...")

            val syncService = DesktopSyncService(applicationContext)
            val smsRepository = SmsRepository(applicationContext)

            // Get outgoing messages from Firebase
            val outgoingMessages = syncService.getOutgoingMessages()

            if (outgoingMessages.isEmpty()) {
                Log.d(TAG, "No outgoing messages to process")
                return@withContext Result.success()
            }

            Log.d(TAG, "Found ${outgoingMessages.size} outgoing messages")

            // Process each message
            outgoingMessages.forEach { (messageId, messageData) ->
                try {
                    val address = messageData["address"] as? String ?: return@forEach
                    val body = messageData["body"] as? String ?: ""
                    val isMms = messageData["isMms"] as? Boolean ?: false
                    @Suppress("UNCHECKED_CAST")
                    val attachments = messageData["attachments"] as? List<Map<String, Any?>>

                    val sendSuccess = if (isMms && !attachments.isNullOrEmpty()) {
                        Log.d(TAG, "Sending MMS to $address (attachments=${attachments.size})")
                        sendMmsWithAttachments(address, body, attachments)
                    } else if (body.isNotBlank()) {
                        Log.d(TAG, "Sending SMS to $address: $body")
                        smsRepository.sendSms(address, body)
                    } else {
                        Log.w(TAG, "Empty outgoing message (no body, no attachments)")
                        false
                    }

                    if (!sendSuccess) {
                        Log.e(TAG, "Failed to send message $messageId to $address")
                        return@forEach
                    }

                    // Write sent message to messages collection
                    if (isMms && !attachments.isNullOrEmpty()) {
                        syncService.writeSentMmsMessage(messageId, address, body, attachments)
                    } else {
                        syncService.writeSentMessage(messageId, address, body)
                    }

                    // Delete from outgoing_messages
                    syncService.deleteOutgoingMessage(messageId)

                    Log.d(TAG, "Message sent and synced successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending message $messageId", e)
                }
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error processing outgoing messages", e)
            Result.retry()
        }
    }

    // -------------------------------------------------------------------------
    // MMS Attachment Handling
    // -------------------------------------------------------------------------

    /**
     * Send an MMS message with attachments downloaded from R2 storage.
     *
     * ## Attachment Processing Flow
     *
     * 1. Extract attachment metadata (URL/inlineData, fileName, contentType)
     * 2. Get attachment data either from:
     *    - Inline Base64 data (small attachments embedded in Firebase)
     *    - R2 presigned URL (larger files stored in Cloudflare R2)
     * 3. Write data to local cache directory
     * 4. Create content URI via FileProvider for MMS API
     * 5. Send MMS via [MmsHelper.sendMms]
     * 6. Clean up cached file
     *
     * ## Current Limitations
     *
     * - Only processes first attachment (MMS API limitation)
     * - Requires FileProvider configuration in AndroidManifest
     * - Cache files stored in `cacheDir/mms_outgoing/`
     *
     * @param address Recipient phone number (E.164 format preferred)
     * @param body Optional text body for the MMS
     * @param attachments List of attachment metadata maps from Firebase
     * @return true if MMS was sent successfully, false otherwise
     */
    private suspend fun sendMmsWithAttachments(
        address: String,
        body: String,
        attachments: List<Map<String, Any?>>
    ): Boolean {
        return try {
            val attachment = attachments.firstOrNull() ?: return false

            val url = attachment["url"] as? String
            val inlineData = attachment["inlineData"] as? String
            val fileName = attachment["fileName"] as? String ?: "attachment"
            val contentType = attachment["contentType"] as? String ?: "application/octet-stream"

            Log.d(TAG, "Processing MMS attachment: $fileName ($contentType)")

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

            Log.d(TAG, "Attachment data size: ${attachmentData.size} bytes")

            val cacheDir = File(applicationContext.cacheDir, "mms_outgoing")
            cacheDir.mkdirs()
            val cacheFile = File(cacheDir, fileName)
            cacheFile.writeBytes(attachmentData)

            val contentUri = FileProvider.getUriForFile(
                applicationContext,
                "${applicationContext.packageName}.provider",
                cacheFile
            )

            val success = MmsHelper.sendMms(applicationContext, address, contentUri, body.ifEmpty { null })

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
     * Download an attachment file from Cloudflare R2 storage.
     *
     * ## R2 Download Flow
     *
     * 1. Call `getR2DownloadUrl` Cloud Function with r2Key
     * 2. Receive presigned download URL (typically valid for 1 hour)
     * 3. Download file via HTTP GET to presigned URL
     * 4. Return raw bytes for local processing
     *
     * ## Firebase Function Interaction
     *
     * **Request:** `{ r2Key: "files/userId/..." }`
     * **Response:** `{ downloadUrl: "https://r2.cloudflarestorage.com/..." }`
     *
     * ## Network Configuration
     *
     * - Connect timeout: 30 seconds
     * - Read timeout: 60 seconds (accommodates larger files)
     * - Accepts HTTP 2xx responses only
     *
     * @param r2Key The R2 storage key (path) for the attachment
     * @return Raw file bytes if successful, null on any error
     */
    private suspend fun downloadAttachment(r2Key: String): ByteArray? {
        return try {
            // Get presigned download URL from R2 via Cloud Function
            val downloadUrlData = hashMapOf("r2Key" to r2Key)
            val result = FirebaseFunctions.getInstance()
                .getHttpsCallable("getR2DownloadUrl")
                .call(downloadUrlData)
                .await()

            @Suppress("UNCHECKED_CAST")
            val response = result.data as? Map<String, Any>
            val downloadUrl = response?.get("downloadUrl") as? String
                ?: throw Exception("Failed to get R2 download URL")

            // Download from presigned URL
            withContext(Dispatchers.IO) {
                var connection: HttpURLConnection? = null
                try {
                    val urlObj = URL(downloadUrl)
                    connection = urlObj.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 30000
                    connection.readTimeout = 60000

                    if (connection.responseCode in 200..299) {
                        connection.inputStream.use { it.readBytes() }
                    } else {
                        Log.e(TAG, "Download failed with response code: ${connection.responseCode}")
                        null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error downloading from URL", e)
                    null
                } finally {
                    connection?.disconnect()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading attachment from R2", e)
            null
        }
    }
}
