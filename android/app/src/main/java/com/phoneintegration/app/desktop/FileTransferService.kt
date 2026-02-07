package com.phoneintegration.app.desktop

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.phoneintegration.app.vps.VPSClient
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * FileTransferService.kt - Cross-Platform File Transfer Service for SyncFlow
 *
 * This service enables seamless file sharing between Android devices and macOS/desktop
 * clients using Cloudflare R2 for storage and Firebase Realtime Database for coordination.
 *
 * ## Architecture Overview
 *
 * The file transfer system uses a three-component architecture:
 *
 * 1. **Firebase Realtime Database** - Coordinates transfers and stores metadata
 *    - Path: `users/{userId}/file_transfers/{transferId}`
 *    - Stores: fileName, fileSize, contentType, r2Key, status, timestamp
 *
 * 2. **Cloudflare R2 Storage** - Stores actual file content
 *    - Accessed via presigned URLs from Cloud Functions
 *    - No egress fees (unlike S3), enabling generous transfer limits
 *
 * 3. **Firebase Cloud Functions** - Provides secure presigned URL generation
 *    - `getR2UploadUrl` - Generate presigned PUT URL for uploads
 *    - `getR2DownloadUrl` - Generate presigned GET URL for downloads
 *    - `confirmR2Upload` - Confirm upload completion and record usage
 *    - `deleteR2File` - Clean up files after successful download
 *
 * ## Transfer Flow
 *
 * **Upload (Android -> Desktop):**
 * 1. App calls [uploadFile] with local file
 * 2. Service checks subscription tier limits via [canTransfer]
 * 3. Gets presigned upload URL from `getR2UploadUrl` Cloud Function
 * 4. Uploads file directly to R2 via HTTP PUT
 * 5. Confirms upload via `confirmR2Upload` (records usage)
 * 6. Creates transfer record in Firebase Database
 * 7. Desktop client receives transfer via Firebase listener
 *
 * **Download (Desktop -> Android):**
 * 1. Desktop creates transfer record with r2Key in Firebase
 * 2. This service's [ChildEventListener] detects new transfer
 * 3. Service validates transfer (source, status, age, size)
 * 4. Gets presigned download URL from Cloud Function
 * 5. Downloads file to temp directory
 * 6. Saves to Downloads/SyncFlow via MediaStore (Android 10+) or direct file access
 * 7. Updates transfer status and cleans up R2 file
 *
 * ## Subscription Tiers
 *
 * | Tier | Max File Size |
 * |------|---------------|
 * | Free | 50 MB         |
 * | Pro  | 1 GB          |
 *
 * Note: No daily transfer limits since R2 has free egress.
 *
 * ## Battery Optimization Considerations
 *
 * - Uses Firebase Realtime Database listeners (efficient long-polling)
 * - Downloads run on [Dispatchers.IO] to avoid blocking
 * - File age check (5 minutes) prevents processing stale transfers
 * - Duplicate prevention via [processingFileIds] set
 *
 * ## Notification Handling
 *
 * - Creates notification channel "file_transfer_channel" on init
 * - Shows progress notification during download
 * - Shows completion notification when file is saved
 * - Notifications are auto-cancelled on tap
 *
 * @see ClipboardSyncService For text-based sync
 * @see PhotoSyncService For photo thumbnail sync
 */

// =============================================================================
// REGION: FileTransferService - Main Service Class
// =============================================================================

/**
 * Service that handles file transfers between macOS/desktop and Android.
 *
 * Files are uploaded to Cloudflare R2 and synced via VPS.
 * This service provides bidirectional file transfer capabilities with
 * subscription-based size limits.
 *
 * VPS Backend Only - Uses VPS API instead of Firebase.
 *
 * ## Usage
 *
 * ```kotlin
 * val service = FileTransferService(context)
 * service.startListening()  // Start receiving files from desktop
 *
 * // Upload a file to share with desktop
 * val success = service.uploadFile(file, "document.pdf", "application/pdf")
 *
 * // When done
 * service.stopListening()
 * ```
 *
 * ## Thread Safety
 *
 * - File listeners run on [Dispatchers.IO] via coroutines
 * - [processingFileIds] is synchronized for thread-safe duplicate detection
 * - Toast messages are posted to MainScope
 *
 * @param context Application context (will be converted to applicationContext)
 */
