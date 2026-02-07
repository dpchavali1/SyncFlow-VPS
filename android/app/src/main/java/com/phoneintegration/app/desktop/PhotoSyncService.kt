package com.phoneintegration.app.desktop

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.phoneintegration.app.auth.UnifiedIdentityManager
import com.phoneintegration.app.vps.VPSClient
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * PhotoSyncService.kt - Photo Thumbnail Synchronization Service for SyncFlow
 *
 * This service synchronizes recent photos from the Android device to Cloudflare R2
 * for display on macOS/desktop clients. Photos are converted to compressed thumbnails
 * to optimize bandwidth and storage costs.
 *
 * ## Premium Feature
 *
 * **IMPORTANT:** Photo sync is a PREMIUM-ONLY feature. Only users with active paid
 * subscriptions (monthly, yearly, 3-year, or lifetime) can sync photos. Free tier
 * and trial users will not have access to this functionality.
 *
 * ## Architecture Overview
 *
 * The photo sync system consists of:
 *
 * 1. **ContentObserver** - Monitors MediaStore for new photos
 * 2. **Thumbnail Generator** - Creates compressed JPEG thumbnails (max 800px)
 * 3. **R2 Upload Pipeline** - Uploads via presigned URLs from VPS API
 * 4. **VPS Metadata** - Stores photo metadata for desktop client consumption
 *
 * ## Sync Flow
 *
 * 1. [ContentObserver] detects new photo in MediaStore
 * 2. Premium access is verified via [hasPremiumAccess]
 * 3. Recent photos are queried (limited to [MAX_PHOTOS_TO_SYNC])
 * 4. For each new photo not in [syncedPhotoIds]:
 *    a. Create thumbnail via [createThumbnail]
 *    b. Compress to JPEG at [THUMBNAIL_QUALITY]
 *    c. Get presigned upload URL from VPS API
 *    d. Upload to R2 via OkHttp
 *    e. Confirm upload via VPS API
 * 5. Old photos beyond limit are cleaned up from R2 and VPS
 *
 * ## VPS Interaction Patterns
 *
 * **VPS API Endpoints Used:**
 * - `GET /api/photos/synced-ids` - Get already synced photo IDs
 * - `GET /api/photos` - Get list of photos
 * - `POST /api/file-transfers/upload-url` - Get presigned PUT URL for upload
 * - `POST /api/photos/confirm-upload` - Confirm upload and store metadata
 * - `POST /api/photos/delete` - Clean up old thumbnails from R2
 *
 * ## Battery Optimization Considerations
 *
 * - Uses [ContentObserver] instead of polling (efficient system-level monitoring)
 * - Debounces sync operations by [SYNC_DEBOUNCE_MS] (2 seconds)
 * - Initial sync delayed by 3 seconds to avoid startup load
 * - Limits sync to [MAX_PHOTOS_TO_SYNC] (20) to bound processing time
 * - Thumbnail compression reduces upload size significantly
 *
 * ## Duplicate Prevention
 *
 * - [syncedPhotoIds] tracks already-synced photo IDs (MediaStore _ID)
 * - IDs are loaded from VPS on startup via [loadSyncedPhotoIds]
 * - Prevents re-uploading photos on app restart or reconnection
 * - [cleanupDuplicates] handles race conditions that create duplicates
 *
 * ## WorkManager Integration
 *
 * Unlike SMS sync, photo sync does NOT use WorkManager periodic tasks because:
 * 1. ContentObserver provides efficient real-time monitoring
 * 2. Photos are less time-sensitive than messages
 * 3. Premium-only feature reduces user base requiring background processing
 *
 * @see FileTransferService For full-resolution file transfers
 * @see ClipboardSyncService For text sync
 */

// =============================================================================
// REGION: PhotoSyncService - Main Service Class
// =============================================================================

