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
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.functions.FirebaseFunctions
import com.phoneintegration.app.auth.UnifiedIdentityManager
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.*

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
 * 3. **R2 Upload Pipeline** - Uploads via presigned URLs from Cloud Functions
 * 4. **Firebase Metadata** - Stores photo metadata for desktop client consumption
 *
 * ## Sync Flow
 *
 * 1. [ContentObserver] detects new photo in MediaStore
 * 2. Premium access is verified via [hasPremiumAccess]
 * 3. Recent photos are queried (limited to [MAX_PHOTOS_TO_SYNC])
 * 4. For each new photo not in [syncedPhotoIds]:
 *    a. Create thumbnail via [createThumbnail]
 *    b. Compress to JPEG at [THUMBNAIL_QUALITY]
 *    c. Get presigned upload URL from `getR2UploadUrl`
 *    d. Upload to R2 via [uploadToR2]
 *    e. Confirm upload via `confirmR2Upload`
 * 5. Old photos beyond limit are cleaned up from R2 and Firebase
 *
 * ## Firebase Interaction Patterns
 *
 * **Photo Metadata Path:**
 * `users/{userId}/photos/{photoId}`
 *
 * **Metadata Structure:**
 * ```
 * {
 *   originalId: 12345,          // Android MediaStore ID
 *   dateTaken: 1704067200000,   // Unix timestamp
 *   width: 800,                  // Thumbnail width
 *   height: 600,                 // Thumbnail height
 *   size: 45678,                 // Original file size
 *   mimeType: "image/jpeg",
 *   r2Key: "photos/userId/...",
 *   syncedAt: 1704067300000
 * }
 * ```
 *
 * **Cloud Functions Used:**
 * - `getR2UploadUrl` - Get presigned PUT URL for thumbnail upload
 * - `confirmR2Upload` - Confirm upload and store metadata
 * - `deleteR2File` - Clean up old thumbnails from R2
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
 * - IDs are loaded from Firebase on startup via [loadSyncedPhotoIds]
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
 * Service that syncs recent photos from Android to Firebase for display on macOS.
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

    /** Firebase Realtime Database for photo metadata storage */
    private val database = FirebaseDatabase.getInstance()

    /** Firebase Cloud Functions for R2 presigned URL generation */
    private val functions = FirebaseFunctions.getInstance()

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
     * Loaded from Firebase on startup and updated as photos are synced.
     * Prevents re-uploading the same photos on app restart.
     */
    private val syncedPhotoIds = mutableSetOf<Long>()

    /** Flag indicating whether [syncedPhotoIds] has been loaded from Firebase */
    private var syncedIdsLoaded = false

    /** Timestamp of last sync operation (for debugging and future use) */
    private var lastSyncTimestamp: Long = 0

    // -------------------------------------------------------------------------
    // Companion Object: Constants and Configuration
    // -------------------------------------------------------------------------

    companion object {
        private const val TAG = "PhotoSyncService"

        /** Firebase Database path for photo metadata */
        private const val PHOTOS_PATH = "photos"

        /** Firebase Database root path for user data */
        private const val USERS_PATH = "users"

        /** Firebase Database path for usage/subscription data */
        private const val USAGE_PATH = "usage"

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
         * Maximum number of recent photos to sync and keep in Firebase.
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
     * Queries the user's usage/subscription data in Firebase to determine
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
     * ## Firebase Path
     *
     * `users/{userId}/usage`
     * - `plan`: Subscription tier name
     * - `planExpiresAt`: Expiration timestamp (for monthly/yearly)
     *
     * @return true if user has premium access, false otherwise
     */
    private suspend fun hasPremiumAccess(): Boolean {
        val userId = unifiedIdentityManager.getUnifiedUserIdSync() ?: return false

        return try {
            val usageRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child(USAGE_PATH)

            val snapshot = usageRef.get().await()
            val planRaw = snapshot.child("plan").getValue(String::class.java)?.lowercase()
            val planExpiresAt = snapshot.child("planExpiresAt").getValue(Long::class.java)
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
     * Data class for photo metadata stored in Firebase.
     *
     * Represents the synchronized photo information available to desktop clients.
     *
     * @property id Firebase document ID (UUID)
     * @property originalId Android MediaStore _ID (for duplicate detection)
     * @property fileName Generated filename (typically UUID.jpg)
     * @property dateTaken Timestamp when photo was taken/added
     * @property thumbnailUrl Presigned URL for thumbnail (if available)
     * @property width Original photo width
     * @property height Original photo height
     * @property size Original file size in bytes
     * @property mimeType Original MIME type (e.g., "image/jpeg")
     * @property syncedAt Timestamp when photo was synced to Firebase
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
     * 1. Go online with Firebase
     * 2. Wait 3 seconds for app initialization
     * 3. Check premium subscription status
     * 4. If premium: register ContentObserver, cleanup duplicates, sync photos
     * 5. If not premium: log warning and return (no-op)
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
        database.goOnline()

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
     * Load already synced photo IDs from Firebase to prevent duplicate uploads.
     *
     * Called at the start of each sync operation to ensure [syncedPhotoIds]
     * contains all photos already in Firebase. This prevents re-uploading
     * photos that were synced in a previous session.
     *
     * ## Firebase Path
     *
     * Reads all children from `users/{userId}/photos` and extracts `originalId`
     * (the MediaStore _ID) from each photo document.
     *
     * @param userId Current user's Firebase UID
     */
    private suspend fun loadSyncedPhotoIds(userId: String) {
        if (syncedIdsLoaded) return

        try {
            Log.d(TAG, "Loading synced photo IDs from Firebase...")

            val photosRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child(PHOTOS_PATH)

            val snapshot = photosRef.get().await()

            for (child in snapshot.children) {
                val originalId = child.child("originalId").getValue(Long::class.java)
                if (originalId != null) {
                    syncedPhotoIds.add(originalId)
                }
            }

            syncedIdsLoaded = true
            Log.d(TAG, "Loaded ${syncedPhotoIds.size} synced photo IDs from Firebase")

        } catch (e: Exception) {
            Log.e(TAG, "Error loading synced photo IDs", e)
        }
    }

    // -------------------------------------------------------------------------
    // REGION: Photo Sync Operations
    // -------------------------------------------------------------------------

    /**
     * Sync recent photos to Firebase/R2.
     *
     * Main sync entry point. Validates premium access, loads existing synced IDs,
     * queries recent photos, uploads new ones, and cleans up old photos.
     *
     * ## Sync Flow
     *
     * 1. Verify premium subscription
     * 2. Load synced IDs from Firebase (if not already loaded)
     * 3. Query [MAX_PHOTOS_TO_SYNC] most recent photos from MediaStore
     * 4. For each photo not in [syncedPhotoIds]:
     *    - Create thumbnail
     *    - Upload to R2
     *    - Update Firebase metadata
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

            val userId = unifiedIdentityManager.getUnifiedUserIdSync() ?: return Result.failure(Exception("User authentication required"))

            Log.d(TAG, "Syncing recent photos...")

            // Load already synced IDs from Firebase first to prevent duplicates
            loadSyncedPhotoIds(userId)

            val photos = getRecentPhotos(MAX_PHOTOS_TO_SYNC)
            Log.d(TAG, "Found ${photos.size} recent photos")

            for (photo in photos) {
                if (!syncedPhotoIds.contains(photo.id)) {
                    uploadPhoto(userId, photo)
                    syncedPhotoIds.add(photo.id)
                    syncedCount++
                }
            }

            // Clean up old photos from Firebase
            cleanupOldPhotos(userId)

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
     * 3. Get presigned upload URL from `getR2UploadUrl` Cloud Function
     * 4. Upload directly to R2 via HTTP PUT
     * 5. Confirm upload via `confirmR2Upload` (stores metadata in Firebase)
     *
     * ## Cloud Function: getR2UploadUrl
     *
     * **Request:**
     * ```
     * {
     *   fileName: "uuid.jpg",
     *   contentType: "image/jpeg",
     *   fileSize: 45678,
     *   transferType: "photo"
     * }
     * ```
     *
     * **Response:**
     * ```
     * {
     *   uploadUrl: "https://r2.cloudflarestorage.com/...",
     *   r2Key: "photos/userId/uuid.jpg",
     *   fileId: "uuid"
     * }
     * ```
     *
     * ## Cloud Function: confirmR2Upload
     *
     * **Request:**
     * ```
     * {
     *   fileId: "uuid",
     *   r2Key: "photos/userId/...",
     *   fileName: "uuid.jpg",
     *   fileSize: 45678,
     *   contentType: "image/jpeg",
     *   transferType: "photo",
     *   photoMetadata: { originalId, dateTaken, width, height, size, mimeType }
     * }
     * ```
     *
     * @param userId Current user's Firebase UID
     * @param photo Local photo metadata with content URI
     */
    private suspend fun uploadPhoto(userId: String, photo: LocalPhoto) {
        try {
            Log.d(TAG, "Uploading photo: ${photo.name}")

            // Create thumbnail
            val thumbnail = createThumbnail(photo.contentUri) ?: return
            val thumbnailBytes = compressThumbnail(thumbnail)

            val photoId = UUID.randomUUID().toString()
            val fileName = "$photoId.jpg"

            // Step 1: Get presigned upload URL from R2
            val uploadUrlData = hashMapOf(
                "fileName" to fileName,
                "contentType" to "image/jpeg",
                "fileSize" to thumbnailBytes.size,
                "transferType" to "photo"
            )

            val uploadUrlResult = functions
                .getHttpsCallable("getR2UploadUrl")
                .call(uploadUrlData)
                .await()

            @Suppress("UNCHECKED_CAST")
            val uploadResponse = uploadUrlResult.data as? Map<String, Any>
            val uploadUrl = uploadResponse?.get("uploadUrl") as? String
            val r2Key = uploadResponse?.get("fileKey") as? String  // Cloud Function returns "fileKey"
            val fileId = uploadResponse?.get("fileId") as? String

            if (uploadUrl == null || r2Key == null || fileId == null) {
                Log.e(TAG, "Failed to get R2 upload URL for photo: ${photo.name}")
                return
            }

            // Step 2: Upload directly to R2 via presigned URL
            val uploaded = withContext(Dispatchers.IO) {
                uploadToR2(uploadUrl, thumbnailBytes, "image/jpeg")
            }

            if (!uploaded) {
                Log.e(TAG, "Failed to upload photo to R2: ${photo.name}")
                return
            }

            // Step 3: Confirm upload with Cloud Function (this also stores metadata)
            val confirmData = hashMapOf(
                "fileId" to fileId,
                "r2Key" to r2Key,
                "fileName" to fileName,
                "fileSize" to thumbnailBytes.size,
                "contentType" to "image/jpeg",
                "transferType" to "photo",
                "photoMetadata" to hashMapOf(
                    "originalId" to photo.id,
                    "dateTaken" to photo.dateTaken,
                    "width" to photo.width,
                    "height" to photo.height,
                    "size" to photo.size,
                    "mimeType" to photo.mimeType
                )
            )

            functions
                .getHttpsCallable("confirmR2Upload")
                .call(confirmData)
                .await()

            Log.d(TAG, "Photo uploaded successfully to R2: ${photo.name} (key: $r2Key)")

        } catch (e: Exception) {
            Log.e(TAG, "Error uploading photo: ${photo.name}", e)
        }
    }

    /**
     * Upload raw bytes directly to Cloudflare R2 via presigned URL.
     *
     * Performs a simple HTTP PUT request to the presigned URL with the
     * provided content type and data.
     *
     * ## Network Configuration
     *
     * - Connect timeout: 30 seconds
     * - Read timeout: 60 seconds
     * - Accepts HTTP 2xx responses only
     *
     * @param uploadUrl Presigned PUT URL from getR2UploadUrl Cloud Function
     * @param data Raw bytes to upload (thumbnail JPEG data)
     * @param contentType MIME type for Content-Type header
     * @return true if upload succeeded (2xx response), false otherwise
     */
    private fun uploadToR2(uploadUrl: String, data: ByteArray, contentType: String): Boolean {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(uploadUrl)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "PUT"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", contentType)
            connection.setRequestProperty("Content-Length", data.size.toString())
            connection.connectTimeout = 30000
            connection.readTimeout = 60000

            connection.outputStream.use { outputStream ->
                outputStream.write(data)
                outputStream.flush()
            }

            val responseCode = connection.responseCode
            return responseCode in 200..299
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading to R2", e)
            return false
        } finally {
            connection?.disconnect()
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
     * Clean up old photos from R2 and Firebase, keeping only the most recent.
     *
     * When the number of synced photos exceeds [MAX_PHOTOS_TO_SYNC], this
     * method deletes the oldest photos (by syncedAt timestamp) from both
     * R2 storage and Firebase Database.
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
     *    a. Delete from R2 via `deleteR2File` Cloud Function
     *    b. Delete metadata from Firebase Database
     *
     * @param userId Current user's Firebase UID
     */
    private suspend fun cleanupOldPhotos(userId: String) {
        try {
            val photosRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child(PHOTOS_PATH)

            val snapshot = photosRef.orderByChild("syncedAt").get().await()
            val photoCount = snapshot.childrenCount

            if (photoCount > MAX_PHOTOS_TO_SYNC) {
                val photosToDelete = (photoCount - MAX_PHOTOS_TO_SYNC).toInt()
                var deleted = 0

                for (child in snapshot.children) {
                    if (deleted >= photosToDelete) break

                    val photoId = child.key ?: continue
                    val r2Key = child.child("r2Key").getValue(String::class.java)

                    // Delete from R2 if r2Key exists
                    if (r2Key != null) {
                        try {
                            val deleteData = hashMapOf("r2Key" to r2Key)
                            functions
                                .getHttpsCallable("deleteR2File")
                                .call(deleteData)
                                .await()
                        } catch (e: Exception) {
                            Log.w(TAG, "Error deleting photo from R2: $r2Key", e)
                        }
                    }

                    // Delete from Database
                    child.ref.removeValue().await()
                    deleted++
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
     * Clears the local [syncedPhotoIds] cache and reloads from Firebase,
     * then performs a full sync. Use this when:
     * - User manually requests a refresh
     * - Suspected data inconsistency
     * - After clearing Firebase data
     */
    suspend fun forceSync() {
        syncedPhotoIds.clear()
        syncedIdsLoaded = false  // Force reload from Firebase
        syncRecentPhotos().getOrNull() // Internal call - result ignored
    }

    /**
     * Clean up duplicate photos in R2 and Firebase Database.
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
     * 1. Query all photos from Firebase
     * 2. Group by originalId
     * 3. For groups with >1 photo:
     *    a. Sort by syncedAt descending
     *    b. Keep the first (most recent)
     *    c. Delete all others from R2 and Firebase
     *
     * ## Idempotency
     *
     * Safe to call multiple times - will only delete actual duplicates.
     */
    suspend fun cleanupDuplicates() {
        try {
            val userId = unifiedIdentityManager.getUnifiedUserIdSync() ?: return

            Log.d(TAG, "Cleaning up duplicate photos...")

            val photosRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child(PHOTOS_PATH)

            val snapshot = photosRef.get().await()

            // Group photos by originalId, including r2Key for deletion
            data class PhotoInfo(val photoId: String, val syncedAt: Long, val r2Key: String?)
            val photosByOriginalId = mutableMapOf<Long, MutableList<PhotoInfo>>()

            for (child in snapshot.children) {
                val photoId = child.key ?: continue
                val originalId = child.child("originalId").getValue(Long::class.java) ?: continue
                val syncedAt = child.child("syncedAt").getValue(Long::class.java) ?: 0L
                val r2Key = child.child("r2Key").getValue(String::class.java)

                photosByOriginalId.getOrPut(originalId) { mutableListOf() }
                    .add(PhotoInfo(photoId, syncedAt, r2Key))
            }

            var deletedCount = 0

            // For each originalId with duplicates, keep only the most recent one
            for ((originalId, photos) in photosByOriginalId) {
                if (photos.size > 1) {
                    // Sort by syncedAt descending (most recent first)
                    val sorted = photos.sortedByDescending { it.syncedAt }

                    // Delete all but the first (most recent)
                    for (i in 1 until sorted.size) {
                        val photoToDelete = sorted[i]

                        // Delete from R2 if r2Key exists
                        if (photoToDelete.r2Key != null) {
                            try {
                                val deleteData = hashMapOf("r2Key" to photoToDelete.r2Key)
                                functions
                                    .getHttpsCallable("deleteR2File")
                                    .call(deleteData)
                                    .await()
                            } catch (e: Exception) {
                                Log.w(TAG, "Error deleting duplicate photo from R2: ${photoToDelete.r2Key}", e)
                            }
                        }

                        // Delete from Database
                        photosRef.child(photoToDelete.photoId).removeValue().await()
                        deletedCount++

                        Log.d(TAG, "Deleted duplicate photo: ${photoToDelete.photoId} (originalId: $originalId)")
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