class FileTransferService(context: Context) {

    // -------------------------------------------------------------------------
    // Service Dependencies and State
    // -------------------------------------------------------------------------

    /** Application context for system services and content resolver access */
    private val context: Context = context.applicationContext

    /** VPS Client for API calls */
    private val vpsClient = VPSClient.getInstance(this.context)

    /** HTTP client for file downloads/uploads */
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    /** Polling job for file transfers */
    private var pollingJob: Job? = null

    /** Coroutine scope for async operations, uses SupervisorJob for independent failure handling */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Set of file IDs currently being processed.
     *
     * Prevents duplicate processing when polling returns the same transfer.
     * Access is synchronized.
     */
    private val processingFileIds = mutableSetOf<String>()

    /** System notification manager for download progress and completion notifications */
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    // -------------------------------------------------------------------------
    // Companion Object: Constants and Tier Limits
    // -------------------------------------------------------------------------

    companion object {
        private const val TAG = "FileTransferService"

        /** Firebase Database path for file transfer records */
        private const val FILE_TRANSFERS_PATH = "file_transfers"

        /** Firebase Database root path for user data */
        private const val USERS_PATH = "users"

        /** Notification channel ID for file transfer notifications */
        private const val CHANNEL_ID = "file_transfer_channel"

        /** Notification ID (reused for download progress updates) */
        private const val NOTIFICATION_ID = 9001

        /** Polling interval for incoming file transfers */
        private const val POLLING_INTERVAL_MS = 10000L // Poll every 10 seconds

        // -----------------------------------------------------------------
        // Subscription Tier Limits
        // -----------------------------------------------------------------

        /**
         * Maximum file size for free tier users (50 MB).
         * Free egress from R2 allows generous limits without daily caps.
         */
        const val MAX_FILE_SIZE_FREE = 50 * 1024 * 1024L     // 50MB for free users

        /**
         * Maximum file size for Pro tier users (1 GB).
         * Enables transfer of large media files and documents.
         */
        const val MAX_FILE_SIZE_PRO = 1024 * 1024 * 1024L    // 1GB for pro users

        /**
         * Legacy constant for backward compatibility with older code.
         * @deprecated Use [MAX_FILE_SIZE_FREE] or tier-aware [canTransfer] instead.
         */
        private const val MAX_FILE_SIZE = 50 * 1024 * 1024L
    }

    // -------------------------------------------------------------------------
    // REGION: Subscription Tier Management
    // -------------------------------------------------------------------------

    /**
     * Data class representing current transfer limits based on subscription tier.
     *
     * @property maxFileSize Maximum allowed file size in bytes
     * @property isPro Whether user has Pro subscription
     */
    data class TransferLimits(
        val maxFileSize: Long,
        val isPro: Boolean
    )

    /**
     * Check if the current user has a Pro subscription.
     *
     * Queries VPS API for the user's subscription plan.
     * Returns true for any non-free plan (pro, premium, etc.).
     *
     * ## Failure Handling
     *
     * On query failure (network issues, etc.), defaults to allowing
     * transfers to avoid blocking legitimate users due to connectivity.
     *
     * @return true if user has Pro subscription, false for free tier
     */
    private suspend fun isPro(): Boolean {
        return try {
            if (!vpsClient.isAuthenticated) return false

            val subscription = vpsClient.getUserSubscription()
            val plan = subscription["plan"] as? String
            plan != null && plan != "free"
        } catch (e: Exception) {
            Log.w(TAG, "Error checking pro status: ${e.message}")
            // Default to allowing transfers if check fails (connectivity issues)
            true
        }
    }

    /**
     * Get current transfer limits based on user's subscription tier.
     *
     * @return [TransferLimits] containing max file size and Pro status
     */
    suspend fun getTransferLimits(): TransferLimits {
        val isPro = isPro()
        val maxFileSize = if (isPro) MAX_FILE_SIZE_PRO else MAX_FILE_SIZE_FREE

        return TransferLimits(
            maxFileSize = maxFileSize,
            isPro = isPro
        )
    }

