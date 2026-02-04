package com.phoneintegration.app.desktop

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

/**
 * ClipboardSyncService.kt - Real-Time Clipboard Synchronization for SyncFlow
 *
 * This service enables seamless clipboard sharing between Android and macOS/desktop
 * clients using Firebase Realtime Database for instant synchronization.
 *
 * ## Architecture Overview
 *
 * The clipboard sync uses a bidirectional listener pattern:
 *
 * 1. **Local Listener** - [ClipboardManager.OnPrimaryClipChangedListener]
 *    monitors the Android clipboard for changes and syncs to Firebase
 *
 * 2. **Remote Listener** - Firebase [ValueEventListener] monitors the
 *    user's clipboard path and updates local clipboard when changed
 *
 * ## Firebase Interaction Pattern
 *
 * **Database Path:** `users/{userId}/clipboard`
 *
 * **Data Structure:**
 * ```json
 * {
 *   "text": "Clipboard content here",
 *   "timestamp": 1704067200000,
 *   "source": "android",  // or "macos"
 *   "type": "text"
 * }
 * ```
 *
 * ## Sync Flow
 *
 * **Android -> Desktop:**
 * 1. User copies text on Android
 * 2. OnPrimaryClipChangedListener fires
 * 3. Content validated (not empty, under size limit, not duplicate)
 * 4. Debounced write to Firebase with source="android"
 * 5. Desktop client receives via Firebase listener
 *
 * **Desktop -> Android:**
 * 1. User copies text on macOS
 * 2. macOS app writes to Firebase with source="macos"
 * 3. ValueEventListener fires on Android
 * 4. If source != "android" and timestamp is newer, update local clipboard
 * 5. User can paste on Android
 *
 * ## Loop Prevention
 *
 * Clipboard sync can create infinite loops if not handled carefully:
 * - Copy on Android -> Sync to Firebase -> Desktop receives
 * - Desktop syncs back -> Firebase listener fires on Android
 * - Android updates clipboard -> OnPrimaryClipChangedListener fires
 * - Repeat...
 *
 * This is prevented by:
 * 1. **Source checking** - Ignore updates from same device type
 * 2. **Content deduplication** - Track [lastSyncedContent] to skip unchanged content
 * 3. **Timestamp comparison** - Only accept newer content
 * 4. **Remote update flag** - [isUpdatingFromRemote] blocks local listener during remote updates
 *
 * ## Battery Optimization Considerations
 *
 * - Uses Firebase Realtime Database listeners (efficient long-polling)
 * - No polling or periodic wake-ups required
 * - Debounces local clipboard changes by [SYNC_DEBOUNCE_MS]
 * - Clipboard listener is system-managed (minimal overhead)
 *
 * ## Limitations
 *
 * - **Text only**: Currently only supports plain text (no images, files, rich text)
 * - **Size limit**: Content capped at [MAX_CLIPBOARD_LENGTH] (50KB)
 * - **Android background restrictions**: May not sync when app is backgrounded on newer Android versions
 *
 * ## Security Considerations
 *
 * - Clipboard content is stored in user's private Firebase path
 * - Data is encrypted in transit (Firebase TLS)
 * - Content is overwritten with each sync (not stored historically)
 * - Consider sensitive data implications (passwords, etc.)
 *
 * @see FileTransferService For file-based sync
 * @see PhotoSyncService For photo sync
 */

// =============================================================================
// REGION: ClipboardSyncService - Main Service Class
// =============================================================================

/**
 * Service that syncs clipboard content between Android and macOS/desktop.
 *
 * Provides real-time text clipboard synchronization using Firebase Realtime Database.
 * Changes on either platform are reflected on the other within seconds.
 *
 * ## Usage
 *
 * ```kotlin
 * val service = ClipboardSyncService(context)
 * service.startSync()  // Starts bidirectional sync
 *
 * // Manual sync if needed
 * service.syncNow()
 *
 * // Get current clipboard content
 * val content = service.getCurrentClipboard()
 *
 * // Cleanup
 * service.stopSync()
 * ```
 *
 * ## Required Permissions
 *
 * No special permissions required for clipboard access, but clipboard content
 * may be restricted on Android 10+ when app is in background.
 *
 * @param context Application context for ClipboardManager access
 */
class ClipboardSyncService(context: Context) {