/**
 * Service that syncs recent photos from Android to VPS/R2 for display on macOS.
 *
 * Photos are uploaded as compressed thumbnails (max 800px dimension, 80% JPEG quality)
 * to minimize bandwidth and storage costs while maintaining visual quality for preview.
 *
 * ## Usage
 *
 * ```kotlin
 * val service = PhotoSyncService(context)
 * service.startSync()  // Starts ContentObserver and initial sync
 *
 * // Manual sync trigger
 * service.syncRecentPhotos()
 *
 * // Force re-sync all photos
 * service.forceSync()
 *
 * // Cleanup
 * service.stopSync()
 * ```
 *
 * ## Required Permissions
 *
 * - `READ_MEDIA_IMAGES` (Android 13+) or `READ_EXTERNAL_STORAGE` (older)
 *
 * @param context Application context for ContentResolver and system services
 */
class PhotoSyncService(context: Context) {

    // -------------------------------------------------------------------------
    // Service Dependencies and State
    // -------------------------------------------------------------------------

    /** Application context for ContentResolver and system services */
    private val context: Context = context.applicationContext

    /** VPS Client for API calls */
    private val vpsClient = VPSClient.getInstance(this.context)

    /** HTTP client for uploads */
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Unified identity manager for cross-platform user identification */
    private val unifiedIdentityManager = UnifiedIdentityManager.getInstance(context)

    /** MediaStore observer for detecting new photos */
    private var contentObserver: ContentObserver? = null

    /** Coroutine scope for async operations with SupervisorJob for independent failure handling */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // -------------------------------------------------------------------------
    // Duplicate Prevention State
    // -------------------------------------------------------------------------

    /**
     * Set of MediaStore photo IDs that have already been synced.
     * Loaded from VPS on startup and updated as photos are synced.
     * Prevents re-uploading the same photos on app restart.
     */
    private val syncedPhotoIds = mutableSetOf<Long>()

    /** Flag indicating whether [syncedPhotoIds] has been loaded from VPS */
    private var syncedIdsLoaded = false

    /** Timestamp of last sync operation (for debugging and future use) */
    private var lastSyncTimestamp: Long = 0

    // -------------------------------------------------------------------------
    // Companion Object: Constants and Configuration
    // -------------------------------------------------------------------------

    companion object {
        private const val TAG = "PhotoSyncService"

        /**
         * Maximum dimension (width or height) for generated thumbnails.
         * Larger images are scaled down proportionally.
         */
        private const val MAX_THUMBNAIL_SIZE = 800

        /**
         * JPEG compression quality for thumbnails (0-100).
         * 80 provides good visual quality with significant size reduction.
         */
        private const val THUMBNAIL_QUALITY = 80

        /**
         * Maximum number of recent photos to sync and keep in VPS.
         * Older photos beyond this limit are cleaned up.
         */
        private const val MAX_PHOTOS_TO_SYNC = 20

        /**
         * Debounce delay for sync operations in milliseconds.
         * Prevents rapid-fire syncs when multiple photos are added quickly.
         */
        private const val SYNC_DEBOUNCE_MS = 2000L
    }

    // -------------------------------------------------------------------------
    // REGION: Premium Subscription Validation
    // -------------------------------------------------------------------------