    /**
     * Result of a transfer eligibility check.
     *
     * @property allowed Whether the transfer is permitted
     * @property reason Human-readable explanation if not allowed (null if allowed)
     */
    data class TransferCheck(
        val allowed: Boolean,
        val reason: String? = null
    )

    /**
     * Check if a file transfer is allowed based on subscription limits.
     *
     * Validates the file size against the user's tier limit.
     * For free users, includes upgrade suggestion in rejection message.
     *
     * @param fileSize Size of the file to transfer in bytes
     * @return [TransferCheck] indicating if transfer is allowed and why not
     */
    suspend fun canTransfer(fileSize: Long): TransferCheck {
        val limits = getTransferLimits()

        return when {
            fileSize > limits.maxFileSize -> {
                val maxMB = limits.maxFileSize / (1024 * 1024)
                TransferCheck(false, "File too large. Max size: ${maxMB}MB" +
                    if (!limits.isPro) " (Upgrade to Pro for 1GB)" else "")
            }
            else -> TransferCheck(true)
        }
    }

    init {
        // Create notification channel on service instantiation
        createNotificationChannel()
    }

    // -------------------------------------------------------------------------
    // REGION: Data Classes
    // -------------------------------------------------------------------------

    /**
     * Data class representing a file transfer record.
     *
     * Maps to Firebase Database structure at:
     * `users/{userId}/file_transfers/{id}`
     *
     * @property id Unique transfer identifier (Firebase push key or timestamp)
     * @property originalId Original ID from source device (for deduplication)
     * @property fileName Display name of the file
     * @property fileSize File size in bytes
     * @property contentType MIME type (e.g., "image/jpeg", "application/pdf")
     * @property downloadUrl Presigned download URL (legacy Firebase Storage)
     * @property source Origin device type ("android" or "macos")
     * @property timestamp Unix timestamp of transfer creation
     * @property status Transfer status: "pending", "downloading", "downloaded", "failed"
     */
    data class FileTransfer(
        val id: String,
        val fileName: String,
        val fileSize: Long,
        val contentType: String,
        val downloadUrl: String,
        val source: String,
        val timestamp: Long,
        val status: String
    )

    // -------------------------------------------------------------------------
    // REGION: Service Lifecycle Management
    // -------------------------------------------------------------------------

    /**
     * Start listening for incoming file transfers from desktop clients.
     *
     * Starts polling VPS API for file transfers.
     *
     * Call this when:
     * - App starts and user is authenticated
     * - Desktop sync feature is enabled
     * - After [stopListening] to resume
     */
    fun startListening() {
        Log.d(TAG, "Starting file transfer service")
        startPolling()
    }

    /**
     * Stop listening for file transfers and clean up resources.
     *
     * Stops polling and cancels the coroutine scope.
     * Call this when:
     * - User signs out
     * - Desktop sync feature is disabled
     * - App is being destroyed
     */
    fun stopListening() {
        Log.d(TAG, "Stopping file transfer service")
        stopPolling()
        scope.cancel()
    }

    // -------------------------------------------------------------------------
    // REGION: Polling Management
    // -------------------------------------------------------------------------