    // -------------------------------------------------------------------------
    // Service Dependencies and State
    // -------------------------------------------------------------------------

    /** Application context for system service access */
    private val appContext: Context = context.applicationContext

    /** Firebase Auth for user identification */
    private val auth = FirebaseAuth.getInstance()

    /** Firebase Realtime Database for clipboard data */
    private val database = FirebaseDatabase.getInstance()

    /** System ClipboardManager for local clipboard access */
    private val clipboardManager = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    /** Listener for local clipboard changes (Android -> Firebase) */
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null

    /** Listener for remote clipboard changes (Firebase -> Android) */
    private var clipboardValueListener: ValueEventListener? = null

    /** Coroutine scope for async operations */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // -------------------------------------------------------------------------
    // Loop Prevention State
    // -------------------------------------------------------------------------

    /**
     * Last synced clipboard content for deduplication.
     * Prevents syncing the same content repeatedly.
     */
    private var lastSyncedContent: String? = null

    /**
     * Timestamp of last synced content.
     * Used to determine if remote content is newer than local.
     */
    private var lastSyncedTimestamp: Long = 0

    /**
     * Flag indicating we're currently updating from a remote change.
     * When true, the local clipboard listener ignores changes to prevent loops.
     */
    private var isUpdatingFromRemote = false

    // -------------------------------------------------------------------------
    // Companion Object: Constants
    // -------------------------------------------------------------------------

    companion object {
        private const val TAG = "ClipboardSyncService"

        /** Firebase Database path for clipboard data */
        private const val CLIPBOARD_PATH = "clipboard"

        /** Firebase Database root path for user data */
        private const val USERS_PATH = "users"

        /**
         * Maximum clipboard content length in characters.
         * Large content is skipped to prevent Firebase write issues and
         * excessive bandwidth usage.
         */
        private const val MAX_CLIPBOARD_LENGTH = 50000 // 50KB max for text

        /**
         * Debounce delay for clipboard sync in milliseconds.
         * Prevents rapid-fire syncs during text editing operations.
         */
        private const val SYNC_DEBOUNCE_MS = 500L
    }

    // -------------------------------------------------------------------------
    // REGION: Data Classes
    // -------------------------------------------------------------------------

    /**
     * Data class representing clipboard content for sync.
     *
     * Maps directly to the Firebase Database structure.
     *
     * @property text The clipboard text content
     * @property timestamp Unix timestamp when content was copied
     * @property source Origin device type: "android" or "macos"
     * @property type Content type (currently only "text" supported)
     */
    data class ClipboardContent(
        val text: String,
        val timestamp: Long,
        val source: String, // "android" or "macos"
        val type: String = "text" // Currently only "text" supported
    )

    // -------------------------------------------------------------------------
    // REGION: Service Lifecycle Management
    // -------------------------------------------------------------------------

    /**
     * Start clipboard sync - enables bidirectional synchronization.
     *
     * Registers both local clipboard listener and Firebase listener.
     * Ensures clean state by stopping any existing listeners first.
     *
     * Call this when:
     * - App starts and user is authenticated
     * - Desktop sync feature is enabled
     * - After [stopSync] to resume
     */
    fun startSync() {
        Log.d(TAG, "Starting clipboard sync")
        database.goOnline()
        stopListeningForLocalClipboard()
        stopListeningForRemoteClipboard()
        startListeningForLocalClipboard()
        startListeningForRemoteClipboard()
    }

    /**
     * Stop clipboard sync and release all resources.
     *
     * Removes both local and remote listeners and cancels the coroutine scope.
     * Call this when:
     * - User signs out
     * - Desktop sync feature is disabled
     * - App is being destroyed
     */
    fun stopSync() {
        Log.d(TAG, "Stopping clipboard sync")
        stopListeningForLocalClipboard()
        stopListeningForRemoteClipboard()
        scope.cancel()
    }

    // -------------------------------------------------------------------------
    // REGION: Local Clipboard Monitoring (Android -> Firebase)
    // -------------------------------------------------------------------------