    /**
     * Check if the current user has a paid subscription (required for photo sync).
     *
     * Queries the user's subscription data from VPS to determine
     * access level. Photo sync is only available to paying subscribers.
     *
     * ## Subscription Tiers with Access
     *
     * - **lifetime** - Permanent access
     * - **3year** - 3-year plan, permanent access
     * - **monthly** - Access while subscription active
     * - **yearly** - Access while subscription active
     * - **paid** - Legacy paid status
     *
     * ## Tiers WITHOUT Access
     *
     * - **free** - No photo sync
     * - **trial** - No photo sync (trial is for core features only)
     *
     * @return true if user has premium access, false otherwise
     */
    private suspend fun hasPremiumAccess(): Boolean {
        if (!vpsClient.isAuthenticated) return false

        return try {
            val subscription = vpsClient.getUserSubscription()
            val planRaw = (subscription["plan"] as? String)?.lowercase()
            val planExpiresAt = subscription["planExpiresAt"] as? Long
            val now = System.currentTimeMillis()

            when (planRaw) {
                "lifetime", "3year" -> true
                "monthly", "yearly", "paid" -> planExpiresAt?.let { it > now } ?: true
                else -> false // Trial users and free users don't have access
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking premium status", e)
            false
        }
    }

    // -------------------------------------------------------------------------
    // REGION: Data Classes
    // -------------------------------------------------------------------------

    /**
     * Data class for photo metadata stored in VPS.
     *
     * Represents the synchronized photo information available to desktop clients.
     *
     * @property id VPS document ID (UUID)
     * @property originalId Android MediaStore _ID (for duplicate detection)
     * @property fileName Generated filename (typically UUID.jpg)
     * @property dateTaken Timestamp when photo was taken/added
     * @property thumbnailUrl Presigned URL for thumbnail (if available)
     * @property width Original photo width
     * @property height Original photo height
     * @property size Original file size in bytes
     * @property mimeType Original MIME type (e.g., "image/jpeg")
     * @property syncedAt Timestamp when photo was synced to VPS
     */
    data class PhotoMetadata(
        val id: String,
        val originalId: Long,
        val fileName: String,
        val dateTaken: Long,
        val thumbnailUrl: String,
        val width: Int,
        val height: Int,
        val size: Long,
        val mimeType: String,
        val syncedAt: Long
    )

    // -------------------------------------------------------------------------
    // REGION: Service Lifecycle Management
    // -------------------------------------------------------------------------

    /**
     * Start photo sync - monitors for new photos and performs initial sync.
     *
     * ## Startup Sequence
     *
     * 1. Wait 3 seconds for app initialization
     * 2. Check premium subscription status
     * 3. If premium: register ContentObserver, cleanup duplicates, sync photos
     * 4. If not premium: log warning and return (no-op)
     *
     * ## Premium Requirement
     *
     * Photo sync is a premium-only feature. Free and trial users will see
     * a log message but no error is thrown - the service simply doesn't activate.
     *
     * Call this when:
     * - App starts and user is authenticated
     * - Desktop sync feature is enabled
     * - After [stopSync] to resume
     */
    fun startSync() {
        Log.d(TAG, "Starting photo sync")

        // Sync recent photos on startup (with premium check)
        scope.launch {
            delay(3000) // Wait for app to fully initialize

            // Check premium access before syncing
            if (!hasPremiumAccess()) {
                Log.w(TAG, "Photo sync requires premium subscription - skipping")
                return@launch
            }

            registerContentObserver()

            // Clean up any existing duplicates first
            cleanupDuplicates()

            syncRecentPhotos().getOrNull() // Internal call - result ignored
        }
    }

    /**
     * Stop photo sync and release all resources.
     *
     * Unregisters the ContentObserver and cancels the coroutine scope.
     * Call this when:
     * - User signs out
     * - Desktop sync feature is disabled
     * - App is being destroyed
     */
    fun stopSync() {
        Log.d(TAG, "Stopping photo sync")
        unregisterContentObserver()
        scope.cancel()
    }

    // -------------------------------------------------------------------------
    // REGION: ContentObserver Management
    // -------------------------------------------------------------------------

    /**
     * Register a ContentObserver to monitor MediaStore for new photos.
     *
     * The observer monitors `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` for changes.
     * When changes are detected, a debounced sync is triggered after [SYNC_DEBOUNCE_MS].
     *
     * ## Battery Optimization
     *
     * ContentObserver is more efficient than polling because:
     * 1. System notifies us only when changes occur
     * 2. No wake-up when device is idle and no photos are taken
     * 3. Single registration covers all photo additions/changes
     *
     * ## Handler Configuration
     *
     * Observer runs on main looper to ensure proper ContentResolver callback delivery.
     * Actual sync work is dispatched to [Dispatchers.IO] via coroutine scope.
     */
    private fun registerContentObserver() {
        contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                Log.d(TAG, "Media changed: $uri")

                // Debounce syncs
                scope.launch {
                    delay(SYNC_DEBOUNCE_MS)
                    syncRecentPhotos().getOrNull() // Internal call - result ignored
                }
            }
        }