    /**
     * Start polling VPS API for incoming file transfers.
     */
    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                try {
                    if (vpsClient.isAuthenticated) {
                        pollFileTransfers()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error polling file transfers", e)
                }
                delay(POLLING_INTERVAL_MS)
            }
        }
        Log.d(TAG, "File transfer polling started")
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
        Log.d(TAG, "File transfer polling stopped")
    }

    /**
     * Poll for file transfers from VPS API
     */
    private suspend fun pollFileTransfers() {
        try {
            val transfers = vpsClient.getFileTransfers()
            for (transfer in transfers) {
                handleFileTransfer(transfer)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching file transfers", e)
        }
    }

    // -------------------------------------------------------------------------
    // REGION: Incoming File Transfer Processing
    // -------------------------------------------------------------------------

    /**
     * Process an incoming file transfer from VPS.
     *
     * Validates the transfer and initiates download if eligible.
     *
     * ## Validation Checks
     *
     * 1. **Duplicate prevention** - Skip if already in [processingFileIds]
     * 2. **Source filter** - Skip transfers from Android (we're Android)
     * 3. **Status check** - Only process "pending" transfers
     * 4. **Age check** - Skip transfers older than 5 minutes (stale data)
     *
     * ## Thread Safety
     *
     * Access to [processingFileIds] is synchronized to prevent race conditions
     * when multiple API responses arrive simultaneously.
     *
     * @param transfer VPSFileTransfer containing transfer metadata
     */
    private fun handleFileTransfer(transfer: com.phoneintegration.app.vps.VPSFileTransfer) {
        val fileId = transfer.id

        // Prevent duplicate processing of the same file
        synchronized(processingFileIds) {
            if (processingFileIds.contains(fileId)) {
                Log.d(TAG, "Skipping duplicate file transfer: $fileId")
                return
            }
        }

        val fileName = transfer.fileName
        val fileSize = transfer.fileSize
        val contentType = transfer.contentType
        val source = transfer.source
        val status = transfer.status
        val timestamp = transfer.timestamp

        // Support both R2 (r2Key) and direct download URL
        val r2Key = transfer.r2Key
        val downloadUrl = transfer.downloadUrl

        if (r2Key == null && downloadUrl == null) {
            return
        }

        // Only process files from other devices (not Android)
        if (source == "android") {
            return
        }

        // Only process pending files
        if (status != "pending") {
            return
        }

        // Check if file is recent (within last 5 minutes)
        val now = System.currentTimeMillis()
        if (now - timestamp > 300000) {
            Log.d(TAG, "Ignoring old file transfer: $fileId")
            return
        }

        // Mark as processing before starting download
        synchronized(processingFileIds) {
            processingFileIds.add(fileId)
        }

        Log.d(TAG, "Received file transfer: $fileName ($fileSize bytes)")

        // Download the file
        scope.launch {
            try {
                downloadFile(fileId, fileName, fileSize, contentType, r2Key, downloadUrl)
            } finally {
                // Remove from processing set after completion (success or failure)
                synchronized(processingFileIds) {
                    processingFileIds.remove(fileId)
                }
            }
        }
    }

    /**
     * Download a file from R2 storage or direct URL.
     *
     * ## Download Flow
     *
     * 1. Validate file size against tier limits
     * 2. Show download progress notification
     * 3. Update transfer status to "downloading"
     * 4. Get presigned URL (R2) or use direct URL
     * 5. Download to temp file
     * 6. Save to Downloads/SyncFlow via MediaStore
     * 7. Update status to "downloaded"
     * 8. Clean up R2 file and VPS record
     *
     * ## Error Handling
     *
     * - Shows toast on failure
     * - Updates transfer status to "failed" with error message
     * - Always removes fileId from [processingFileIds] (in finally block of caller)
     *
     * @param fileId Unique identifier for this transfer
     * @param fileName Display name for the downloaded file
     * @param fileSize Size in bytes for validation
     * @param contentType MIME type for MediaStore
     * @param r2Key Cloudflare R2 storage key (null if direct URL)
     * @param directDownloadUrl Direct download URL (null if R2)
     */
    private suspend fun downloadFile(
        fileId: String,
        fileName: String,
        fileSize: Long,
        contentType: String,
        r2Key: String?,
        directDownloadUrl: String?
    ) {
        try {
            if (!vpsClient.isAuthenticated) return

            // Check file size
            if (fileSize > MAX_FILE_SIZE) {
                Log.w(TAG, "File too large: $fileSize bytes")
                updateTransferStatus(fileId, "failed", "File too large (max 50MB)")
                showToast("File too large to download")
                return
            }

            // Show download notification
            showDownloadNotification(fileName, 0)

            // Update status to downloading
            updateTransferStatus(fileId, "downloading")

            // Get download URL - either from R2 via VPS or use direct URL
            val downloadUrl: String = if (r2Key != null) {
                // Get presigned download URL from R2 via VPS
                vpsClient.getDownloadUrl(r2Key)
            } else {
                directDownloadUrl ?: throw Exception("No download URL available")
            }

            // Download to temp file first
            val tempFile = File(context.cacheDir, "download_$fileName")

            withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url(downloadUrl)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw Exception("Download failed with status: ${response.code}")
                    }

                    response.body?.byteStream()?.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }

            Log.d(TAG, "File downloaded to temp: ${tempFile.absolutePath}")

            // Save to Downloads folder
            saveToDownloads(fileName, contentType, tempFile)

            // Update status
            updateTransferStatus(fileId, "downloaded")

            // Show completion notification immediately
            showDownloadCompleteNotification(fileName)

            // Clean up temp file
            tempFile.delete()

            Log.d(TAG, "File saved successfully: $fileName")

            // Delete file from R2 after successful download (in background)
            if (r2Key != null) {
                try {
                    vpsClient.deleteR2File(r2Key)
                    Log.d(TAG, "Cleaned up file from R2: $fileName")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to clean up file from R2 (non-fatal): ${e.message}")
                }
            }

            // Clean up the transfer record from VPS after a delay
            // This gives the sender time to see the "downloaded" status
            try {
                delay(5000) // Wait 5 seconds
                vpsClient.deleteFileTransfer(fileId)
                Log.d(TAG, "Cleaned up transfer record from VPS: $fileId")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clean up transfer record (non-fatal): ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading file", e)
            updateTransferStatus(fileId, "failed", e.message)
            showToast("Failed to download: $fileName")
        }
    }

    // -------------------------------------------------------------------------
    // REGION: File System Operations
    // -------------------------------------------------------------------------

    /**
     * Save a downloaded file to the public Downloads folder.
     *
     * Uses different APIs based on Android version:
     * - **Android 10+ (Q)**: MediaStore API with scoped storage
     * - **Android 9 and below**: Direct file system access
     *
     * Files are saved to `Downloads/SyncFlow/` subdirectory.
     *
     * ## MediaStore (Android 10+)
     *
     * Uses ContentResolver to insert file metadata and write content.
     * Respects scoped storage restrictions without WRITE_EXTERNAL_STORAGE.
     *
     * ## Legacy (Android 9-)
     *
     * Directly accesses Environment.DIRECTORY_DOWNLOADS.
     * Requires WRITE_EXTERNAL_STORAGE permission.
     *
     * @param fileName Target file name (will overwrite if exists on legacy)
     * @param contentType MIME type for MediaStore
     * @param sourceFile Temp file containing downloaded content
     */
    private fun saveToDownloads(fileName: String, contentType: String, sourceFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Use MediaStore for Android 10+
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, contentType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SyncFlow")
            }

            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                contentValues
            )

            uri?.let {
                context.contentResolver.openOutputStream(it)?.use { outputStream ->
                    sourceFile.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            }
        } else {
            // Direct file access for older versions
            @Suppress("DEPRECATION")
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val syncFlowDir = File(downloadsDir, "SyncFlow")
            if (!syncFlowDir.exists()) {
                syncFlowDir.mkdirs()
            }

            val destFile = File(syncFlowDir, fileName)
            sourceFile.copyTo(destFile, overwrite = true)
        }
    }

    // -------------------------------------------------------------------------
    // REGION: Outgoing File Upload (Android -> Desktop)
    // -------------------------------------------------------------------------

    /**
     * Upload a file to share with other devices via Cloudflare R2.
     *
     * Respects subscription tier limits:
     * - **Free**: 50MB per file
     * - **Pro**: 1GB per file
     *
     * ## Upload Flow
     *
     * 1. Check tier limits via [canTransfer]
     * 2. Get presigned upload URL from VPS API
     * 3. Upload file directly to R2 via HTTP PUT
     * 4. Confirm upload via VPS API (records usage statistics)
     * 5. Create transfer record in VPS
     * 6. Desktop clients receive notification via polling
     *
     * ## Error Handling
     *
     * - Shows toast message on failure
     * - Returns false for any error (auth, network, limits, upload failure)
     * - Logs detailed error for debugging
     *
     * @param file Local file to upload
     * @param fileName Display name for the file
     * @param contentType MIME type (e.g., "image/png", "application/pdf")
     * @return true if upload succeeded, false otherwise
     */
    suspend fun uploadFile(file: File, fileName: String, contentType: String): Boolean {
        return try {
            if (!vpsClient.isAuthenticated) return false
            val fileSize = file.length()

            // Check tiered limits (file size)
            val transferCheck = canTransfer(fileSize)
            if (!transferCheck.allowed) {
                Log.w(TAG, "File transfer blocked: ${transferCheck.reason}")
                showToast(transferCheck.reason ?: "Transfer not allowed")
                return false
            }

            // Step 1: Get presigned upload URL from R2 via VPS
            val urlResponse = vpsClient.getUploadUrl(fileName, contentType, fileSize, "files")
            val uploadUrl = urlResponse.uploadUrl
            val r2Key = urlResponse.fileKey

            Log.d(TAG, "Got R2 upload URL for: $fileName")

            // Step 2: Upload file directly to R2 using presigned URL
            withContext(Dispatchers.IO) {
                val requestBody = file.asRequestBody(contentType.toMediaType())
                val request = Request.Builder()
                    .url(uploadUrl)
                    .put(requestBody)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw Exception("Upload to R2 failed with status: ${response.code}")
                    }
                }
            }

            Log.d(TAG, "File uploaded to R2: $fileName")

            // Step 3: Confirm upload to record usage
            vpsClient.confirmUpload(r2Key, fileSize, "files")

            // Step 4: Create transfer record via VPS
            vpsClient.createFileTransfer(fileName, fileSize, contentType, r2Key, "android")

            Log.d(TAG, "File uploaded: $fileName (${fileSize / 1024}KB)")
            showToast("File shared: $fileName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading file", e)
            val message = e.message ?: "Failed to share file"
            showToast(if (message.contains("resource-exhausted", ignoreCase = true) ||
                         message.contains("limit", ignoreCase = true)) {
                message
            } else {
                "Failed to share file"
            })
            false
        }
    }

    // -------------------------------------------------------------------------
    // REGION: Firebase Status Updates
    // -------------------------------------------------------------------------

    /**
     * Update the status of a file transfer via VPS API.
     *
     * Status values:
     * - "pending" - Transfer created, awaiting download
     * - "downloading" - Download in progress
     * - "downloaded" - Successfully downloaded
     * - "failed" - Download failed (includes error message)
     *
     * @param fileId Unique identifier of the transfer
     * @param status New status value
     * @param error Optional error message (for "failed" status)
     */
    private suspend fun updateTransferStatus(fileId: String, status: String, error: String? = null) {
        try {
            if (!vpsClient.isAuthenticated) return

            vpsClient.updateFileTransferStatus(fileId, status, error)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating transfer status", e)
        }
    }

    // -------------------------------------------------------------------------
    // REGION: Notification Management
    // -------------------------------------------------------------------------

    /**
     * Create the notification channel for file transfer notifications.
     *
     * Required for Android 8.0 (API 26) and above. Called during service
     * initialization to ensure channel exists before notifications are posted.
     *
     * Channel properties:
     * - **ID**: "file_transfer_channel"
     * - **Name**: "File Transfers"
     * - **Importance**: Default (shows in shade, makes sound)
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "File Transfers",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "File transfer notifications"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Show a download progress notification.
     *
     * Uses the same [NOTIFICATION_ID] for all progress updates so each
     * update replaces the previous notification.
     *
     * @param fileName Name of the file being downloaded
     * @param progress Download progress (0-100), 0 shows indeterminate progress
     */
    private fun showDownloadNotification(fileName: String, progress: Int) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading file")
            .setContentText(fileName)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Show a notification indicating download completed successfully.
     *
     * Replaces the progress notification with a completion message.
     * Notification auto-cancels when tapped.
     *
     * @param fileName Name of the downloaded file
     */
    private fun showDownloadCompleteNotification(fileName: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("File received from Mac")
            .setContentText("$fileName saved to Downloads/SyncFlow")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    // -------------------------------------------------------------------------
    // REGION: UI Feedback
    // -------------------------------------------------------------------------

    /**
     * Show a toast message on the main thread.
     *
     * Uses [MainScope] to ensure Toast is shown on the UI thread,
     * as this may be called from coroutines on [Dispatchers.IO].
     *
     * @param message Text to display in the toast
     */
    private fun showToast(message: String) {
        MainScope().launch {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