    /**
     * Register listener for local Android clipboard changes.
     *
     * Creates an [OnPrimaryClipChangedListener] that fires whenever the user
     * copies content on the Android device. The listener:
     *
     * 1. Checks if we're updating from remote (to prevent loops)
     * 2. Extracts text from the clipboard
     * 3. Validates content (not empty, under size limit)
     * 4. Checks for duplicate content
     * 5. Debounces and syncs to Firebase
     *
     * ## Loop Prevention
     *
     * The [isUpdatingFromRemote] flag is checked first. When we update the
     * local clipboard from a remote change, this prevents immediately syncing
     * that content back to Firebase.
     *
     * ## Content Validation
     *
     * - Empty/blank text is ignored
     * - Content over [MAX_CLIPBOARD_LENGTH] is skipped with a log warning
     * - Duplicate content (same as [lastSyncedContent] within 2 seconds) is skipped
     */
    private fun startListeningForLocalClipboard() {
        stopListeningForLocalClipboard()
        clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
            // Ignore if we're currently updating from remote
            if (isUpdatingFromRemote) {
                Log.d(TAG, "Ignoring clipboard change - updating from remote")
                return@OnPrimaryClipChangedListener
            }

            val clipData = clipboardManager.primaryClip
            if (clipData == null || clipData.itemCount == 0) return@OnPrimaryClipChangedListener

            val item = clipData.getItemAt(0)
            val text = item.text?.toString()

            if (text.isNullOrBlank()) return@OnPrimaryClipChangedListener
            if (text.length > MAX_CLIPBOARD_LENGTH) {
                Log.w(TAG, "Clipboard content too large (${text.length} chars), skipping sync")
                return@OnPrimaryClipChangedListener
            }

            // Debounce and check if content changed
            val now = System.currentTimeMillis()
            if (text == lastSyncedContent && now - lastSyncedTimestamp < 2000) {
                Log.d(TAG, "Clipboard content unchanged, skipping sync")
                return@OnPrimaryClipChangedListener
            }

            scope.launch {
                delay(SYNC_DEBOUNCE_MS) // Debounce
                syncClipboardToFirebase(text)
            }
        }