        context.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            contentObserver!!
        )

        Log.d(TAG, "Content observer registered")
    }

    private fun unregisterContentObserver() {
        contentObserver?.let {
            context.contentResolver.unregisterContentObserver(it)
        }
        contentObserver = null
    }

    // -------------------------------------------------------------------------
    // REGION: Duplicate Prevention
    // -------------------------------------------------------------------------

    /**
     * Load already synced photo IDs from VPS to prevent duplicate uploads.
     *
     * Called at the start of each sync operation to ensure [syncedPhotoIds]
     * contains all photos already synced. This prevents re-uploading
     * photos that were synced in a previous session.
     */
    private suspend fun loadSyncedPhotoIds() {
        if (syncedIdsLoaded) return

        try {
            Log.d(TAG, "Loading synced photo IDs from VPS...")

            val ids = vpsClient.getSyncedPhotoIds()
            syncedPhotoIds.addAll(ids)

            syncedIdsLoaded = true
            Log.d(TAG, "Loaded ${syncedPhotoIds.size} synced photo IDs from VPS")

        } catch (e: Exception) {
            Log.e(TAG, "Error loading synced photo IDs", e)
        }
    }

    // -------------------------------------------------------------------------
    // REGION: Photo Sync Operations
    // -------------------------------------------------------------------------

    /**
     * Sync recent photos to VPS/R2.
     *
     * Main sync entry point. Validates premium access, loads existing synced IDs,
     * queries recent photos, uploads new ones, and cleans up old photos.
     *
     * ## Sync Flow
     *
     * 1. Verify premium subscription
     * 2. Load synced IDs from VPS (if not already loaded)
     * 3. Query [MAX_PHOTOS_TO_SYNC] most recent photos from MediaStore
     * 4. For each photo not in [syncedPhotoIds]:
     *    - Create thumbnail
     *    - Upload to R2
     *    - Update VPS metadata
     * 5. Clean up photos beyond limit
     *
     * ## Return Values
     *
     * - `Result.success("Photo sync completed successfully! N photos synced.")`
     * - `Result.failure(Exception("Photo sync requires a premium subscription..."))`
     * - `Result.failure(Exception("User authentication required"))`
     * - `Result.failure(Exception("Photo sync failed: ..."))`
     *
     * @return Result indicating success with message or failure with exception
     */
    suspend fun syncRecentPhotos(): Result<String> {
        var syncedCount = 0

        try {
            // Double-check premium access before syncing
            if (!hasPremiumAccess()) {
                Log.w(TAG, "Photo sync requires premium subscription")
                return Result.failure(Exception("Photo sync requires a premium subscription. Please upgrade to Pro to sync photos."))
            }

            if (!vpsClient.isAuthenticated) {
                return Result.failure(Exception("User authentication required"))
            }

            Log.d(TAG, "Syncing recent photos...")

            // Load already synced IDs from VPS first to prevent duplicates
            loadSyncedPhotoIds()

            val photos = getRecentPhotos(MAX_PHOTOS_TO_SYNC)
            Log.d(TAG, "Found ${photos.size} recent photos")

            for (photo in photos) {
                if (!syncedPhotoIds.contains(photo.id)) {
                    uploadPhoto(photo)
                    syncedPhotoIds.add(photo.id)
                    syncedCount++
                }
            }

            // Clean up old photos from VPS
            cleanupOldPhotos()

        } catch (e: Exception) {
            Log.e(TAG, "Error syncing photos", e)
            return Result.failure(Exception("Photo sync failed: ${e.message}"))
        }

        Log.d(TAG, "Photo sync completed successfully")
        return Result.success("Photo sync completed successfully! $syncedCount photos synced.")
    }

    // -------------------------------------------------------------------------
    // REGION: MediaStore Query Operations
    // -------------------------------------------------------------------------

    /**
     * Query recent photos from the device's MediaStore.
     *
     * Queries `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` for the most
     * recent photos, sorted by DATE_TAKEN descending.
     *
     * ## Projection Columns
     *
     * - _ID: Unique MediaStore identifier
     * - DISPLAY_NAME: File name
     * - DATE_TAKEN: When photo was taken (camera timestamp)
     * - DATE_ADDED: When file was added to MediaStore (fallback)
     * - WIDTH/HEIGHT: Image dimensions
     * - SIZE: File size in bytes
     * - MIME_TYPE: Content type
     *
     * ## Date Handling
     *
     * Uses DATE_TAKEN when available, falls back to DATE_ADDED (converted
     * from seconds to milliseconds) for photos without EXIF data.
     *
     * @param limit Maximum number of photos to return
     * @return List of [LocalPhoto] objects with metadata and content URIs
     */
    private fun getRecentPhotos(limit: Int): List<LocalPhoto> {
        val photos = mutableListOf<LocalPhoto>()

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE
        )

        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"

        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateTakenColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)

                var count = 0
                while (cursor.moveToNext() && count < limit) {
                    val id = cursor.getLong(idColumn)
                    val name = cursor.getString(nameColumn) ?: "photo_$id.jpg"
                    val dateTaken = cursor.getLong(dateTakenColumn)
                    val dateAdded = cursor.getLong(dateAddedColumn) * 1000 // Convert to ms
                    val width = cursor.getInt(widthColumn)
                    val height = cursor.getInt(heightColumn)
                    val size = cursor.getLong(sizeColumn)
                    val mimeType = cursor.getString(mimeColumn) ?: "image/jpeg"

                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    )

                    photos.add(
                        LocalPhoto(
                            id = id,
                            name = name,
                            dateTaken = if (dateTaken > 0) dateTaken else dateAdded,
                            width = width,
                            height = height,
                            size = size,
                            mimeType = mimeType,
                            contentUri = contentUri
                        )
                    )
                    count++
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying photos", e)
        }

        return photos
    }

    // -------------------------------------------------------------------------
    // REGION: R2 Upload Operations
    // -------------------------------------------------------------------------

    /**
     * Upload a photo thumbnail to Cloudflare R2 storage.
     *
     * ## Upload Flow
     *
     * 1. Create thumbnail from photo URI via [createThumbnail]
     * 2. Compress to JPEG bytes via [compressThumbnail]
     * 3. Get presigned upload URL from VPS API
     * 4. Upload directly to R2 via HTTP PUT
     * 5. Confirm upload via VPS API (stores metadata)
     *
     * @param photo Local photo metadata with content URI
     */
    private suspend fun uploadPhoto(photo: LocalPhoto) {
        try {
            Log.d(TAG, "Uploading photo: ${photo.name}")

            // Create thumbnail
            val thumbnail = createThumbnail(photo.contentUri) ?: return
            val thumbnailBytes = compressThumbnail(thumbnail)

            val photoId = UUID.randomUUID().toString()
            val fileName = "$photoId.jpg"

            // Step 1: Get presigned upload URL from VPS
            val uploadUrlResponse = vpsClient.getUploadUrl(
                fileName = fileName,
                contentType = "image/jpeg",
                fileSize = thumbnailBytes.size.toLong(),
                transferType = "photo"
            )

            val uploadUrl = uploadUrlResponse.uploadUrl
            val r2Key = uploadUrlResponse.fileKey

            // Step 2: Upload directly to R2 via presigned URL using OkHttp
            val uploaded = withContext(Dispatchers.IO) {
                uploadToR2WithOkHttp(uploadUrl, thumbnailBytes, "image/jpeg")
            }

            if (!uploaded) {
                Log.e(TAG, "Failed to upload photo to R2: ${photo.name}")
                return
            }

            // Step 3: Confirm upload via VPS API (this also stores metadata)
            val photoMetadata = mapOf(
                "originalId" to photo.id,
                "dateTaken" to photo.dateTaken,
                "width" to photo.width,
                "height" to photo.height,
                "size" to photo.size,
                "mimeType" to photo.mimeType
            )

            vpsClient.confirmPhotoUpload(
                fileId = photoId,
                r2Key = r2Key,
                fileName = fileName,
                fileSize = thumbnailBytes.size,
                photoMetadata = photoMetadata
            )

            Log.d(TAG, "Photo uploaded successfully to R2: ${photo.name} (key: $r2Key)")

        } catch (e: Exception) {
            Log.e(TAG, "Error uploading photo: ${photo.name}", e)
        }
    }

    /**
     * Upload raw bytes directly to Cloudflare R2 via presigned URL using OkHttp.
     *
     * Performs a simple HTTP PUT request to the presigned URL with the
     * provided content type and data.
     *
     * @param uploadUrl Presigned PUT URL from VPS API
     * @param data Raw bytes to upload (thumbnail JPEG data)
     * @param contentType MIME type for Content-Type header
     * @return true if upload succeeded (2xx response), false otherwise
     */
    private fun uploadToR2WithOkHttp(uploadUrl: String, data: ByteArray, contentType: String): Boolean {
        return try {
            val requestBody = data.toRequestBody(contentType.toMediaType())
            val request = Request.Builder()
                .url(uploadUrl)
                .put(requestBody)
                .build()

            httpClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading to R2", e)
            false
        }
    }

    // -------------------------------------------------------------------------
    // REGION: Thumbnail Generation
    // -------------------------------------------------------------------------

    /**
     * Create a thumbnail bitmap from an image content URI.
     *
     * Uses BitmapFactory with inSampleSize to efficiently decode large images
     * without loading full resolution into memory.
     *
     * ## Algorithm
     *
     * 1. First pass: Decode with inJustDecodeBounds=true to get dimensions
     * 2. Calculate sample size to get image within [MAX_THUMBNAIL_SIZE]
     * 3. Second pass: Decode with calculated sample size
     *
     * ## Memory Optimization
     *
     * Using inSampleSize avoids loading full-resolution images (which can be
     * 20+ MB for modern phone cameras) into memory. Sample size of 2 loads
     * 1/4 the pixels, sample size of 4 loads 1/16, etc.
     *
     * @param uri Content URI of the image (from MediaStore)
     * @return Thumbnail bitmap or null if decoding fails
     */
    private fun createThumbnail(uri: Uri): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, options)
            }

            // Calculate sample size
            val width = options.outWidth
            val height = options.outHeight
            var sampleSize = 1

            if (width > MAX_THUMBNAIL_SIZE || height > MAX_THUMBNAIL_SIZE) {
                val halfWidth = width / 2
                val halfHeight = height / 2

                while ((halfWidth / sampleSize) >= MAX_THUMBNAIL_SIZE &&
                    (halfHeight / sampleSize) >= MAX_THUMBNAIL_SIZE) {
                    sampleSize *= 2
                }
            }

            // Decode with sample size
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }

            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, decodeOptions)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating thumbnail", e)
            null
        }
    }

    /**
     * Compress a bitmap to JPEG format with configured quality.
     *
     * Uses [THUMBNAIL_QUALITY] (80%) which provides good visual quality
     * while achieving significant file size reduction.
     *
     * @param bitmap Source bitmap to compress
     * @return JPEG-compressed byte array
     */
    private fun compressThumbnail(bitmap: Bitmap): ByteArray {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, outputStream)
        return outputStream.toByteArray()
    }

    // -------------------------------------------------------------------------
    // REGION: Cleanup Operations
    // -------------------------------------------------------------------------

    /**
     * Clean up old photos from R2 and VPS, keeping only the most recent.
     *
     * When the number of synced photos exceeds [MAX_PHOTOS_TO_SYNC], this
     * method deletes the oldest photos (by syncedAt timestamp) from both
     * R2 storage and VPS database.
     *
     * ## Deletion Order
     *
     * Photos are ordered by syncedAt ascending, so oldest synced photos
     * are deleted first regardless of when they were originally taken.
     *
     * ## Cleanup Flow
     *
     * 1. Query all photos ordered by syncedAt
     * 2. Calculate how many to delete (count - MAX_PHOTOS_TO_SYNC)
     * 3. For each photo to delete:
     *    a. Delete from R2 via VPS API
     *    b. Delete metadata from VPS
     */
    private suspend fun cleanupOldPhotos() {
        try {
            val photos = vpsClient.getPhotos()
            val photoCount = photos.size

            if (photoCount > MAX_PHOTOS_TO_SYNC) {
                // Sort by syncedAt ascending (oldest first)
                val sortedPhotos = photos.sortedBy {
                    (it["syncedAt"] as? Number)?.toLong() ?: 0L
                }

                val photosToDelete = photoCount - MAX_PHOTOS_TO_SYNC
                var deleted = 0

                for (photo in sortedPhotos) {
                    if (deleted >= photosToDelete) break

                    val photoId = photo["id"] as? String ?: continue
                    val r2Key = photo["r2Key"] as? String

                    // Delete from R2 and VPS database
                    try {
                        vpsClient.deletePhoto(photoId, r2Key)
                        deleted++
                    } catch (e: Exception) {
                        Log.w(TAG, "Error deleting photo: $photoId", e)
                    }
                }

                Log.d(TAG, "Cleaned up $deleted old photos")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up old photos", e)
        }
    }

    // -------------------------------------------------------------------------
    // REGION: Manual Sync Operations
    // -------------------------------------------------------------------------

    /**
     * Force a complete re-sync of recent photos.
     *
     * Clears the local [syncedPhotoIds] cache and reloads from VPS,
     * then performs a full sync. Use this when:
     * - User manually requests a refresh
     * - Suspected data inconsistency
     * - After clearing synced data
     */
    suspend fun forceSync() {
        syncedPhotoIds.clear()
        syncedIdsLoaded = false  // Force reload from VPS
        syncRecentPhotos().getOrNull() // Internal call - result ignored
    }

    /**
     * Clean up duplicate photos in R2 and VPS database.
     *
     * Finds photos with the same originalId (MediaStore _ID) and keeps only
     * the most recently synced one, deleting older duplicates.
     *
     * ## Why Duplicates Occur
     *
     * - Race conditions during rapid photo taking
     * - App restart during sync operation
     * - Network failures causing retry uploads
     * - Multiple devices syncing same photo library (rare)
     *
     * ## Cleanup Algorithm
     *
     * 1. Query all photos from VPS
     * 2. Group by originalId
     * 3. For groups with >1 photo:
     *    a. Sort by syncedAt descending
     *    b. Keep the first (most recent)
     *    c. Delete all others from R2 and VPS
     *
     * ## Idempotency
     *
     * Safe to call multiple times - will only delete actual duplicates.
     */
    suspend fun cleanupDuplicates() {
        try {
            Log.d(TAG, "Cleaning up duplicate photos...")

            val photos = vpsClient.getPhotos()

            // Group photos by originalId, including r2Key for deletion
            data class PhotoInfo(val photoId: String, val syncedAt: Long, val r2Key: String?)
            val photosByOriginalId = mutableMapOf<Long, MutableList<PhotoInfo>>()

            for (photo in photos) {
                val photoId = photo["id"] as? String ?: continue
                val originalId = (photo["originalId"] as? Number)?.toLong() ?: continue
                val syncedAt = (photo["syncedAt"] as? Number)?.toLong() ?: 0L
                val r2Key = photo["r2Key"] as? String

                photosByOriginalId.getOrPut(originalId) { mutableListOf() }
                    .add(PhotoInfo(photoId, syncedAt, r2Key))
            }

            var deletedCount = 0

            // For each originalId with duplicates, keep only the most recent one
            for ((originalId, photosList) in photosByOriginalId) {
                if (photosList.size > 1) {
                    // Sort by syncedAt descending (most recent first)
                    val sorted = photosList.sortedByDescending { it.syncedAt }

                    // Delete all but the first (most recent)
                    for (i in 1 until sorted.size) {
                        val photoToDelete = sorted[i]

                        try {
                            vpsClient.deletePhoto(photoToDelete.photoId, photoToDelete.r2Key)
                            deletedCount++
                            Log.d(TAG, "Deleted duplicate photo: ${photoToDelete.photoId} (originalId: $originalId)")
                        } catch (e: Exception) {
                            Log.w(TAG, "Error deleting duplicate photo: ${photoToDelete.photoId}", e)
                        }
                    }
                }
            }

            Log.d(TAG, "Cleaned up $deletedCount duplicate photos")

        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up duplicates", e)
        }
    }

    // -------------------------------------------------------------------------
    // REGION: Internal Data Classes
    // -------------------------------------------------------------------------

    /**
     * Local photo metadata retrieved from MediaStore.
     *
     * Used internally to pass photo information between query and upload methods.
     *
     * @property id MediaStore _ID (unique identifier in device's photo library)
     * @property name Display name (filename)
     * @property dateTaken Timestamp when photo was taken
     * @property width Original image width in pixels
     * @property height Original image height in pixels
     * @property size File size in bytes
     * @property mimeType MIME type (e.g., "image/jpeg", "image/png")
     * @property contentUri Content URI for accessing the photo via ContentResolver
     */
    private data class LocalPhoto(
        val id: Long,
        val name: String,
        val dateTaken: Long,
        val width: Int,
        val height: Int,
        val size: Long,
        val mimeType: String,
        val contentUri: Uri
    )
}