        clipboardManager.addPrimaryClipChangedListener(clipboardListener)
        Log.d(TAG, "Local clipboard listener registered")
    }

    private fun stopListeningForLocalClipboard() {
        clipboardListener?.let {
            clipboardManager.removePrimaryClipChangedListener(it)
        }
        clipboardListener = null
    }

    // -------------------------------------------------------------------------
    // REGION: Remote Clipboard Monitoring (Firebase -> Android)
    // -------------------------------------------------------------------------

    /**
     * Listen for clipboard changes from other devices via Firebase.
     *
     * Registers a [ValueEventListener] on the user's clipboard path that
     * fires whenever the data changes (including initial load).
     *
     * ## Event Handling
     *
     * - **Source check**: Ignores updates where source="android" (our own updates)
     * - **Timestamp check**: Only accepts content newer than [lastSyncedTimestamp]
     * - **Local update**: Calls [updateLocalClipboard] for valid remote content
     *
     * ## Firebase Path
     *
     * `users/{userId}/clipboard`
     *
     * ## Threading
     *
     * Listener callbacks run on Firebase's internal thread. Local clipboard
     * update is performed on the calling thread (should be safe as
     * ClipboardManager is thread-safe for setPrimaryClip).
     */
    private fun startListeningForRemoteClipboard() {
        scope.launch {
            try {
                val currentUser = auth.currentUser ?: return@launch
                val userId = currentUser.uid

                val clipboardRef = database.reference
                    .child(USERS_PATH)
                    .child(userId)
                    .child(CLIPBOARD_PATH)

                val listener = object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val source = snapshot.child("source").value as? String ?: return

                        // Only process if from another device
                        if (source == "android") return

                        val text = snapshot.child("text").value as? String ?: return
                        val timestamp = snapshot.child("timestamp").value as? Long ?: return

                        // Check if this is newer than what we have
                        if (timestamp <= lastSyncedTimestamp) return

                        Log.d(TAG, "Received clipboard from $source: ${text.take(50)}...")

                        // Update local clipboard
                        updateLocalClipboard(text, timestamp)
                    }

                    override fun onCancelled(error: DatabaseError) {
                        Log.e(TAG, "Clipboard listener cancelled: ${error.message}")
                    }
                }

                clipboardValueListener = listener
                clipboardRef.addValueEventListener(listener)

                Log.d(TAG, "Remote clipboard listener registered")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting remote clipboard listener", e)
            }
        }
    }

    private fun stopListeningForRemoteClipboard() {
        clipboardValueListener?.let { listener ->
            scope.launch {
                try {
                    val userId = auth.currentUser?.uid ?: return@launch
                    database.reference
                        .child(USERS_PATH)
                        .child(userId)
                        .child(CLIPBOARD_PATH)
                        .removeEventListener(listener)
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing clipboard listener", e)
                }
            }
        }
        clipboardValueListener = null
    }

    // -------------------------------------------------------------------------
    // REGION: Firebase Sync Operations
    // -------------------------------------------------------------------------

    /**
     * Sync local clipboard content to Firebase.
     *
     * Writes the provided text to the user's clipboard path with metadata
     * including timestamp and source identifier.
     *
     * ## Firebase Write Structure
     *
     * ```json
     * {
     *   "text": "clipboard content",
     *   "timestamp": 1704067200000,
     *   "source": "android",
     *   "type": "text"
     * }
     * ```
     *
     * ## State Updates
     *
     * On successful write:
     * - [lastSyncedContent] is updated
     * - [lastSyncedTimestamp] is updated
     *
     * These are used for deduplication and timestamp comparison.
     *
     * @param text Clipboard text content to sync
     */
    private suspend fun syncClipboardToFirebase(text: String) {
        try {
            val currentUser = auth.currentUser ?: return
            val userId = currentUser.uid

            val clipboardRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child(CLIPBOARD_PATH)

            val timestamp = System.currentTimeMillis()

            val clipboardData = mapOf(
                "text" to text,
                "timestamp" to timestamp,
                "source" to "android",
                "type" to "text"
            )

            clipboardRef.setValue(clipboardData).await()

            lastSyncedContent = text
            lastSyncedTimestamp = timestamp

            Log.d(TAG, "Clipboard synced to Firebase: ${text.take(50)}...")
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing clipboard", e)
        }
    }

    /**
     * Update the local Android clipboard with remote content.
     *
     * Sets the Android clipboard to the provided text, with loop prevention
     * via the [isUpdatingFromRemote] flag.
     *
     * ## Loop Prevention Flow
     *
     * 1. Set [isUpdatingFromRemote] = true
     * 2. Update clipboard via ClipboardManager
     * 3. Update [lastSyncedContent] and [lastSyncedTimestamp]
     * 4. After 500ms delay, set [isUpdatingFromRemote] = false
     *
     * The delay ensures the OnPrimaryClipChangedListener has time to fire
     * and be ignored before we reset the flag.
     *
     * ## ClipData Label
     *
     * Uses "SyncFlow" as the clip label for identification in clipboard
     * history tools (though most users won't see this).
     *
     * @param text Text content to set as clipboard
     * @param timestamp Timestamp from the remote source (for tracking)
     */
    private fun updateLocalClipboard(text: String, timestamp: Long) {
        try {
            isUpdatingFromRemote = true

            val clip = ClipData.newPlainText("SyncFlow", text)
            clipboardManager.setPrimaryClip(clip)

            lastSyncedContent = text
            lastSyncedTimestamp = timestamp

            Log.d(TAG, "Local clipboard updated from remote")

            // Reset flag after a short delay
            scope.launch {
                delay(500)
                isUpdatingFromRemote = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating local clipboard", e)
            isUpdatingFromRemote = false
        }
    }

    // -------------------------------------------------------------------------
    // REGION: Public Utility Methods
    // -------------------------------------------------------------------------

    /**
     * Manually trigger a sync of the current clipboard content.
     *
     * Reads the current Android clipboard and syncs to Firebase if valid.
     * Use this for:
     * - User-initiated manual sync
     * - Initial sync when service starts
     * - Recovery after connectivity issues
     *
     * Does nothing if clipboard is empty or content is too large.
     */
    suspend fun syncNow() {
        val clipData = clipboardManager.primaryClip
        if (clipData == null || clipData.itemCount == 0) return

        val text = clipData.getItemAt(0).text?.toString()
        if (!text.isNullOrBlank() && text.length <= MAX_CLIPBOARD_LENGTH) {
            syncClipboardToFirebase(text)
        }
    }

    /**
     * Get the current text content of the Android clipboard.
     *
     * @return Current clipboard text, or null if empty or not text
     */
    fun getCurrentClipboard(): String? {
        val clipData = clipboardManager.primaryClip
        if (clipData == null || clipData.itemCount == 0) return null
        return clipData.getItemAt(0).text?.toString()
    }
}
