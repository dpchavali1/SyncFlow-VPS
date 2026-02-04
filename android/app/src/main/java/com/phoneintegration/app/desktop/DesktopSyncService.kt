/**
 * DesktopSyncService.kt
 *
 * Core synchronization service that enables SMS/MMS messages to be accessed from desktop
 * (macOS, Windows) and web clients. This service acts as the bridge between the Android
 * device's local message storage and Firebase Realtime Database.
 *
 * ## Architecture Overview
 *
 * The service follows a unidirectional data flow pattern:
 * ```
 * Android Device -> DesktopSyncService -> Firebase Cloud Functions -> Firebase RTDB
 *                                              |
 * Desktop/Web <--------------------------- Firebase RTDB (listeners)
 * ```
 *
 * ## Key Responsibilities
 *
 * 1. **Message Synchronization**: Syncs SMS/MMS messages to Firebase with optional E2EE encryption
 * 2. **Device Pairing**: Manages QR-code based pairing between phone and desktop clients
 * 3. **Attachment Handling**: Uploads MMS attachments to R2 storage with encryption
 * 4. **Bidirectional Sync**: Listens for outgoing messages from desktop to send via Android
 * 5. **Spam Sync**: Synchronizes spam messages and whitelist/blocklist across devices
 * 6. **Call Events**: Syncs call state for desktop call monitoring
 *
 * ## Firebase Data Structure
 *
 * ```
 * users/
 *   {userId}/
 *     messages/           - Synced SMS/MMS messages
 *     outgoing_messages/  - Messages queued from desktop to send
 *     devices/            - Paired desktop/web devices
 *     spam_messages/      - Detected spam messages
 *     spam_whitelist/     - User-marked safe senders
 *     spam_blocklist/     - User-marked spam senders
 *     groups/             - Group chat information
 *     calls/              - Call event history
 *     sync_requests/      - History sync requests from clients
 * ```
 *
 * ## Security Model
 *
 * - End-to-End Encryption (E2EE) is optional and managed via SignalProtocolManager
 * - When enabled, message bodies are encrypted with per-device key maps
 * - Attachments are encrypted before upload to R2 storage
 * - All Firebase writes use Cloud Functions to prevent OOM from auto-sync
 *
 * ## Memory Considerations
 *
 * - Uses Cloud Functions for all reads to avoid Firebase SDK's eager data sync
 * - Processes messages in batched chunks (50 at a time) to limit memory usage
 * - Always uses applicationContext to prevent Activity memory leaks
 *
 * ## Dependencies
 *
 * - Firebase Auth: User authentication
 * - Firebase Realtime Database: Message storage and real-time listeners
 * - Firebase Cloud Functions: Secure message writes and device management
 * - Firebase Storage / R2: Attachment storage
 * - SignalProtocolManager: E2EE encryption/decryption
 * - AuthManager: Authentication state management
 * - UsageTracker: Rate limiting and usage quotas
 *
 * @param context Android context (applicationContext is used internally)
 *
 * @see SignalProtocolManager for E2EE implementation details
 * @see OutgoingMessageService for desktop-to-Android message sending
 * @see SyncFlowMessagingService for FCM push notification handling
 */
package com.phoneintegration.app.desktop

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.google.firebase.functions.FirebaseFunctions
import com.phoneintegration.app.auth.AuthManager
import com.phoneintegration.app.MmsHelper
import com.phoneintegration.app.MmsAttachment
import com.phoneintegration.app.PhoneNumberUtils
import com.phoneintegration.app.SimManager
import com.phoneintegration.app.SmsMessage
import com.phoneintegration.app.SmsRepository
import com.phoneintegration.app.data.database.Group
import com.phoneintegration.app.data.database.GroupMember
import com.phoneintegration.app.data.PreferencesManager
import com.phoneintegration.app.e2ee.SignalProtocolManager
import com.phoneintegration.app.usage.UsageCategory
import com.phoneintegration.app.usage.UsageCheck
import com.phoneintegration.app.usage.UsageTracker
import com.phoneintegration.app.utils.NetworkUtils
import com.phoneintegration.app.utils.RetryUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

/**
 * Handles syncing SMS messages to Firebase Realtime Database
 * for desktop access.
 * Note: Always uses applicationContext internally to prevent memory leaks.
 */
class DesktopSyncService(context: Context) {

    // ==========================================
    // REGION: Instance Variables
    // ==========================================

    // Always use applicationContext to prevent Activity memory leaks
    private val context: Context = context.applicationContext

    /** Manages Firebase Auth and anonymous sign-in */
    private val authManager = AuthManager.getInstance(context)

    /** Firebase Authentication instance for user identity */
    private val auth = FirebaseAuth.getInstance()

    /** Firebase Realtime Database for message storage and real-time sync */
    private val database = FirebaseDatabase.getInstance()

    /** Firebase Cloud Functions for secure server-side operations */
    private val functions = FirebaseFunctions.getInstance()

    /** End-to-end encryption manager using Signal Protocol */
    private val e2eeManager = SignalProtocolManager(this.context)

    /** User preferences including E2EE toggle state */
    private val preferencesManager = PreferencesManager(this.context)

    /** Tracks upload/download usage for quota enforcement */
    private val usageTracker = UsageTracker(database)

    /** Manages SIM card information for phone number detection */
    private val simManager = SimManager(this.context)

    /** DEPRECATED: Backfill no longer needed with shared sync group keypair (v3) */
    @Deprecated("Backfill no longer needed")
    @Volatile private var isE2eeBackfillRunning = false

    /**
     * Cache of user's own phone numbers (normalized to last 10 digits).
     * Used to filter out messages where the address is the user's own number,
     * which can happen with some Android implementations storing sender instead
     * of recipient for sent messages.
     */
    private val userPhoneNumbers: Set<String> by lazy {
        val numbers = mutableSetOf<String>()
        try {
            simManager.getActiveSims().forEach { sim ->
                sim.phoneNumber?.let { phone ->
                    // Store only the last 10 digits (standard US number length without country code)
                    val normalized = phone.replace(Regex("[^0-9]"), "")
                    if (normalized.length >= 10) {
                        numbers.add(normalized.takeLast(10))
                        Log.d(TAG, "Added user phone number to filter: ${normalized.takeLast(10)}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not get user phone numbers: ${e.message}")
        }
        numbers
    }

    // ==========================================
    // REGION: Companion Object - Constants and Static Methods
    // ==========================================

    companion object {
        private const val TAG = "DesktopSyncService"

        // Firebase database path constants
        private const val MESSAGES_PATH = "messages"
        private const val DEVICES_PATH = "devices"
        private const val USERS_PATH = "users"
        private const val GROUPS_PATH = "groups"
        private const val ATTACHMENTS_PATH = "attachments"
        private const val MESSAGE_REACTIONS_PATH = "message_reactions"
        private const val SPAM_MESSAGES_PATH = "spam_messages"
        private const val SYNC_REQUESTS_PATH = "sync_requests"
        private const val E2EE_KEY_BACKUPS_PATH = "e2ee_key_backups"
        private const val E2EE_KEY_REQUESTS_PATH = "e2ee_key_requests"
        private const val E2EE_KEY_RESPONSES_PATH = "e2ee_key_responses"
        private const val E2EE_KEY_BACKFILL_REQUESTS_PATH = "e2ee_key_backfill_requests"

        // SharedPreferences keys for cached paired device status
        // This avoids hitting Firebase on every sync check
        private const val PREFS_NAME = "desktop_sync_prefs"
        private const val KEY_HAS_PAIRED_DEVICES = "has_paired_devices"
        private const val KEY_PAIRED_DEVICES_COUNT = "paired_devices_count"
        private const val KEY_LAST_CHECK_TIME = "last_paired_check_time"
        private const val CACHE_VALID_MS = 5 * 60 * 1000L // 5 minutes

        /**
         * Fast synchronous check if devices are paired (uses cached value)
         * Call this before any sync operation to skip unnecessary work
         */
        fun hasPairedDevices(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_HAS_PAIRED_DEVICES, false)
        }

        /**
         * Get cached paired devices count
         */
        fun getCachedDeviceCount(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getInt(KEY_PAIRED_DEVICES_COUNT, 0)
        }

        /**
         * Update the cached paired devices status
         * Called after pairing/unpairing or after fetching device list
         *
         * When transitioning from unpaired to paired, triggers deferred initialization
         * of E2EE, FCM, contacts sync, etc. that were skipped on fresh install.
         */
        fun updatePairedDevicesCache(context: Context, hasPaired: Boolean, count: Int = 0) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val wasPreviouslyPaired = prefs.getBoolean(KEY_HAS_PAIRED_DEVICES, false)

            prefs.edit()
                .putBoolean(KEY_HAS_PAIRED_DEVICES, hasPaired)
                .putInt(KEY_PAIRED_DEVICES_COUNT, count)
                .putLong(KEY_LAST_CHECK_TIME, System.currentTimeMillis())
                .apply()
            Log.d(TAG, "Updated paired devices cache: hasPaired=$hasPaired, count=$count")

            // Trigger deferred initialization when first device is paired
            if (hasPaired && !wasPreviouslyPaired) {
                Log.d(TAG, "First device paired - triggering deferred initialization")
                triggerDeferredInitialization(context)
            }
        }

        /**
         * Trigger initialization that was deferred until first device pairing.
         * This includes AuthManager, E2EE setup, FCM registration, contacts/call history sync.
         *
         * BANDWIDTH OPTIMIZATION: These services are not initialized on fresh install
         * to prevent 2MB+ Firebase downloads before any device pairing.
         *
         * CRITICAL: E2EE key listener is set up FIRST to respond to macOS/Web key requests
         * within their timeout window (60 seconds).
         */
        private fun triggerDeferredInitialization(context: Context) {
            val appContext = context.applicationContext

            // ⚡ CRITICAL: Setup E2EE key listener IMMEDIATELY (not in background thread)
            // macOS/Web sends key request right after pairing and waits 60 seconds
            // We must have the listener active BEFORE that timeout
            try {
                Log.d(TAG, "⚡ Setting up E2EE key listener IMMEDIATELY...")
                com.phoneintegration.app.services.IntelligentSyncManager.getInstance(appContext)
                    .setupE2eeKeyListenerImmediately()
                Log.d(TAG, "⚡ E2EE key listener setup initiated")
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up E2EE key listener", e)
            }

            // Use a background thread for other deferred initialization
            java.util.concurrent.Executors.newSingleThreadExecutor().execute {
                Log.d(TAG, "Starting deferred initialization after first device pairing...")

                // Initialize AuthManager and start Firebase Auth listeners
                try {
                    com.phoneintegration.app.auth.AuthManager.getInstance(appContext)
                        .startAuthListenersAfterPairing()
                    Log.d(TAG, "Deferred AuthManager initialization completed")
                } catch (e: Exception) {
                    Log.e(TAG, "Error in deferred AuthManager initialization", e)
                }

                // Initialize DataCleanupService
                try {
                    com.phoneintegration.app.services.DataCleanupService.getInstance(appContext)
                    Log.d(TAG, "Deferred DataCleanupService initialization completed")
                } catch (e: Exception) {
                    Log.e(TAG, "Error in deferred DataCleanupService initialization", e)
                }

                // Initialize E2EE keys and publish to Firebase
                try {
                    val signalProtocolManager = SignalProtocolManager(appContext)
                    signalProtocolManager.initializeKeys()
                    Log.d(TAG, "Deferred E2EE initialization completed")
                } catch (e: Exception) {
                    Log.e(TAG, "Error in deferred E2EE initialization", e)
                }

                // Register FCM token
                try {
                    val userId = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
                    if (userId != null) {
                        com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                            .addOnSuccessListener { token ->
                                Log.d(TAG, "Registering FCM token after pairing for user $userId")
                                com.google.firebase.database.FirebaseDatabase.getInstance().reference
                                    .child("fcm_tokens")
                                    .child(userId)
                                    .setValue(token)
                                    .addOnSuccessListener {
                                        Log.d(TAG, "FCM token registered successfully after pairing")
                                    }
                            }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error registering FCM token after pairing", e)
                }

                // Start UnifiedIdentityManager monitoring
                try {
                    com.phoneintegration.app.auth.UnifiedIdentityManager.getInstance(appContext)
                        .startMonitoringAfterPairing()
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting device monitoring after pairing", e)
                }

                // Start IntelligentSyncManager listeners
                try {
                    com.phoneintegration.app.services.IntelligentSyncManager.getInstance(appContext)
                        .startAfterPairing()
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting IntelligentSyncManager after pairing", e)
                }

                // Brief delay to let E2EE finish, then trigger sync
                Thread.sleep(1000)
                try {
                    ContactsSyncWorker.syncNow(appContext)
                    CallHistorySyncWorker.syncNow(appContext)
                    Log.d(TAG, "Triggered contacts and call history sync after pairing")
                } catch (e: Exception) {
                    Log.e(TAG, "Error triggering sync after pairing", e)
                }

                // Start OutgoingMessageService if enabled (must be on main thread)
                try {
                    val preferencesManager = com.phoneintegration.app.data.PreferencesManager(appContext)
                    if (preferencesManager.backgroundSyncEnabled.value) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            OutgoingMessageService.start(appContext)
                        }
                        Log.d(TAG, "Started OutgoingMessageService after pairing")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting OutgoingMessageService after pairing", e)
                }

                Log.d(TAG, "Deferred initialization completed")
            }
        }

        /**
         * Check if cache needs refresh
         */
        fun isCacheStale(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lastCheck = prefs.getLong(KEY_LAST_CHECK_TIME, 0)
            return System.currentTimeMillis() - lastCheck > CACHE_VALID_MS
        }
    }

    // ==========================================
    // REGION: Initialization
    // ==========================================

    init {
        // Only initialize E2EE keys if user has paired devices
        // This prevents Firebase writes on fresh install before pairing
        if (hasPairedDevices(context)) {
            e2eeManager.initializeKeys()
        }
    }

    /**
     * Get current user ID.
     *
     * Priority: Recovery Code (unified identity) > Firebase Auth
     * This ensures contacts and data sync to the correct unified user account
     * instead of creating data under a new Firebase anonymous auth UID.
     *
     * NOTE: ensureDeviceKeysPublished() is NOT called here anymore because it can hang
     * due to Android Keystore operations. It should only be called when needed for E2EE,
     * not on every user ID request.
     */
    suspend fun getCurrentUserId(): String {
        // Priority 1: Check RecoveryCodeManager (unified identity - consistent across devices)
        val recoveryManager = com.phoneintegration.app.auth.RecoveryCodeManager.getInstance(context)
        recoveryManager.getEffectiveUserId()?.let { recoveredUserId ->
            // Ensure Firebase auth session exists
            if (FirebaseAuth.getInstance().currentUser == null) {
                FirebaseAuth.getInstance().signInAnonymously().await()
            }
            return recoveredUserId
        }

        // Priority 2: Use current Firebase Auth user (fallback for non-unified accounts)
        FirebaseAuth.getInstance().currentUser?.let { return it.uid }

        // Priority 3: Try AuthManager
        authManager.getCurrentUserId()?.let { return it }

        // Last resort: sign in anonymously
        val result = authManager.signInAnonymously()
        return result.getOrNull() ?: throw Exception("Authentication failed")
    }

    // ==========================================
    // REGION: Address Resolution
    // ==========================================

    /**
     * Get the "conversation partner" address for a message.
     *
     * This method resolves the correct phone number for the other party in a conversation,
     * handling the complexities of Android's SMS/MMS storage where sent message addresses
     * may incorrectly contain the sender's own number instead of the recipient.
     *
     * ## Resolution Strategy
     *
     * 1. For received messages (type=1): Return the address directly (it's the sender)
     * 2. For sent MMS: Query MMS recipients table, filter out user's own number
     * 3. For sent SMS: Query the Threads table to find the true recipient
     * 4. Fallback: Look for received messages in same thread to infer recipient
     *
     * @param message The SMS/MMS message to resolve the address for
     * @return The conversation partner's phone number, or null if the address
     *         resolves to the user's own number (message should be skipped)
     */
    private suspend fun getConversationAddress(message: SmsMessage): String? {
        // For received messages, the address is already the other party
        if (message.type == 1) {
            return message.address
        }

        // MMS sent: resolve from MMS recipients to avoid SMS ID collisions
        if (message.isMms) {
            val recipients = MmsHelper.getMmsAllRecipients(
                context.contentResolver,
                message.id
            )
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.contains("insert-address-token", ignoreCase = true) }

            if (recipients.isNotEmpty()) {
                // Prefer recipients that are NOT the user's own number
                val nonUserRecipients = recipients.filterNot { isUserOwnNumber(it) }
                if (nonUserRecipients.isNotEmpty()) {
                    return nonUserRecipients.first()
                }
                // All recipients are user's own number - skip this message
                Log.w(TAG, "MMS ${message.id} only has user's own number as recipient, skipping")
                return null
            }

            return message.address
        }

        // For sent SMS messages (type=2), the message.address SHOULD be the recipient
        // But some Android implementations store the sender's number instead
        // We need to verify by checking the Threads table
        try {
            val uri = android.provider.Telephony.Sms.CONTENT_URI
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(
                    android.provider.Telephony.Sms.ADDRESS,
                    android.provider.Telephony.Sms.TYPE,
                    android.provider.Telephony.Sms.THREAD_ID
                ),
                "${android.provider.Telephony.Sms._ID} = ?",
                arrayOf(message.id.toString()),
                null
            )

            var threadId: Long? = null
            var smsAddress: String? = null
            cursor?.use {
                if (it.moveToFirst()) {
                    smsAddress = it.getString(0)
                    threadId = it.getLong(2)
                }
            }

            // Try to get the recipient address from the Threads table
            // This is the most reliable source for the conversation partner
            if (threadId != null) {
                try {
                    val threadsUri = android.net.Uri.parse("content://mms-sms/conversations?simple=true")
                    val threadCursor = context.contentResolver.query(
                        threadsUri,
                        arrayOf("_id", "recipient_ids"),
                        "_id = ?",
                        arrayOf(threadId.toString()),
                        null
                    )

                    threadCursor?.use {
                        if (it.moveToFirst()) {
                            val recipientIdsIndex = it.getColumnIndex("recipient_ids")
                            if (recipientIdsIndex >= 0) {
                                val recipientIds = it.getString(recipientIdsIndex)
                                if (!recipientIds.isNullOrBlank()) {
                                    // Resolve ALL recipient_ids and filter out user's own number
                                    val allIds = recipientIds.split(" ").filter { id -> id.isNotBlank() }
                                    for (recipientId in allIds) {
                                        val resolvedAddress = resolveRecipientId(recipientId)
                                        if (!resolvedAddress.isNullOrBlank() && !isUserOwnNumber(resolvedAddress)) {
                                            return resolvedAddress
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not query Threads table: ${e.message}")
                }

                // Fallback: look for a received message in the same thread
                val threadMsgCursor = context.contentResolver.query(
                    uri,
                    arrayOf(android.provider.Telephony.Sms.ADDRESS),
                    "${android.provider.Telephony.Sms.THREAD_ID} = ? AND ${android.provider.Telephony.Sms.TYPE} = 1",
                    arrayOf(threadId.toString()),
                    "${android.provider.Telephony.Sms.DATE} DESC LIMIT 1"
                )

                threadMsgCursor?.use {
                    if (it.moveToFirst()) {
                        val recipientAddress = it.getString(0)
                        if (!recipientAddress.isNullOrBlank()) {
                            // Received message address is the OTHER party, use it
                            return recipientAddress
                        }
                    }
                }
            }

            // Use the address from the SMS table query if available
            if (!smsAddress.isNullOrBlank()) {
                // Only skip if this address is definitively the user's own number
                if (isUserOwnNumber(smsAddress!!)) {
                    Log.w(TAG, "Sent SMS ${message.id} has user's own number as address, skipping")
                    return null
                }
                return smsAddress!!
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding conversation address", e)
        }

        // Final fallback: only skip if address is user's own number
        if (isUserOwnNumber(message.address)) {
            Log.w(TAG, "Could not determine recipient for sent message ${message.id}, address is user's own number")
            return null
        }

        // Fallback: return the original address from the message
        return message.address
    }

    /**
     * Resolve a recipient_id to an actual phone number using the canonical_addresses table
     */
    private fun resolveRecipientId(recipientId: String): String? {
        try {
            val canonicalUri = android.net.Uri.parse("content://mms-sms/canonical-addresses")
            val cursor = context.contentResolver.query(
                canonicalUri,
                arrayOf("_id", "address"),
                "_id = ?",
                arrayOf(recipientId),
                null
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    val addressIndex = it.getColumnIndex("address")
                    if (addressIndex >= 0) {
                        return it.getString(addressIndex)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve recipient ID $recipientId: ${e.message}")
        }
        return null
    }

    private fun isRcsAddress(address: String): Boolean {
        val lower = address.lowercase()
        return lower.contains("@rcs") ||
            lower.contains("rcs.google") ||
            lower.contains("rcs.goog") ||
            lower.startsWith("rcs:") ||
            lower.startsWith("rcs://")
    }

    /**
     * Check if an address is the user's own phone number.
     * Used to filter out incorrectly resolved sent message addresses.
     * Only matches on last 10 digits to avoid false positives while still
     * handling different country code formats.
     */
    private fun isUserOwnNumber(address: String): Boolean {
        if (userPhoneNumbers.isEmpty()) return false
        val normalized = address.replace(Regex("[^0-9]"), "")
        // Need at least 10 digits for a valid comparison
        if (normalized.length < 10) return false
        val last10 = normalized.takeLast(10)
        val isMatch = userPhoneNumbers.contains(last10)
        if (isMatch) {
            Log.d(TAG, "Address $address matches user's own number")
        }
        return isMatch
    }

    // ==========================================
    // REGION: Message Synchronization
    // ==========================================

    /**
     * Syncs a single SMS/MMS message to Firebase with optional E2EE encryption.
     *
     * This is the core sync method that handles:
     * - Network connectivity checks (skips sync if offline)
     * - RCS/RBM message filtering (these are handled differently)
     * - E2EE encryption when enabled (encrypts body and creates per-device key maps)
     * - MMS attachment processing and upload
     * - Writing to Firebase via Cloud Function (prevents OOM from direct writes)
     *
     * ## E2EE Encryption Flow
     *
     * When E2EE is enabled:
     * 1. Generate a random 32-byte data key for this message
     * 2. Encrypt message body with the data key
     * 3. For each paired device, encrypt the data key with that device's public key
     * 4. Store encrypted body + nonce + keyMap in Firebase
     *
     * ## Error Handling
     *
     * - If E2EE encryption fails, message is stored with a redacted body and e2eeFailed flag
     * - If Cloud Function fails, error is logged but doesn't throw (prevents crashes)
     * - Attachment upload failures are logged and skipped (message still syncs)
     *
     * @param message The SMS/MMS message to sync
     * @param skipAttachments If true, skips MMS attachment processing (used for history sync)
     */
    suspend fun syncMessage(message: SmsMessage, skipAttachments: Boolean = false) {
        try {
            Log.d(TAG, "Starting sync for message: id=${message.id}, isMms=${message.isMms}, address=${message.address}, body length=${message.body?.length ?: 0}, skipAttachments=$skipAttachments")

            // Check network connectivity before syncing
            if (!NetworkUtils.isNetworkAvailable(context)) {
                Log.w(TAG, "No network available, skipping sync for message: ${message.id}")
                return
            }

            // Filter out RBM (Rich Business Messaging) spam
            if (message.address.contains("@rbm.goog", ignoreCase = true)) {
                Log.d(TAG, "Skipping RBM message from: ${message.address}")
                return
            }
            if (isRcsAddress(message.address)) {
                Log.d(TAG, "Skipping RCS message from: ${message.address}")
                return
            }

            val userId = getCurrentUserId()
            // E2EE keys are initialized in init{}, no need to call ensureDeviceKeysPublished() here
            val messageKey = getFirebaseMessageKey(message)
            val messageRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child(MESSAGES_PATH)
                .child(messageKey)

            // Get the normalized conversation address (the "other party")
            val conversationAddress = getConversationAddress(message)

            // Skip messages where we couldn't determine the conversation partner
            // This happens when the address is the user's own phone number
            if (conversationAddress == null) {
                Log.w(TAG, "Skipping message ${message.id} - could not determine conversation partner")
                return
            }

            val messageData = mutableMapOf<String, Any?>(
                "id" to messageKey,
                "sourceId" to message.id,
                "sourceType" to if (message.isMms) "mms" else "sms",
                "address" to conversationAddress,  // Use normalized address
                "date" to message.date,
                "type" to message.type,
                "timestamp" to ServerValue.TIMESTAMP
            )

            val isE2eeEnabled = preferencesManager.e2eeEnabled.value
            messageData.applyEncryptionPayload(userId, message.body)

            // Add contact name if available
            if (!isE2eeEnabled) {
                message.contactName?.let {
                    messageData["contactName"] = it
                }
            }

            Log.d(TAG, "Syncing message: id=${message.id}, type=${message.type}, address=$conversationAddress")

            // Handle MMS attachments
            if (message.isMms) {
                messageData["isMms"] = true
                if (skipAttachments) {
                    Log.d(TAG, "Skipping attachments for MMS message ${message.id} (history sync)")
                } else {
                    Log.d(TAG, "Processing MMS attachments for message ${message.id}")
                    val attachments = if (message.mmsAttachments.isNotEmpty()) {
                        Log.d(TAG, "Using existing MMS attachments: ${message.mmsAttachments.size}")
                        message.mmsAttachments
                    } else {
                        Log.d(TAG, "Loading MMS attachments from provider for message ${message.id}")
                        loadMmsAttachmentsFromProvider(message.id)
                    }
                    Log.d(TAG, "Found ${attachments.size} MMS attachments")
                    if (attachments.isNotEmpty()) {
                    val attachmentUrls = uploadMmsAttachments(userId, message.id, attachments)
                    Log.d(TAG, "Uploaded ${attachmentUrls.size} MMS attachment URLs")
                    if (attachmentUrls.isNotEmpty()) {
                        messageData["attachments"] = attachmentUrls
                    }
                    }
                }
            }

            // Add subId if available
            message.subId?.let {
                messageData["subId"] = it
            }

            // Write to Firebase via Cloud Function (prevents OOM from Firebase auto-sync)
            try {
                val result = functions
                    .getHttpsCallable("syncMessage")
                    .call(mapOf(
                        "messageId" to messageKey,
                        "message" to messageData
                    ))
                    .await()

                val data = result.data as? Map<*, *>
                val success = data?.get("success") as? Boolean ?: false
                if (success) {
                    Log.d(TAG, "Successfully synced message via Cloud Function: ${message.id}")
                } else {
                    Log.w(TAG, "Cloud Function returned success=false for message: ${message.id}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing message via Cloud Function: ${e.message}", e)
                // Don't throw - message sync failure shouldn't crash the app
            }

            Log.d(
                TAG,
                "Message synced successfully: $messageKey with address: $conversationAddress (encrypted: ${messageData["encrypted"] == true}, isMms: ${message.isMms})"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing message after retries", e)
        }
    }

    /**
     * Sets or removes a reaction (emoji) for a message.
     *
     * Reactions are synced across all devices in real-time. When a user reacts
     * to a message on Android, the reaction appears on desktop/web clients.
     *
     * @param messageId The local message ID to react to
     * @param reaction The emoji reaction string, or null to remove the reaction
     */
    suspend fun setMessageReaction(messageId: Long, reaction: String?) {
        val userId = getCurrentUserId()
        val reactionRef = database.reference
            .child(USERS_PATH)
            .child(userId)
            .child(MESSAGE_REACTIONS_PATH)
            .child(messageId.toString())

        RetryUtils.withFirebaseRetry("setMessageReaction $messageId") {
            if (reaction.isNullOrBlank()) {
                reactionRef.removeValue().await()
            } else {
                val payload = mapOf(
                    "reaction" to reaction,
                    "updatedAt" to ServerValue.TIMESTAMP,
                    "updatedBy" to "android"
                )
                reactionRef.setValue(payload).await()
            }
        }
    }

    // ==========================================
    // REGION: Spam Message Synchronization
    // ==========================================

    /**
     * Syncs a spam message to Firebase for cross-device persistence.
     *
     * When a message is detected as spam (either automatically or user-marked),
     * it's synced to Firebase so the spam classification persists across:
     * - App reinstalls
     * - Device migrations
     * - Desktop/web clients
     *
     * @param message The spam message entity containing classification details
     */
    suspend fun syncSpamMessage(message: com.phoneintegration.app.data.database.SpamMessage) {
        withContext(NonCancellable + kotlinx.coroutines.Dispatchers.IO) {
            try {
                val spamMessageData = hashMapOf<String, Any>(
                    "address" to message.address,
                    "body" to message.body,
                    "date" to message.date,
                    "contactName" to (message.contactName ?: message.address),
                    "spamConfidence" to message.spamConfidence.toDouble(),
                    "spamReasons" to (message.spamReasons ?: "user_marked"),
                    "detectedAt" to message.detectedAt,
                    "isUserMarked" to message.isUserMarked,
                    "isRead" to message.isRead
                )

                val data = hashMapOf<String, Any>(
                    "messageId" to message.messageId.toString(),
                    "spamMessage" to spamMessageData
                )

                val response = functions.getHttpsCallable("syncSpamMessage").call(data).await()
                val resultData = response.data as? Map<*, *>
                val success = resultData?.get("success") as? Boolean ?: false
                if (!success) {
                    Log.e(TAG, "Failed to sync spam message ${message.messageId}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing spam message ${message.messageId}", e)
            }
        }
    }

    suspend fun syncSpamMessages(messages: List<com.phoneintegration.app.data.database.SpamMessage>) {
        messages.forEach { syncSpamMessage(it) }
    }

    suspend fun fetchSpamMessages(): List<com.phoneintegration.app.data.database.SpamMessage> {
        return try {
            val userId = getCurrentUserId()
            val spamRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child(SPAM_MESSAGES_PATH)

            val snapshot = spamRef.get().await()
            val list = mutableListOf<com.phoneintegration.app.data.database.SpamMessage>()
            snapshot.children.forEach { child ->
                val address = child.child("address").getValue(String::class.java) ?: return@forEach
                val body = child.child("body").getValue(String::class.java) ?: ""
                val date = child.child("date").getValue(Long::class.java) ?: 0L
                val id = child.child("messageId").getValue(Long::class.java)
                    ?: child.key?.toLongOrNull()
                    ?: child.child("originalMessageId").getValue(String::class.java)?.toLongOrNull()
                    ?: generateSpamFallbackId(address, body, date)
                    ?: return@forEach
                val contactName = child.child("contactName").getValue(String::class.java)
                val spamConfidence = (child.child("spamConfidence").getValue(Double::class.java)
                    ?: child.child("spamConfidence").getValue(Float::class.java)?.toDouble()
                    ?: 0.5).toFloat()
                val spamReasons = child.child("spamReasons").getValue(String::class.java)
                val detectedAt = child.child("detectedAt").getValue(Long::class.java) ?: System.currentTimeMillis()
                val isUserMarked = child.child("isUserMarked").getValue(Boolean::class.java) ?: false
                val isRead = child.child("isRead").getValue(Boolean::class.java) ?: false

                list.add(
                    com.phoneintegration.app.data.database.SpamMessage(
                        messageId = id,
                        address = address,
                        body = body,
                        date = date,
                        contactName = contactName,
                        spamConfidence = spamConfidence,
                        spamReasons = spamReasons,
                        detectedAt = detectedAt,
                        isUserMarked = isUserMarked,
                        isRead = isRead
                    )
                )
            }
            list.sortedByDescending { it.date }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching spam messages", e)
            emptyList()
        }
    }

    private fun generateSpamFallbackId(address: String, body: String, date: Long): Long? {
        if (address.isBlank() && body.isBlank() && date == 0L) {
            return null
        }
        val input = "$address|$body|$date"
        val crc = java.util.zip.CRC32()
        crc.update(input.toByteArray(Charsets.UTF_8))
        return crc.value.toLong()
    }

    suspend fun deleteSpamMessage(messageId: Long) {
        withContext(NonCancellable + kotlinx.coroutines.Dispatchers.IO) {
            try {
                val data = hashMapOf<String, Any>("messageId" to messageId.toString())
                functions.getHttpsCallable("deleteSpamMessage").call(data).await()
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting spam message $messageId", e)
            }
        }
    }

    suspend fun clearAllSpamMessages() {
        withContext(NonCancellable + kotlinx.coroutines.Dispatchers.IO) {
            try {
                functions.getHttpsCallable("clearAllSpamMessages").call(hashMapOf<String, Any>()).await()
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing spam messages", e)
            }
        }
    }

    /**
     * Real-time listener for spam messages from Firebase.
     *
     * This enables bidirectional spam sync - when Mac/Web marks a message as spam,
     * Android will receive the update in real-time and update its local database.
     *
     * ## Flow Behavior
     *
     * - Emits the full list of spam messages whenever any change occurs
     * - Returns sorted by date descending (newest first)
     * - Closes the flow if user is not authenticated
     *
     * @return Flow emitting List<SpamMessage> on each Firebase update
     */
    fun listenForSpamMessages(): Flow<List<com.phoneintegration.app.data.database.SpamMessage>> = callbackFlow {
        val userId = try {
            getCurrentUserId()
        } catch (e: Exception) {
            Log.e(TAG, "Cannot listen for spam - not authenticated", e)
            close()
            return@callbackFlow
        }

        val spamRef = database.reference
            .child(USERS_PATH)
            .child(userId)
            .child(SPAM_MESSAGES_PATH)

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val list = mutableListOf<com.phoneintegration.app.data.database.SpamMessage>()
                snapshot.children.forEach { child ->
                    try {
                        val address = child.child("address").getValue(String::class.java) ?: return@forEach
                        val body = child.child("body").getValue(String::class.java) ?: ""
                        val date = child.child("date").getValue(Long::class.java) ?: 0L
                        val id = child.child("messageId").getValue(Long::class.java)
                            ?: child.key?.toLongOrNull()
                            ?: child.child("originalMessageId").getValue(String::class.java)?.toLongOrNull()
                            ?: generateSpamFallbackId(address, body, date)
                            ?: return@forEach
                        val contactName = child.child("contactName").getValue(String::class.java)
                        val spamConfidence = (child.child("spamConfidence").getValue(Double::class.java)
                            ?: child.child("spamConfidence").getValue(Float::class.java)?.toDouble()
                            ?: 0.5).toFloat()
                        val spamReasons = child.child("spamReasons").getValue(String::class.java)
                        val detectedAt = child.child("detectedAt").getValue(Long::class.java) ?: System.currentTimeMillis()
                        val isUserMarked = child.child("isUserMarked").getValue(Boolean::class.java) ?: false
                        val isRead = child.child("isRead").getValue(Boolean::class.java) ?: false

                        list.add(
                            com.phoneintegration.app.data.database.SpamMessage(
                                messageId = id,
                                address = address,
                                body = body,
                                date = date,
                                contactName = contactName,
                                spamConfidence = spamConfidence,
                                spamReasons = spamReasons,
                                detectedAt = detectedAt,
                                isUserMarked = isUserMarked,
                                isRead = isRead
                            )
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Error parsing spam message ${child.key}", e)
                    }
                }
                trySend(list.sortedByDescending { it.date }).isSuccess
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Spam listener cancelled: ${error.message}")
            }
        }

        spamRef.addValueEventListener(listener)
        awaitClose {
            Log.d(TAG, "Cleaning up spam messages listener for user: $userId")
            spamRef.removeEventListener(listener)
        }
    }

    /**
     * BANDWIDTH OPTIMIZED spam listener using ChildEventListener.
     *
     * Uses child events instead of value events to reduce bandwidth by ~95%.
     * Instead of receiving all spam messages on every change, this only
     * receives the individual message that was added/changed/removed.
     *
     * @param onAdded Called when a new spam message is added
     * @param onChanged Called when a spam message is updated
     * @param onRemoved Called when a spam message is removed
     * @return Flow that emits Unit on each change (use with local cache)
     */
    fun listenForSpamMessagesOptimized(
        onAdded: (com.phoneintegration.app.data.database.SpamMessage) -> Unit,
        onChanged: (com.phoneintegration.app.data.database.SpamMessage) -> Unit,
        onRemoved: (String) -> Unit
    ): Flow<Unit> = callbackFlow {
        val userId = try {
            getCurrentUserId()
        } catch (e: Exception) {
            Log.e(TAG, "Cannot listen for spam - not authenticated", e)
            close()
            return@callbackFlow
        }

        val spamRef = database.reference
            .child(USERS_PATH)
            .child(userId)
            .child(SPAM_MESSAGES_PATH)
            .orderByChild("date")
            .limitToLast(100)

        fun parseSpamMessage(child: DataSnapshot): com.phoneintegration.app.data.database.SpamMessage? {
            return try {
                val address = child.child("address").getValue(String::class.java) ?: return null
                val body = child.child("body").getValue(String::class.java) ?: ""
                val date = child.child("date").getValue(Long::class.java) ?: 0L
                val id = child.child("messageId").getValue(Long::class.java)
                    ?: child.key?.toLongOrNull()
                    ?: child.child("originalMessageId").getValue(String::class.java)?.toLongOrNull()
                    ?: generateSpamFallbackId(address, body, date)
                    ?: return null
                val contactName = child.child("contactName").getValue(String::class.java)
                val spamConfidence = (child.child("spamConfidence").getValue(Double::class.java)
                    ?: child.child("spamConfidence").getValue(Float::class.java)?.toDouble()
                    ?: 0.5).toFloat()
                val spamReasons = child.child("spamReasons").getValue(String::class.java)
                val detectedAt = child.child("detectedAt").getValue(Long::class.java) ?: System.currentTimeMillis()
                val isUserMarked = child.child("isUserMarked").getValue(Boolean::class.java) ?: false
                val isRead = child.child("isRead").getValue(Boolean::class.java) ?: false

                com.phoneintegration.app.data.database.SpamMessage(
                    messageId = id,
                    address = address,
                    body = body,
                    date = date,
                    contactName = contactName,
                    spamConfidence = spamConfidence,
                    spamReasons = spamReasons,
                    detectedAt = detectedAt,
                    isUserMarked = isUserMarked,
                    isRead = isRead
                )
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing spam message ${child.key}", e)
                null
            }
        }

        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                parseSpamMessage(snapshot)?.let { msg ->
                    onAdded(msg)
                    trySend(Unit)
                }
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                parseSpamMessage(snapshot)?.let { msg ->
                    onChanged(msg)
                    trySend(Unit)
                }
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {
                snapshot.key?.let { key ->
                    onRemoved(key)
                    trySend(Unit)
                }
            }

            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {
                // Not used for spam messages
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Optimized spam listener cancelled: ${error.message}")
            }
        }

        spamRef.addChildEventListener(listener)
        awaitClose {
            Log.d(TAG, "Cleaning up optimized spam messages listener for user: $userId")
            spamRef.removeEventListener(listener)
        }
    }

    // ==========================================
    // REGION: Spam Whitelist/Blocklist Sync
    // ==========================================

    /**
     * Syncs the spam whitelist (safe senders) to Firebase.
     *
     * When a user marks a sender as "not spam", their address is added to the
     * whitelist. This list syncs to Firebase so the classification persists
     * across devices and reinstalls.
     *
     * @param addresses Set of phone numbers/addresses marked as safe
     */
    suspend fun syncWhitelist(addresses: Set<String>) {
        withContext(NonCancellable + kotlinx.coroutines.Dispatchers.IO) {
            try {
                val userId = getCurrentUserId()
                val whitelistRef = database.reference
                    .child(USERS_PATH)
                    .child(userId)
                    .child("spam_whitelist")

                whitelistRef.setValue(addresses.toList()).await()
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing whitelist", e)
            }
        }
    }

    /**
     * Syncs the spam blocklist (always-spam senders) to Firebase.
     *
     * When a user explicitly marks a sender as spam, their address is added
     * to the blocklist. Future messages from this sender will be automatically
     * classified as spam.
     *
     * @param addresses Set of phone numbers/addresses marked as spam
     */
    suspend fun syncBlocklist(addresses: Set<String>) {
        withContext(NonCancellable + kotlinx.coroutines.Dispatchers.IO) {
            try {
                val userId = getCurrentUserId()
                val blocklistRef = database.reference
                    .child(USERS_PATH)
                    .child(userId)
                    .child("spam_blocklist")

                blocklistRef.setValue(addresses.toList()).await()
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing blocklist", e)
            }
        }
    }

    /**
     * Listen for whitelist changes from cloud (e.g., when macOS marks as not spam)
     */
    fun listenForWhitelist(): Flow<Set<String>> = callbackFlow {
        val userId = try {
            getCurrentUserId()
        } catch (e: Exception) {
            Log.e(TAG, "Cannot listen for whitelist - not authenticated", e)
            close()
            return@callbackFlow
        }

        val whitelistRef = database.reference
            .child(USERS_PATH)
            .child(userId)
            .child("spam_whitelist")

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val addresses = mutableSetOf<String>()
                snapshot.children.forEach { child ->
                    (child.getValue(String::class.java))?.let { addresses.add(it) }
                }
                trySend(addresses).isSuccess
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Whitelist listener cancelled: ${error.message}")
            }
        }

        whitelistRef.addValueEventListener(listener)
        awaitClose {
            whitelistRef.removeEventListener(listener)
        }
    }

    /**
     * Listen for blocklist changes from cloud (e.g., when macOS marks as spam)
     */
    fun listenForBlocklist(): Flow<Set<String>> = callbackFlow {
        val userId = try {
            getCurrentUserId()
        } catch (e: Exception) {
            Log.e(TAG, "Cannot listen for blocklist - not authenticated", e)
            close()
            return@callbackFlow
        }

        val blocklistRef = database.reference
            .child(USERS_PATH)
            .child(userId)
            .child("spam_blocklist")

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val addresses = mutableSetOf<String>()
                snapshot.children.forEach { child ->
                    (child.getValue(String::class.java))?.let { addresses.add(it) }
                }
                trySend(addresses).isSuccess
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Blocklist listener cancelled: ${error.message}")
            }
        }

        blocklistRef.addValueEventListener(listener)
        awaitClose {
            blocklistRef.removeEventListener(listener)
        }
    }

    suspend fun listenForMessageReactions(): Flow<Map<Long, String>> = callbackFlow {
        val userId = getCurrentUserId()
        val reactionsRef = database.reference
            .child(USERS_PATH)
            .child(userId)
            .child(MESSAGE_REACTIONS_PATH)

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val reactions = mutableMapOf<Long, String>()
                snapshot.children.forEach { child ->
                    val id = child.key?.toLongOrNull() ?: return@forEach
                    val reactionValue = when (val value = child.child("reaction").value) {
                        is String -> value
                        else -> null
                    } ?: return@forEach
                    reactions[id] = reactionValue
                }
                trySend(reactions).isSuccess
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Reaction listener cancelled: ${error.message}")
            }
        }

        reactionsRef.addValueEventListener(listener)
        awaitClose {
            reactionsRef.removeEventListener(listener)
        }
    }

    // ==========================================
    // BANDWIDTH OPTIMIZED LISTENERS (use ChildEventListener for delta-only sync)
    // ==========================================

    /**
     * BANDWIDTH OPTIMIZED: Listen for whitelist changes using child events
     * Downloads only deltas instead of full list on every change
     */
    fun listenForWhitelistOptimized(): Flow<Set<String>> = callbackFlow {
        val userId = try {
            getCurrentUserId()
        } catch (e: Exception) {
            Log.e(TAG, "Cannot listen for whitelist - not authenticated", e)
            close()
            return@callbackFlow
        }

        val whitelistRef = database.reference
            .child(USERS_PATH)
            .child(userId)
            .child("spam_whitelist")

        // Local cache to maintain full set
        val addressCache = mutableSetOf<String>()

        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                snapshot.getValue(String::class.java)?.let { address ->
                    addressCache.add(address)
                    trySend(addressCache.toSet()).isSuccess
                }
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                // Whitelist entries don't change, just add/remove
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {
                snapshot.getValue(String::class.java)?.let { address ->
                    addressCache.remove(address)
                    trySend(addressCache.toSet()).isSuccess
                }
            }

            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {
                // Not used for flat lists
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Whitelist optimized listener cancelled: ${error.message}")
            }
        }

        whitelistRef.addChildEventListener(listener)
        awaitClose {
            whitelistRef.removeEventListener(listener)
        }
    }

    /**
     * BANDWIDTH OPTIMIZED: Listen for blocklist changes using child events
     * Downloads only deltas instead of full list on every change
     */
    fun listenForBlocklistOptimized(): Flow<Set<String>> = callbackFlow {
        val userId = try {
            getCurrentUserId()
        } catch (e: Exception) {
            Log.e(TAG, "Cannot listen for blocklist - not authenticated", e)
            close()
            return@callbackFlow
        }

        val blocklistRef = database.reference
            .child(USERS_PATH)
            .child(userId)
            .child("spam_blocklist")

        // Local cache to maintain full set
        val addressCache = mutableSetOf<String>()

        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                snapshot.getValue(String::class.java)?.let { address ->
                    addressCache.add(address)
                    trySend(addressCache.toSet()).isSuccess
                }
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                // Blocklist entries don't change, just add/remove
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {
                snapshot.getValue(String::class.java)?.let { address ->
                    addressCache.remove(address)
                    trySend(addressCache.toSet()).isSuccess
                }
            }

            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {
                // Not used for flat lists
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Blocklist optimized listener cancelled: ${error.message}")
            }
        }

        blocklistRef.addChildEventListener(listener)
        awaitClose {
            blocklistRef.removeEventListener(listener)
        }
    }

    /**
     * BANDWIDTH OPTIMIZED: Listen for message reactions using child events
     * Downloads only deltas instead of full reactions map on every change
     */
    fun listenForMessageReactionsOptimized(): Flow<Map<Long, String>> = callbackFlow {
        val userId = try {
            getCurrentUserId()
        } catch (e: Exception) {
            Log.e(TAG, "Cannot listen for reactions - not authenticated", e)
            close()
            return@callbackFlow
        }

        val reactionsRef = database.reference
            .child(USERS_PATH)
            .child(userId)
            .child(MESSAGE_REACTIONS_PATH)

        // Local cache to maintain full map
        val reactionsCache = mutableMapOf<Long, String>()

        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val id = snapshot.key?.toLongOrNull() ?: return
                val reaction = when (val value = snapshot.child("reaction").value) {
                    is String -> value
                    else -> return
                }
                reactionsCache[id] = reaction
                trySend(reactionsCache.toMap()).isSuccess
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                val id = snapshot.key?.toLongOrNull() ?: return
                val reaction = when (val value = snapshot.child("reaction").value) {
                    is String -> value
                    else -> return
                }
                reactionsCache[id] = reaction
                trySend(reactionsCache.toMap()).isSuccess
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {
                val id = snapshot.key?.toLongOrNull() ?: return
                reactionsCache.remove(id)
                trySend(reactionsCache.toMap()).isSuccess
            }

            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {
                // Not used for maps
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Reactions optimized listener cancelled: ${error.message}")
            }
        }

        reactionsRef.addChildEventListener(listener)
        awaitClose {
            reactionsRef.removeEventListener(listener)
        }
    }

    // ==========================================
    // REGION: MMS Attachment Handling
    // ==========================================

    /**
     * Uploads MMS attachments to Cloudflare R2 storage with optional E2EE encryption.
     *
     * This method handles the complete attachment upload flow:
     * 1. Reads attachment bytes from ContentProvider or cached data
     * 2. Encrypts the data if E2EE is enabled
     * 3. Gets a presigned upload URL from R2 via Cloud Function
     * 4. Uploads directly to R2 using HTTP PUT
     * 5. Confirms the upload with Cloud Function
     * 6. Falls back to inline Base64 data for small files if R2 fails
     *
     * ## Usage Tracking
     *
     * Uploads are checked against user's quota before proceeding. If the user
     * has exceeded their storage/bandwidth limit, attachments are skipped.
     *
     * ## Supported Types
     *
     * Only images and videos are uploaded to R2. Other attachment types
     * (like vCards) have only metadata stored.
     *
     * @param userId Current user's Firebase UID
     * @param messageId The MMS message ID for logging/tracking
     * @param attachments List of MMS attachments to upload
     * @return List of attachment metadata maps including R2 keys or inline data
     */
    private suspend fun uploadMmsAttachments(
        userId: String,
        messageId: Long,
        attachments: List<MmsAttachment>
    ): List<Map<String, Any?>> {
        val uploadedAttachments = mutableListOf<Map<String, Any?>>()
        val isE2eeEnabled = preferencesManager.e2eeEnabled.value

        for (attachment in attachments) {
            try {
                // Only upload images and videos
                if (!attachment.isImage() && !attachment.isVideo()) {
                    // For non-media attachments, just include metadata
                    uploadedAttachments.add(mapOf(
                        "id" to attachment.id,
                        "contentType" to attachment.contentType,
                        "fileName" to if (isE2eeEnabled) "attachment" else (attachment.fileName ?: "attachment"),
                        "type" to getAttachmentType(attachment),
                        "encrypted" to false
                    ))
                    continue
                }

                // Get file content - try multiple approaches
                var attachmentBytes: ByteArray? = null

                // First try: use pre-loaded data if available
                if (attachment.data != null) {
                    attachmentBytes = attachment.data
                    Log.d(TAG, "Using pre-loaded attachment data for ${attachment.id}")
                }

                // Second try: read from content URI
                if (attachmentBytes == null && !attachment.filePath.isNullOrEmpty()) {
                    try {
                        val fileUri = Uri.parse(attachment.filePath)
                        attachmentBytes = context.contentResolver.openInputStream(fileUri)?.use { stream ->
                            stream.readBytes()
                        }
                        if (attachmentBytes != null) {
                            Log.d(TAG, "Loaded attachment from URI: ${attachment.filePath}")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to load from content URI: ${e.message}")
                    }
                }

                // Third try: query MMS part directly if we have a part ID
                if (attachmentBytes == null && attachment.id > 0) {
                    try {
                        val partUri = Uri.parse("content://mms/part/${attachment.id}")
                        attachmentBytes = context.contentResolver.openInputStream(partUri)?.use { stream ->
                            stream.readBytes()
                        }
                        if (attachmentBytes != null) {
                            Log.d(TAG, "Loaded attachment from part ID: ${attachment.id}")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to load from part ID: ${e.message}")
                    }
                }

                if (attachmentBytes == null) {
                    Log.e(TAG, "Could not load attachment ${attachment.id} - skipping")
                    continue
                }

                val extension = getFileExtension(attachment.contentType)
                val fileName = "${attachment.id}.$extension"

                // Use the loaded bytes
                val bytes = attachmentBytes
                var bytesToUpload = bytes
                var isEncrypted = false

                // Note: E2EE for MMS attachments is disabled for self-sync
                // MMS attachments are syncing to your own devices (not sending to another user)
                // They are already protected by Firebase Security Rules and HTTPS
                // E2EE is designed for end-to-end messaging between different users
                // TODO: Implement symmetric encryption for multi-device self-sync if needed
                Log.d(TAG, "MMS attachment will be uploaded unencrypted (self-sync): ${attachment.id}")

                val usageCheck = runCatching {
                    usageTracker.isUploadAllowed(
                        userId = userId,
                        bytes = bytesToUpload.size.toLong(),
                        countsTowardStorage = true
                    )
                }.getOrElse {
                    UsageCheck(true)
                }

                if (!usageCheck.allowed) {
                    Log.w(
                        TAG,
                        "Skipping attachment ${attachment.id} due to usage limit: ${usageCheck.reason}"
                    )
                    continue
                }

                var r2Key: String? = null
                var useInlineData = false

                try {
                    // Step 1: Get presigned upload URL from R2
                    val uploadUrlData = hashMapOf(
                        "fileName" to fileName,
                        "contentType" to attachment.contentType,
                        "fileSize" to bytesToUpload.size,
                        "transferType" to "mms",
                        "messageId" to messageId.toString()
                    )

                    val uploadUrlResult = functions
                        .getHttpsCallable("getR2UploadUrl")
                        .call(uploadUrlData)
                        .await()

                    @Suppress("UNCHECKED_CAST")
                    val uploadResponse = uploadUrlResult.data as? Map<String, Any>
                    val uploadUrl = uploadResponse?.get("uploadUrl") as? String
                    r2Key = uploadResponse?.get("fileKey") as? String  // Cloud Function returns "fileKey"
                    val fileId = uploadResponse?.get("fileId") as? String

                    if (uploadUrl == null || r2Key == null || fileId == null) {
                        throw Exception("Failed to get R2 upload URL")
                    }

                    // Step 2: Upload directly to R2 via presigned URL
                    val uploaded = withContext(Dispatchers.IO) {
                        uploadBytesToR2(uploadUrl, bytesToUpload, attachment.contentType)
                    }

                    if (!uploaded) {
                        throw Exception("Failed to upload to R2")
                    }

                    // Step 3: Confirm upload
                    val confirmData = hashMapOf(
                        "fileId" to fileId,
                        "r2Key" to r2Key,
                        "fileName" to fileName,
                        "fileSize" to bytesToUpload.size,
                        "contentType" to attachment.contentType,
                        "transferType" to "mms"
                    )

                    functions
                        .getHttpsCallable("confirmR2Upload")
                        .call(confirmData)
                        .await()

                    Log.d(TAG, "Uploaded attachment to R2: ${attachment.id} -> $r2Key (encrypted: $isEncrypted)")
                } catch (e: Exception) {
                    Log.e(TAG, "Error uploading attachment ${attachment.id} to R2: ${e.message}", e)
                    // BANDWIDTH OPTIMIZATION: Only use inline data for tiny attachments (< 50 KB)
                    // Larger attachments should use R2 to avoid bloating Firebase RTDB
                    if (bytesToUpload.size < 50_000) {
                        useInlineData = true
                        r2Key = null
                        Log.w(TAG, "Falling back to inline data for small attachment ${attachment.id} (${bytesToUpload.size} bytes)")
                    } else {
                        Log.e(TAG, "Attachment ${attachment.id} too large for inline (${bytesToUpload.size} bytes), skipping. Fix R2 upload!")
                        continue
                    }
                }

                val metadata = mutableMapOf<String, Any?>(
                    "id" to attachment.id,
                    "contentType" to attachment.contentType,
                    "fileName" to if (isE2eeEnabled) "attachment.$extension" else (attachment.fileName ?: "attachment.$extension"),
                    "type" to getAttachmentType(attachment),
                    "encrypted" to isEncrypted,
                    "originalSize" to bytes.size
                )

                if (r2Key != null) {
                    metadata["r2Key"] = r2Key
                } else if (useInlineData) {
                    metadata["inlineData"] = android.util.Base64.encodeToString(bytesToUpload, android.util.Base64.NO_WRAP)
                    metadata["isInline"] = true
                }

                uploadedAttachments.add(metadata)

                runCatching {
                    usageTracker.recordUpload(
                        userId = userId,
                        bytes = bytesToUpload.size.toLong(),
                        category = UsageCategory.MMS,
                        countsTowardStorage = r2Key != null
                    )
                }.onFailure { error ->
                    Log.w(TAG, "Failed to record MMS usage for ${attachment.id}: ${error.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error uploading attachment ${attachment.id}", e)
            }
        }

        return uploadedAttachments
    }

    /**
     * Upload bytes directly to R2 via presigned URL
     */
    private fun uploadBytesToR2(uploadUrl: String, data: ByteArray, contentType: String): Boolean {
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

    private fun loadMmsAttachmentsFromProvider(mmsId: Long): List<MmsAttachment> {
        if (mmsId <= 0) return emptyList()

        val list = mutableListOf<MmsAttachment>()
        val cursor = context.contentResolver.query(
            Uri.parse("content://mms/part"),
            arrayOf("_id", "ct", "name", "fn"),
            "mid = ?",
            arrayOf(mmsId.toString()),
            null
        ) ?: return emptyList()

        cursor.use {
            while (it.moveToNext()) {
                val partId = it.getLong(0)
                val contentType = it.getString(1) ?: ""
                val fileName = it.getString(2) ?: it.getString(3)

                if (contentType == "text/plain" || contentType == "application/smil") {
                    continue
                }

                val partUri = "content://mms/part/$partId"

                // Load actual attachment data for media files (critical for sync)
                val data: ByteArray? = if (contentType.startsWith("image/") ||
                    contentType.startsWith("video/") ||
                    contentType.startsWith("audio/")) {
                    try {
                        context.contentResolver.openInputStream(Uri.parse(partUri))?.use { stream ->
                            stream.readBytes()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to read MMS attachment data for part $partId: ${e.message}")
                        null
                    }
                } else null

                list.add(
                    MmsAttachment(
                        id = partId,
                        contentType = contentType,
                        filePath = partUri,
                        data = data,
                        fileName = fileName
                    )
                )
            }
        }

        return list
    }

    fun getFirebaseMessageKey(message: SmsMessage): String {
        return if (message.isMms) {
            "mms_${message.id}"
        } else {
            message.id.toString()
        }
    }

    /**
     * Fetch recent Firebase message keys for deletion reconciliation.
     */
    suspend fun fetchRecentMessageKeys(limit: Int = 1000): Set<String> {
        return try {
            val userId = getCurrentUserId()
            val messagesRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child(MESSAGES_PATH)
                .orderByChild("date")
                .limitToLast(limit)

            val snapshot = messagesRef.get().await()
            val keys = mutableSetOf<String>()
            snapshot.children.forEach { child ->
                child.key?.let { keys.add(it) }
            }
            keys
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching recent message keys", e)
            emptySet()
        }
    }

    /**
     * Get file extension from content type
     */
    private fun getFileExtension(contentType: String): String {
        return when {
            contentType.contains("jpeg") || contentType.contains("jpg") -> "jpg"
            contentType.contains("png") -> "png"
            contentType.contains("gif") -> "gif"
            contentType.contains("webp") -> "webp"
            contentType.contains("mp4") -> "mp4"
            contentType.contains("3gpp") || contentType.contains("3gp") -> "3gp"
            contentType.contains("mpeg") || contentType.contains("mp3") -> "mp3"
            contentType.contains("ogg") -> "ogg"
            contentType.contains("vcard") -> "vcf"
            else -> "bin"
        }
    }

    /**
     * Get attachment type string
     */
    private fun getAttachmentType(attachment: MmsAttachment): String {
        return when {
            attachment.isImage() -> "image"
            attachment.isVideo() -> "video"
            attachment.isAudio() -> "audio"
            attachment.isVCard() -> "vcard"
            else -> "file"
        }
    }

    /**
     * Syncs multiple messages to Firebase using batched parallel writes.
     *
     * This method is optimized for bulk sync operations (like initial sync or
     * history sync requests). Messages are processed in parallel chunks to
     * balance throughput with memory usage.
     *
     * ## Processing Strategy
     *
     * 1. Pre-filter RCS/RBM messages (not supported)
     * 2. Split remaining messages into chunks of 50
     * 3. Process each chunk in parallel using coroutines
     * 4. Add 100ms delay between chunks to avoid Firebase rate limits
     *
     * @param messages List of SMS/MMS messages to sync
     */
    suspend fun syncMessages(
        messages: List<SmsMessage>,
        skipAttachments: Boolean = false,
        onProgress: ((syncedCount: Int, totalCount: Int) -> Unit)? = null
    ) {
        // Early exit if no network - avoid looping through messages
        if (!NetworkUtils.isNetworkAvailable(context)) {
            Log.w(TAG, "No network available, skipping batch sync of ${messages.size} messages")
            return
        }

        if (messages.isEmpty()) return

        val startTime = System.currentTimeMillis()
        Log.d(TAG, "Starting batched sync of ${messages.size} messages (skipAttachments=$skipAttachments)")

        // Filter out RCS/RBM messages upfront
        val filteredMessages = messages.filter { msg ->
            !msg.address.contains("@rbm.goog", ignoreCase = true) && !isRcsAddress(msg.address)
        }

        Log.d(TAG, "After filtering: ${filteredMessages.size} messages to sync")
        if (filteredMessages.isEmpty()) return

        val totalCount = filteredMessages.size
        var syncedCount = 0

        val userId = getCurrentUserId()
        val isE2eeEnabled = preferencesManager.e2eeEnabled.value
        val cachedDeviceId = if (isE2eeEnabled) e2eeManager.getDeviceId() ?: "android" else "android"
        val cachedDeviceKeys = if (isE2eeEnabled) e2eeManager.getDevicePublicKeys(userId) else emptyMap()

        val batchCandidates = mutableListOf<SmsMessage>()
        val individualMessages = mutableListOf<SmsMessage>()

        for (message in filteredMessages) {
            if (message.isMms && !skipAttachments) {
                individualMessages.add(message)
            } else {
                batchCandidates.add(message)
            }
        }

        val chunkSize = 100
        val chunks = batchCandidates.chunked(chunkSize)
        val batchCallable = functions.getHttpsCallable("syncMessageBatch")

        for ((index, chunk) in chunks.withIndex()) {
            Log.d(TAG, "Processing batch ${index + 1}/${chunks.size} (${chunk.size} messages)")

            val payloads = mutableListOf<Map<String, Any?>>()
            for (message in chunk) {
                val payload = buildMessagePayload(
                    message = message,
                    userId = userId,
                    skipAttachments = true,
                    cachedDeviceId = cachedDeviceId,
                    cachedDeviceKeys = cachedDeviceKeys
                )
                if (payload != null) {
                    payloads.add(payload)
                }
            }

            if (payloads.isEmpty()) {
                continue
            }

            try {
                val result = batchCallable.call(mapOf("messages" to payloads)).await()
                val data = result.data as? Map<*, *>
                val count = (data?.get("count") as? Number)?.toInt() ?: payloads.size
                syncedCount += count
                onProgress?.invoke(syncedCount, totalCount)
            } catch (e: Exception) {
                Log.e(TAG, "Batch sync failed, falling back to individual sync", e)
                for (message in chunk) {
                    try {
                        syncMessage(message, skipAttachments = true)
                        syncedCount++
                        onProgress?.invoke(syncedCount, totalCount)
                    } catch (inner: Exception) {
                        Log.e(TAG, "Error syncing message ${message.id}: ${inner.message}")
                    }
                }
            }

            if (index < chunks.size - 1) {
                delay(50)
            }
        }

        for (message in individualMessages) {
            try {
                syncMessage(message, skipAttachments = false)
                syncedCount++
                onProgress?.invoke(syncedCount, totalCount)
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing message ${message.id}: ${e.message}")
            }
        }

        val duration = System.currentTimeMillis() - startTime
        Log.d(TAG, "Batched sync completed: ${filteredMessages.size} messages in ${duration}ms")
    }

    private suspend fun buildMessagePayload(
        message: SmsMessage,
        userId: String,
        skipAttachments: Boolean,
        cachedDeviceId: String,
        cachedDeviceKeys: Map<String, String>
    ): Map<String, Any?>? {
        val isE2eeEnabled = preferencesManager.e2eeEnabled.value
        val messageKey = getFirebaseMessageKey(message)
        val conversationAddress = getConversationAddress(message) ?: return null

        val messageData = mutableMapOf<String, Any?>(
            "id" to messageKey,
            "sourceId" to message.id,
            "sourceType" to if (message.isMms) "mms" else "sms",
            "address" to conversationAddress,
            "date" to message.date,
            "type" to message.type,
            "timestamp" to ServerValue.TIMESTAMP
        )

        messageData.applyEncryptionPayload(
            userId = userId,
            body = message.body,
            cachedDeviceId = cachedDeviceId,
            cachedDeviceKeys = cachedDeviceKeys
        )

        if (!isE2eeEnabled) {
            message.contactName?.let { messageData["contactName"] = it }
        }
        message.subId?.let { messageData["subId"] = it }

        if (message.isMms) {
            messageData["isMms"] = true
            if (!skipAttachments) {
                val attachments = if (message.mmsAttachments.isNotEmpty()) {
                    message.mmsAttachments
                } else {
                    loadMmsAttachmentsFromProvider(message.id)
                }
                if (attachments.isNotEmpty()) {
                    val attachmentUrls = uploadMmsAttachments(userId, message.id, attachments)
                    if (attachmentUrls.isNotEmpty()) {
                        messageData["attachments"] = attachmentUrls
                    }
                }
            }
        }

        return mapOf(
            "messageId" to messageKey,
            "message" to messageData
        )
    }

    /**
     * Decrypt an encrypted message body
     * Returns the decrypted plaintext or the original body if not encrypted/decryption fails
     */
    fun decryptMessageBody(body: String?, isEncrypted: Boolean?): String {
        if (body == null) return ""
        if (isEncrypted != true) return body

        return try {
            val decrypted = e2eeManager.decryptMessage(body)
            if (decrypted != null) {
                Log.d(TAG, "Message decrypted successfully")
                decrypted
            } else {
                Log.w(TAG, "Decryption returned null, using original body")
                body
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decrypting message", e)
            body
        }
    }

    private suspend fun MutableMap<String, Any?>.applyEncryptionPayload(
        userId: String,
        body: String?,
        cachedDeviceId: String? = null,
        cachedDeviceKeys: Map<String, String>? = null  // Deprecated: kept for backward compatibility
    ): MutableMap<String, Any?> {
        val safeBody = body ?: ""
        val isE2eeEnabled = preferencesManager.e2eeEnabled.value
        if (!isE2eeEnabled) {
            this["body"] = safeBody
            this["encrypted"] = false
            return this
        }

        val deviceId = cachedDeviceId ?: (e2eeManager.getDeviceId() ?: "android")
        val dataKey = ByteArray(32).apply { SecureRandom().nextBytes(this) }
        val bodyToEncrypt = safeBody.ifBlank { "[MMS]" }
        val encryptedBodyResult = e2eeManager.encryptMessageBody(dataKey, bodyToEncrypt)

        // NEW APPROACH: Encrypt data key once with sync group public key (not per-device)
        // All devices in the sync group share the same keypair, eliminating backfill need
        val syncGroupPublicKey = e2eeManager.getSyncGroupPublicKeyX963()
        val envelope = if (!syncGroupPublicKey.isNullOrBlank()) {
            e2eeManager.encryptDataKeyForDevice(syncGroupPublicKey, dataKey)
        } else {
            null
        }

        if (encryptedBodyResult != null && !envelope.isNullOrBlank()) {
            // SUCCESS: Store encrypted message with single envelope
            val (ciphertext, nonce) = encryptedBodyResult
            this["body"] = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
            this["nonce"] = Base64.encodeToString(nonce, Base64.NO_WRAP)
            this["e2ee_envelope"] = envelope  // NEW: Single envelope for sync group
            this["keyVersion"] = 3  // Version 3 = shared sync group keypair approach
            this["senderDeviceId"] = deviceId
            this["encrypted"] = true

            Log.d(TAG, "Message encrypted successfully with sync group keypair (v3)")
        } else {
            // FAILURE: Encryption failed, store redacted message
            val failureReason = when {
                encryptedBodyResult == null && envelope == null -> "encryption and key envelope both failed"
                encryptedBodyResult == null -> "body encryption failed"
                envelope == null -> "sync group key not available"
                else -> "unknown error"
            }

            Log.w(TAG, "E2EE encryption failed: $failureReason")
            this["body"] = "[Encrypted]"
            this["encrypted"] = false
            this["e2eeFailed"] = true
            this["e2eeFailureReason"] = failureReason
            this["redacted"] = true
        }

        return this
    }

    // ==========================================
    // REGION: Outgoing Message Handling (Desktop -> Android)
    // ==========================================

    /**
     * Listens for outgoing messages queued from desktop/web clients.
     *
     * When a user composes and sends a message from the web interface or macOS
     * app, the message is written to Firebase's outgoing_messages node. This
     * method listens for those messages and emits them so OutgoingMessageService
     * can send them via Android's SMS/MMS APIs.
     *
     * ## Message Flow
     *
     * 1. User types message on desktop/web
     * 2. Desktop writes to Firebase: users/{uid}/outgoing_messages/{messageId}
     * 3. This listener detects the new child
     * 4. OutgoingMessageService processes the message
     * 5. Android sends the SMS/MMS
     * 6. Message is deleted from outgoing_messages and written to messages
     *
     * @return Flow emitting Map<String, Any?> for each new outgoing message
     */
    fun listenForOutgoingMessages(): Flow<Map<String, Any?>> = callbackFlow {
        val userId = runCatching { getCurrentUserId() }.getOrNull()
        if (userId == null) {
            close()
            return@callbackFlow
        }

        val outgoingRef = database.reference
            .child(USERS_PATH)
            .child(userId)
            .child("outgoing_messages")

        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val messageId = snapshot.key ?: return
                val messageData = snapshot.value as? Map<String, Any?> ?: return

                // Add message ID to the data
                val dataWithId = messageData.toMutableMap()
                dataWithId["_messageId"] = messageId
                dataWithId["_messageRef"] = snapshot.ref

                // Decrypt body if encrypted (for future when web also encrypts)
                val isEncrypted = dataWithId["encrypted"] as? Boolean ?: false
                val body = dataWithId["body"] as? String
                if (isEncrypted && body != null) {
                    dataWithId["body"] = decryptMessageBody(body, true)
                }

                trySend(dataWithId)
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Listen for outgoing messages cancelled", error.toException())
            }
        }

        outgoingRef.addChildEventListener(listener)

        awaitClose {
            Log.d(TAG, "Cleaning up outgoing messages listener for user: $userId")
            outgoingRef.removeEventListener(listener)
        }
    }

    // ==========================================
    // REGION: Device Pairing Management
    // ==========================================

    /**
     * Retrieves the list of paired desktop/web devices.
     *
     * Uses Cloud Functions instead of direct Firebase reads to avoid OOM issues.
     * The Firebase SDK eagerly syncs all data under a node, which can cause
     * out-of-memory crashes with large message histories.
     *
     * ## Implementation Notes
     *
     * - Uses NonCancellable to ensure the query completes even if the UI navigates away
     * - Filters out Android devices (we only show desktop/web clients)
     * - Updates the local cache for fast hasPairedDevices() checks
     *
     * @return List of PairedDevice objects representing connected desktop/web clients
     */
    suspend fun getPairedDevices(): List<PairedDevice> = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
        try {
            Log.d(TAG, "=== GET PAIRED DEVICES (via Cloud Function) ===")

            // Use getDeviceInfoV2 Cloud Function which already returns devices list
            val deviceInfo = getDeviceInfo()
            if (deviceInfo == null) {
                Log.w(TAG, "getDeviceInfo returned null, returning empty list")
                updatePairedDevicesCache(context, false, 0)
                return@withContext emptyList()
            }

            Log.d(TAG, "=== RESULT: Found ${deviceInfo.devices.size} paired devices ===")

            // Update cache for fast checks
            updatePairedDevicesCache(context, deviceInfo.devices.isNotEmpty(), deviceInfo.devices.size)

            deviceInfo.devices
        } catch (e: Exception) {
            Log.e(TAG, "ERROR getting paired devices: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Pair a new device
     */
    suspend fun pairDevice(deviceId: String, deviceName: String): Boolean {
        try {
            val userId = getCurrentUserId()
            val deviceRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child(DEVICES_PATH)
                .child(deviceId)

            val deviceData = mapOf(
                "name" to deviceName,
                "platform" to "web",
                "isPaired" to true,
                "pairedAt" to ServerValue.TIMESTAMP,
                "lastSeen" to ServerValue.TIMESTAMP
            )

            deviceRef.setValue(deviceData).await()
            Log.d(TAG, "Device paired: $deviceName")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error pairing device", e)
            return false
        }
    }

    /**
     * Unpair a device
     */
    suspend fun unpairDevice(deviceId: String): Boolean {
        try {
            val userId = getCurrentUserId()
            val deviceRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child(DEVICES_PATH)
                .child(deviceId)

            deviceRef.removeValue().await()
            Log.d(TAG, "Device unpaired: $deviceId")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error unpairing device", e)
            return false
        }
    }

    /**
     * Clean up duplicate device entries, keeping only the most recent one per device name.
     * This handles cases where multiple entries were created for the same device.
     */
    suspend fun cleanupDuplicateDevices(): Int {
        try {
            val userId = getCurrentUserId()
            val devices = getPairedDevices()

            // Group devices by name
            val devicesByName = devices.groupBy { it.name }

            var removedCount = 0

            for ((name, deviceList) in devicesByName) {
                if (deviceList.size > 1) {
                    // Sort by lastSeen descending, keep the most recent
                    val sorted = deviceList.sortedByDescending { it.lastSeen }
                    val toKeep = sorted.first()
                    val toRemove = sorted.drop(1)

                    Log.d(TAG, "Device '$name' has ${deviceList.size} entries, keeping ${toKeep.id} (lastSeen: ${toKeep.lastSeen})")

                    for (device in toRemove) {
                        Log.d(TAG, "Removing duplicate device: ${device.id} (lastSeen: ${device.lastSeen})")
                        unpairDevice(device.id)
                        removedCount++
                    }
                }
            }

            Log.d(TAG, "Cleanup complete. Removed $removedCount duplicate device entries.")
            return removedCount
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up duplicate devices", e)
            return 0
        }
    }

    /**
     * Remove all old device entries (older than specified days)
     */
    suspend fun removeOldDevices(olderThanDays: Int = 30): Int {
        try {
            val userId = getCurrentUserId()
            val devices = getPairedDevices()
            val cutoffTime = System.currentTimeMillis() - (olderThanDays * 24 * 60 * 60 * 1000L)

            var removedCount = 0

            for (device in devices) {
                if (device.lastSeen < cutoffTime && device.lastSeen > 0) {
                    Log.d(TAG, "Removing old device: ${device.name} (${device.id}), lastSeen: ${device.lastSeen}")
                    unpairDevice(device.id)
                    removedCount++
                }
            }

            Log.d(TAG, "Removed $removedCount old device entries.")
            return removedCount
        } catch (e: Exception) {
            Log.e(TAG, "Error removing old devices", e)
            return 0
        }
    }

    /**
     * Pair with token from web (phone scans QR code from website)
     */
    @Deprecated("Pairing is now initiated by the phone; use generatePairingToken() and redeem on desktop.")
    suspend fun pairWithToken(token: String): Boolean {
        Log.w(TAG, "pairWithToken is deprecated; use device-scoped pairing flow")
        return false
    }

    /**
     * Generate pairing token (OLD METHOD - for phone generating QR code)
     * Kept for backwards compatibility
     */
    suspend fun generatePairingToken(deviceType: String = "desktop"): String {
        val userId = getCurrentUserId()
        val result = functions
            .getHttpsCallable("createPairingToken")
            .call(mapOf("deviceType" to deviceType))
            .await()
        val data = result.data as? Map<*, *> ?: throw Exception("Invalid pairing response")
        return data["token"] as? String ?: throw Exception("Missing pairing token")
    }

    /**
     * Completes a device pairing request after scanning QR code from Web/macOS.
     *
     * This is called when the user scans a pairing QR code displayed on the
     * desktop/web client and taps "Approve" or "Reject" on the Android device.
     *
     * ## Pairing Flow
     *
     * 1. Desktop/web displays QR code containing pairing token
     * 2. User scans QR code with Android app
     * 3. App displays pairing request dialog
     * 4. User approves or rejects
     * 5. This method calls Cloud Function to complete pairing
     * 6. Desktop receives notification and starts syncing
     *
     * ## Protocol Versions
     *
     * - V2 (preferred): Supports device limits, persistent device IDs
     * - V1 (fallback): Legacy protocol for older web/desktop clients
     *
     * @param token The pairing token from the QR code
     * @param approved True if user approved, false if rejected
     * @return CompletePairingResult indicating success, rejection, or error
     */
    suspend fun completePairing(token: String, approved: Boolean): CompletePairingResult {
        Log.d(TAG, "=== COMPLETE PAIRING START ===")
        Log.d(TAG, "Token: ${token.take(8)}..., Approved: $approved")

        // Ensure user is authenticated
        var currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser == null) {
            Log.d(TAG, "No authenticated user, signing in anonymously...")
            try {
                val authResult = FirebaseAuth.getInstance().signInAnonymously().await()
                currentUser = authResult.user
                Log.d(TAG, "Signed in anonymously as: ${currentUser?.uid}")
            } catch (authError: Exception) {
                Log.e(TAG, "Failed to sign in anonymously", authError)
                return CompletePairingResult.Error("Authentication failed: ${authError.message}")
            }
        } else {
            Log.d(TAG, "Using current user: ${currentUser.uid}")
        }

        // Try V2 first
        return try {
            Log.d(TAG, "Trying V2 pairing...")
            val result = completePairingV2(token, approved)
            Log.d(TAG, "V2 pairing result: $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "V2 pairing FAILED with exception: ${e.message}", e)
            Log.d(TAG, "Falling back to V1 pairing...")
            try {
                val v1Result = completePairingV1(token, approved)
                Log.d(TAG, "V1 pairing result: $v1Result")
                v1Result
            } catch (v1Error: Exception) {
                Log.e(TAG, "V1 pairing also FAILED: ${v1Error.message}", v1Error)
                CompletePairingResult.Error("Pairing failed: ${e.message ?: v1Error.message}")
            }
        }
    }

    /**
     * V2 Pairing Completion - Uses approvePairingV2 Cloud Function
     * Supports device limits and persistent device IDs
     */
    private suspend fun completePairingV2(token: String, approved: Boolean): CompletePairingResult {
        Log.d(TAG, "Completing V2 pairing for token: ${token.take(8)}..., approved: $approved")

        val result = functions
            .getHttpsCallable("approvePairingV2")
            .call(mapOf(
                "token" to token,
                "approved" to approved
            ))
            .await()

        Log.d(TAG, "V2 Pairing completion result: $result")

        val data = result.data as? Map<*, *>
            ?: return CompletePairingResult.Error("Invalid response from server")

        val success = data["success"] as? Boolean ?: false
        val status = data["status"] as? String
        val error = data["error"] as? String

        return when {
            // Device limit reached - return special error with upgrade info
            error == "device_limit" -> {
                val currentDevices = (data["currentDevices"] as? Number)?.toInt() ?: 0
                val limit = (data["limit"] as? Number)?.toInt() ?: 3
                val message = data["message"] as? String
                    ?: "Device limit reached ($currentDevices/$limit). Upgrade to Pro for unlimited devices."
                Log.w(TAG, "Device limit reached: $message")
                CompletePairingResult.Error(message)
            }

            success && status == "approved" -> {
                val deviceId = data["deviceId"] as? String
                val userId = data["userId"] as? String
                val isRePairing = data["isRePairing"] as? Boolean ?: false

                if (isRePairing) {
                    Log.d(TAG, "V2 Re-pairing approved. Device ID: $deviceId (already existed)")
                } else {
                    Log.d(TAG, "V2 Pairing approved. Device ID: $deviceId, UserID: $userId")
                }

                CompletePairingResult.Approved(deviceId)
            }

            success && status == "rejected" -> {
                Log.d(TAG, "V2 Pairing rejected by user")
                CompletePairingResult.Rejected
            }

            else -> {
                val errorMessage = data["message"] as? String ?: "Unexpected status: $status"
                CompletePairingResult.Error(errorMessage)
            }
        }
    }

    /**
     * V1 Pairing Completion - Legacy Cloud Function
     */
    private suspend fun completePairingV1(token: String, approved: Boolean): CompletePairingResult {
        try {
            Log.d(TAG, "Completing V1 pairing for token: ${token.take(8)}..., approved: $approved")

            val result = functions
                .getHttpsCallable("completePairing")
                .call(mapOf(
                    "token" to token,
                    "approved" to approved
                ))
                .await()

            Log.d(TAG, "V1 Pairing completion result: $result")

            val data = result.data as? Map<*, *>
                ?: return CompletePairingResult.Error("Invalid response from server")

            val success = data["success"] as? Boolean ?: false
            val status = data["status"] as? String ?: "unknown"

            return when {
                success && status == "approved" -> {
                    val deviceId = data["deviceId"] as? String
                    val userId = data["userId"] as? String

                    Log.d(TAG, "V1 Pairing approved. Device ID: $deviceId, UserID: $userId")
                    CompletePairingResult.Approved(deviceId)
                }
                success && status == "rejected" -> {
                    Log.d(TAG, "V1 Pairing rejected by user")
                    CompletePairingResult.Rejected
                }
                else -> {
                    CompletePairingResult.Error("Unexpected status: $status")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error completing V1 pairing", e)
            return CompletePairingResult.Error(e.message ?: "Failed to complete pairing")
        }
    }

    /**
     * Get device info including device count and limits
     * Uses getDeviceInfoV2 Cloud Function
     *
     * Uses NonCancellable to prevent issues when navigating away from screens.
     */
    suspend fun getDeviceInfo(): DeviceInfoResult? = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
        try {
            // Ensure we're signed in before calling cloud function
            val userId = try {
                getCurrentUserId()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get userId for getDeviceInfo: ${e.message}")
                return@withContext null
            }
            Log.d(TAG, "Fetching device info from getDeviceInfoV2 (user: $userId)")

            val result = functions
                .getHttpsCallable("getDeviceInfoV2")
                .call(emptyMap<String, Any>())
                .await()

            val data = result.data as? Map<*, *>
            if (data == null) {
                Log.w(TAG, "getDeviceInfoV2 returned null data")
                return@withContext null
            }

            val success = data["success"] as? Boolean ?: false
            if (!success) {
                Log.w(TAG, "getDeviceInfoV2 returned success=false")
                return@withContext null
            }

            val deviceCount = (data["deviceCount"] as? Number)?.toInt() ?: 0
            val deviceLimit = (data["deviceLimit"] as? Number)?.toInt() ?: 3
            val plan = data["plan"] as? String ?: "free"
            val canAddDevice = data["canAddDevice"] as? Boolean ?: (deviceCount < deviceLimit)

            // Parse devices list from Cloud Function response
            val devicesData = data["devices"] as? List<*> ?: emptyList<Any>()
            val devices = devicesData.mapNotNull { deviceData ->
                try {
                    val device = deviceData as? Map<*, *> ?: return@mapNotNull null
                    val deviceId = device["deviceId"] as? String ?: return@mapNotNull null
                    val platform = device["platform"] as? String ?: device["type"] as? String ?: "web"

                    // Skip Android devices - we only want desktop/web devices
                    if (platform == "android") return@mapNotNull null

                    PairedDevice(
                        id = deviceId,
                        name = device["name"] as? String ?: "Unknown Device",
                        platform = platform,
                        lastSeen = (device["lastSeen"] as? Number)?.toLong() ?: 0L,
                        syncStatus = null
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Error parsing device: ${e.message}")
                    null
                }
            }

            Log.d(TAG, "Device info: $deviceCount/$deviceLimit devices, plan=$plan, canAdd=$canAddDevice, parsedDevices=${devices.size}")

            DeviceInfoResult(
                deviceCount = deviceCount,
                deviceLimit = deviceLimit,
                plan = plan,
                canAddDevice = canAddDevice,
                devices = devices
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching device info", e)
            null
        }
    }

    /**
     * Parse QR code payload from Web/macOS
     * Returns pairing info if valid, null otherwise
     */
    fun parsePairingQrCode(qrData: String): PairingQrData? {
        return try {
            val json = org.json.JSONObject(qrData)
            val token = json.optString("token", "")
            val name = json.optString("name", "Desktop")
            val platform = json.optString("platform", "web")
            val version = json.optString("version", "1.0.0")
            val syncGroupId = json.optString("syncGroupId", "")

            if (token.isBlank()) {
                Log.w(TAG, "Invalid QR code: missing token")
                null
            } else {
                PairingQrData(token, name, platform, version, syncGroupId.ifBlank { null })
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse pairing QR code: ${e.message}")
            null
        }
    }

    /**
     * Get outgoing messages (snapshot, not listener)
     */
    suspend fun getOutgoingMessages(): Map<String, Map<String, Any?>> {
        try {
            val userId = getCurrentUserId()
            val outgoingRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child("outgoing_messages")

            val snapshot = outgoingRef.get().await()
            val messages = mutableMapOf<String, Map<String, Any?>>()

            snapshot.children.forEach { messageSnapshot ->
                val messageId = messageSnapshot.key ?: return@forEach
                val messageData = messageSnapshot.value as? Map<String, Any?> ?: return@forEach
                messages[messageId] = messageData
            }

            return messages
        } catch (e: Exception) {
            Log.e(TAG, "Error getting outgoing messages", e)
            return emptyMap()
        }
    }

    /**
     * Write sent message to messages collection with E2EE encryption
     */
    suspend fun writeSentMessage(messageId: String, address: String, body: String) {
        try {
            val userId = getCurrentUserId()
            val messageRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child(MESSAGES_PATH)
                .child(messageId)

            val messageData = mutableMapOf<String, Any?>(
                "id" to messageId,
                "address" to address,
                "date" to System.currentTimeMillis(),
                "type" to 2, // 2 = sent message
                "timestamp" to ServerValue.TIMESTAMP
            )

            messageData.applyEncryptionPayload(userId, body)

            messageRef.setValue(messageData).await()
            Log.d(TAG, "Sent message written to messages collection (encrypted: ${messageData["encrypted"] == true})")
        } catch (e: Exception) {
            Log.e(TAG, "Error writing sent message", e)
        }
    }

    /**
     * Write sent MMS message to messages collection with attachments
     */
    suspend fun writeSentMmsMessage(
        messageId: String,
        address: String,
        body: String,
        attachments: List<Map<String, Any?>>
    ) {
        try {
            val userId = getCurrentUserId()
            val messageRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child(MESSAGES_PATH)
                .child(messageId)

            val messageData = mutableMapOf<String, Any?>(
                "id" to messageId,
                "address" to address,
                "date" to System.currentTimeMillis(),
                "type" to 2, // 2 = sent message
                "timestamp" to ServerValue.TIMESTAMP,
                "isMms" to true,
                "attachments" to attachments
            )

            Log.d(TAG, "[FirebaseWrite] Writing sent MMS to Firebase - address: \"$address\" (normalized: \"${PhoneNumberUtils.normalizeForConversation(address)}\"), messageId: $messageId")

            messageData.applyEncryptionPayload(userId, body)

            messageRef.setValue(messageData).await()
            Log.d(TAG, "Sent MMS message written to messages collection with ${attachments.size} attachment(s)")
        } catch (e: Exception) {
            Log.e(TAG, "Error writing sent MMS message", e)
        }
    }

    /**
     * Delete outgoing message after processing
     */
    suspend fun deleteOutgoingMessage(messageId: String) {
        try {
            val userId = getCurrentUserId()
            val messageRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child("outgoing_messages")
                .child(messageId)

            messageRef.removeValue().await()
            Log.d(TAG, "Outgoing message deleted: $messageId")
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting outgoing message", e)
        }
    }

    /**
     * Sync a group to Firebase
     */
    suspend fun syncGroup(group: Group, members: List<GroupMember>) {
        try {
            val userId = getCurrentUserId()
            val groupRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child(GROUPS_PATH)
                .child(group.id.toString())

            val groupData = mutableMapOf<String, Any?>(
                "id" to group.id,
                "name" to group.name,
                "threadId" to group.threadId,
                "createdAt" to group.createdAt,
                "lastMessageAt" to group.lastMessageAt,
                "timestamp" to ServerValue.TIMESTAMP
            )

            // Add members as a nested map
            val membersData = members.associate {
                it.id.toString() to mapOf(
                    "phoneNumber" to it.phoneNumber,
                    "contactName" to (it.contactName ?: it.phoneNumber)
                )
            }
            groupData["members"] = membersData

            // Use retry logic for Firebase write operations
            RetryUtils.withFirebaseRetry("syncGroup ${group.id}") {
                groupRef.setValue(groupData).await()
            }
            Log.d(TAG, "Group synced: ${group.id} (${group.name})")
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing group after retries", e)
        }
    }

    /**
     * Sync all groups to Firebase
     */
    suspend fun syncGroups(groupsWithMembers: List<com.phoneintegration.app.data.database.GroupWithMembers>) {
        for (groupWithMembers in groupsWithMembers) {
            syncGroup(groupWithMembers.group, groupWithMembers.members)
        }
    }

    /**
     * Delete a group from Firebase
     */
    suspend fun deleteGroup(groupId: Long) {
        try {
            val userId = getCurrentUserId()
            val groupRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child(GROUPS_PATH)
                .child(groupId.toString())

            groupRef.removeValue().await()
            Log.d(TAG, "Group deleted from Firebase: $groupId")
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting group from Firebase", e)
        }
    }

    /**
     * Sync a call event to Firebase
     */
    suspend fun syncCallEvent(
        callId: String,
        phoneNumber: String,
        contactName: String?,
        callType: String, // "incoming" or "outgoing"
        callState: String  // "ringing", "active", "ended"
    ) {
        try {
            val userId = getCurrentUserId()
            val callRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child("calls")
                .child(callId)

            val callData = mutableMapOf<String, Any?>(
                "id" to callId,
                "phoneNumber" to phoneNumber,
                "callType" to callType,
                "callState" to callState,
                "timestamp" to ServerValue.TIMESTAMP,
                "date" to System.currentTimeMillis()
            )

            // Add contact name if available
            contactName?.let {
                callData["contactName"] = it
            }

            // Use retry logic for Firebase write operations
            RetryUtils.withFirebaseRetry("syncCallEvent $callId") {
                callRef.setValue(callData).await()
            }
            Log.d(TAG, "Call event synced: $callId - $callState")
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing call event after retries", e)
        }
    }

    /**
     * Update call state in Firebase
     */
    suspend fun updateCallState(callId: String, newState: String) {
        try {
            val userId = getCurrentUserId()
            val callRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child("calls")
                .child(callId)

            // Use retry logic for Firebase write operations
            RetryUtils.withFirebaseRetry("updateCallState $callId") {
                callRef.updateChildren(mapOf(
                    "callState" to newState,
                    "lastUpdated" to ServerValue.TIMESTAMP
                )).await()
            }

            Log.d(TAG, "Call state updated: $callId -> $newState")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating call state after retries", e)
        }
    }

    // ==========================================
    // REGION: Sync History Requests
    // ==========================================

    /**
     * Listens for sync history requests from Mac/Web clients.
     *
     * Desktop clients can request older message history to be synced on-demand.
     * This is more efficient than syncing all history upfront, especially for
     * users with large message archives.
     *
     * ## Request Processing
     *
     * 1. Client creates request at: users/{uid}/sync_requests/{requestId}
     * 2. This listener detects the new request
     * 3. processSyncHistoryRequest() loads and syncs the messages
     * 4. Status updates are written back to the request node
     *
     * @return Flow emitting SyncHistoryRequest for each new pending request
     */
    fun listenForSyncRequests(): Flow<SyncHistoryRequest> = callbackFlow {
        val userId = runCatching { getCurrentUserId() }.getOrNull()
        if (userId == null) {
            close()
            return@callbackFlow
        }

        val syncRequestsRef = database.reference
            .child(USERS_PATH)
            .child(userId)
            .child(SYNC_REQUESTS_PATH)

        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val requestId = snapshot.key ?: return
                val data = snapshot.value as? Map<String, Any?> ?: return

                val status = data["status"] as? String ?: "pending"
                if (status != "pending") return // Only process pending requests

                val request = SyncHistoryRequest(
                    id = requestId,
                    days = (data["days"] as? Number)?.toInt() ?: 30,
                    requestedAt = (data["requestedAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                    requestedBy = data["requestedBy"] as? String ?: "unknown"
                )

                trySend(request)
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Listen for sync requests cancelled", error.toException())
            }
        }

        syncRequestsRef.addChildEventListener(listener)

        awaitClose {
            syncRequestsRef.removeEventListener(listener)
        }
    }

    /**
     * Process a sync history request - load messages for the requested period and sync them.
     */
    suspend fun processSyncHistoryRequest(request: SyncHistoryRequest) {
        val userId = getCurrentUserId()
        val requestRef = database.reference
            .child(USERS_PATH)
            .child(userId)
            .child(SYNC_REQUESTS_PATH)
            .child(request.id)

        try {
            Log.d(TAG, "Processing sync history request: ${request.id}, days=${request.days}")

            // OPTIMIZATION: Check if data already exists in Firebase (recovered/reconnected account)
            // Only sync if this is a NEW account with no messages yet
            val messagesRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child(MESSAGES_PATH)

            val existingMessagesSnapshot = messagesRef.limitToFirst(10).get().await()
            val hasExistingMessages = existingMessagesSnapshot.exists() && existingMessagesSnapshot.childrenCount > 0

            if (hasExistingMessages) {
                val messageCount = existingMessagesSnapshot.childrenCount
                Log.i(TAG, "⏭️ SKIPPING sync - Account already has $messageCount+ messages in Firebase (recovered/reconnected account)")

                // Mark request as skipped (data already exists)
                requestRef.updateChildren(mapOf(
                    "status" to "skipped",
                    "reason" to "account_has_data",
                    "message" to "Account already has messages in Firebase. No sync needed.",
                    "existingMessages" to messageCount,
                    "completedAt" to ServerValue.TIMESTAMP
                )).await()

                return
            }

            Log.i(TAG, "✅ Account is NEW (no messages in Firebase). Proceeding with initial sync...")

            // Update status to in_progress
            requestRef.updateChildren(mapOf(
                "status" to "in_progress",
                "startedAt" to ServerValue.TIMESTAMP
            )).await()

            // Load messages for the requested period
            val smsRepository = SmsRepository(context)
            val messages = if (request.days <= 0) {
                // Load all messages (use a very large number of days)
                smsRepository.getMessagesFromLastDays(days = 3650) // ~10 years
            } else {
                smsRepository.getMessagesFromLastDays(days = request.days)
            }

            Log.d(TAG, "Loaded ${messages.size} messages for sync request ${request.id}")

            // Update progress
            requestRef.updateChildren(mapOf(
                "totalMessages" to messages.size,
                "syncedMessages" to 0
            )).await()

            // Sync messages in batches
            val batchSize = 50
            var syncedCount = 0

            messages.chunked(batchSize).forEach { batch ->
                syncMessages(batch)
                syncedCount += batch.size

                // Update progress
                requestRef.updateChildren(mapOf(
                    "syncedMessages" to syncedCount
                )).await()
            }

            // Mark as completed
            requestRef.updateChildren(mapOf(
                "status" to "completed",
                "completedAt" to ServerValue.TIMESTAMP,
                "syncedMessages" to messages.size
            )).await()

            Log.d(TAG, "Sync history request ${request.id} completed: ${messages.size} messages synced")

        } catch (e: Exception) {
            Log.e(TAG, "Error processing sync history request ${request.id}", e)

            // Mark as failed
            try {
                requestRef.updateChildren(mapOf(
                    "status" to "failed",
                    "error" to (e.message ?: "Unknown error"),
                    "failedAt" to ServerValue.TIMESTAMP
                )).await()
            } catch (updateError: Exception) {
                Log.e(TAG, "Failed to update request status", updateError)
            }
        }
    }

    /**
     * Process an E2EE key sync request for a specific device.
     *
     * NEW BEHAVIOR (v3 - Shared Sync Group Keypair):
     * Shares the Android device's sync group keypair (private key) with the requesting
     * macOS/web device. The requesting device imports this keypair and uses it for
     * all encryption/decryption, eliminating the need for per-device keyMaps and backfill.
     *
     * OLD BEHAVIOR (v2 - Per-Device Keys):
     * Re-encrypted the stored key backup to the requester's current public key.
     *
     * Security: The private key is encrypted during transit using ECDH with the
     * requester's ephemeral public key.
     */
    suspend fun processE2eeKeySyncRequest(requesterDeviceId: String, requesterPublicKeyX963: String) {
        val userId = getCurrentUserId()
        val requestRef = database.reference
            .child(USERS_PATH)
            .child(userId)
            .child(E2EE_KEY_REQUESTS_PATH)
            .child(requesterDeviceId)
        val responseRef = database.reference
            .child(USERS_PATH)
            .child(userId)
            .child(E2EE_KEY_RESPONSES_PATH)
            .child(requesterDeviceId)

        try {
            requestRef.updateChildren(mapOf("status" to "processing")).await()

            // Ensure E2EE keys are available (loads existing if already initialized)
            e2eeManager.initializeKeys()

            // Get sync group private key (Android's ECDH private key)
            val syncGroupPrivateKeyPKCS8 = e2eeManager.getSyncGroupPrivateKeyPKCS8()
            if (syncGroupPrivateKeyPKCS8.isNullOrBlank()) {
                responseRef.setValue(mapOf(
                    "status" to "error",
                    "error" to "Sync group private key not available",
                    "respondedAt" to ServerValue.TIMESTAMP
                )).await()
                requestRef.updateChildren(mapOf("status" to "error")).await()
                Log.e(TAG, "Sync group private key not available for key sync request")
                return
            }

            // Get sync group public key
            val syncGroupPublicKeyX963 = e2eeManager.getSyncGroupPublicKeyX963()
            if (syncGroupPublicKeyX963.isNullOrBlank()) {
                responseRef.setValue(mapOf(
                    "status" to "error",
                    "error" to "Sync group public key not available",
                    "respondedAt" to ServerValue.TIMESTAMP
                )).await()
                requestRef.updateChildren(mapOf("status" to "error")).await()
                Log.e(TAG, "Sync group public key not available for key sync request")
                return
            }

            // Encode private key as bytes for encryption
            val privateKeyBytes = android.util.Base64.decode(syncGroupPrivateKeyPKCS8, android.util.Base64.NO_WRAP)

            // Encrypt the private key for the requesting device
            val encryptedPrivateKeyEnvelope = e2eeManager.encryptDataKeyForDevice(requesterPublicKeyX963, privateKeyBytes)
            if (encryptedPrivateKeyEnvelope.isNullOrBlank()) {
                responseRef.setValue(mapOf(
                    "status" to "error",
                    "error" to "Failed to encrypt sync group private key for device",
                    "respondedAt" to ServerValue.TIMESTAMP
                )).await()
                requestRef.updateChildren(mapOf("status" to "error")).await()
                Log.e(TAG, "Failed to encrypt sync group private key for device: $requesterDeviceId")
                return
            }

            // Send the encrypted sync group keypair to the requesting device
            responseRef.setValue(mapOf(
                "status" to "ready",
                "encryptedPrivateKeyEnvelope" to encryptedPrivateKeyEnvelope,
                "syncGroupPublicKeyX963" to syncGroupPublicKeyX963,
                "keyVersion" to 3,  // Version 3 = shared sync group keypair
                "respondedAt" to ServerValue.TIMESTAMP
            )).await()

            requestRef.updateChildren(mapOf(
                "status" to "completed",
                "completedAt" to ServerValue.TIMESTAMP
            )).await()

            Log.i(TAG, "E2EE key sync completed for device: $requesterDeviceId (shared sync group keypair v3)")
        } catch (e: Exception) {
            Log.e(TAG, "E2EE key sync failed for device $requesterDeviceId", e)
            responseRef.setValue(mapOf(
                "status" to "error",
                "error" to (e.message ?: "Key sync failed"),
                "respondedAt" to ServerValue.TIMESTAMP
            )).await()
            requestRef.updateChildren(mapOf("status" to "error")).await()
        }
    }

    /**
     * Proactively push E2EE keys to a newly paired device.
     *
     * This is called immediately after pairing is approved when the QR code
     * contains the device's public key. The device doesn't need to send a
     * separate key request - keys are pushed directly during pairing.
     *
     * This simplifies the pairing flow:
     * 1. macOS shows QR code with its public key
     * 2. Android scans and approves pairing
     * 3. Android immediately encrypts and pushes E2EE keys (this function)
     * 4. macOS reads the encrypted keys from Firebase
     * 5. Done - no listeners, no timeouts, no race conditions
     *
     * @param targetDeviceId Device ID of the paired macOS/Web device
     * @param targetPublicKeyX963 Device's public key (X963 format, Base64 encoded)
     */
    suspend fun pushE2EEKeysToDevice(targetDeviceId: String, targetPublicKeyX963: String) {
        // CRITICAL: Must use Firebase Auth UID directly (not unified identity from RecoveryCodeManager)
        // Firebase security rules validate auth.uid == $uid, so we must use the actual auth UID
        val firebaseAuthUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
        val unifiedUserId = getCurrentUserId()  // For logging comparison only

        Log.i(TAG, "pushE2EEKeysToDevice - targetDeviceId: $targetDeviceId")
        Log.i(TAG, "pushE2EEKeysToDevice - firebaseAuthUid (using for path): $firebaseAuthUid")
        Log.i(TAG, "pushE2EEKeysToDevice - unifiedUserId (from getCurrentUserId): $unifiedUserId")
        Log.i(TAG, "pushE2EEKeysToDevice - IDs match: ${firebaseAuthUid == unifiedUserId}")

        if (firebaseAuthUid == null) {
            Log.e(TAG, "Firebase Auth UID is null - cannot push E2EE keys")
            return
        }

        val responseRef = database.reference
            .child(USERS_PATH)
            .child(firebaseAuthUid)  // Use Firebase Auth UID, not unified identity
            .child(E2EE_KEY_RESPONSES_PATH)
            .child(targetDeviceId)

        Log.i(TAG, "Pushing E2EE keys to device: $targetDeviceId at path: users/$firebaseAuthUid/e2ee_key_responses/$targetDeviceId")

        try {
            // Ensure E2EE keys are available (initialize if needed)
            e2eeManager.initializeKeys()

            // Get sync group private key (Android's ECDH private key)
            val syncGroupPrivateKeyPKCS8 = e2eeManager.getSyncGroupPrivateKeyPKCS8()
            if (syncGroupPrivateKeyPKCS8.isNullOrBlank()) {
                Log.e(TAG, "Sync group private key not available for push")
                responseRef.setValue(mapOf(
                    "status" to "error",
                    "error" to "Sync group private key not available",
                    "respondedAt" to ServerValue.TIMESTAMP
                )).await()
                return
            }

            // Get sync group public key
            val syncGroupPublicKeyX963 = e2eeManager.getSyncGroupPublicKeyX963()
            if (syncGroupPublicKeyX963.isNullOrBlank()) {
                Log.e(TAG, "Sync group public key not available for push")
                responseRef.setValue(mapOf(
                    "status" to "error",
                    "error" to "Sync group public key not available",
                    "respondedAt" to ServerValue.TIMESTAMP
                )).await()
                return
            }

            // Encode private key as bytes for encryption
            val privateKeyBytes = android.util.Base64.decode(syncGroupPrivateKeyPKCS8, android.util.Base64.NO_WRAP)

            // Encrypt the private key for the target device
            val encryptedPrivateKeyEnvelope = e2eeManager.encryptDataKeyForDevice(targetPublicKeyX963, privateKeyBytes)
            if (encryptedPrivateKeyEnvelope.isNullOrBlank()) {
                Log.e(TAG, "Failed to encrypt sync group private key for device: $targetDeviceId")
                responseRef.setValue(mapOf(
                    "status" to "error",
                    "error" to "Failed to encrypt sync group private key",
                    "respondedAt" to ServerValue.TIMESTAMP
                )).await()
                return
            }

            // Push the encrypted sync group keypair to Firebase
            responseRef.setValue(mapOf(
                "status" to "ready",
                "encryptedPrivateKeyEnvelope" to encryptedPrivateKeyEnvelope,
                "syncGroupPublicKeyX963" to syncGroupPublicKeyX963,
                "keyVersion" to 3,  // Version 3 = shared sync group keypair
                "pushedAt" to ServerValue.TIMESTAMP,
                "pushMethod" to "direct"  // Indicates this was pushed during pairing, not via request
            )).await()

            Log.i(TAG, "E2EE keys pushed successfully to device: $targetDeviceId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push E2EE keys to device $targetDeviceId", e)
            responseRef.setValue(mapOf(
                "status" to "error",
                "error" to (e.message ?: "Key push failed"),
                "respondedAt" to ServerValue.TIMESTAMP
            )).await()
        }
    }

    /**
     * DEPRECATED: Backfill is no longer needed with shared sync group keypair (v3).
     *
     * In the old architecture (v2), each device had its own keypair and messages had
     * per-device keyMaps. When a new device paired, it needed backfill to add its
     * public key to all existing messages' keyMaps.
     *
     * In the new architecture (v3), all devices share the same sync group keypair.
     * Messages are encrypted once with the sync group public key, so new devices
     * can immediately decrypt all messages without backfill.
     *
     * This function is kept for backward compatibility but does nothing.
     */
    @Deprecated("Backfill no longer needed with shared sync group keypair")
    suspend fun processE2eeKeyBackfillRequest(requesterDeviceId: String, requesterPublicKeyX963: String) {
        Log.i(TAG, "Backfill request ignored - using shared sync group keypair (v3), no backfill needed")
        val userId = getCurrentUserId()
        val requestRef = database.reference
            .child(USERS_PATH)
            .child(userId)
            .child(E2EE_KEY_BACKFILL_REQUESTS_PATH)
            .child(requesterDeviceId)

        // Mark as completed immediately (no work needed)
        requestRef.updateChildren(mapOf(
            "status" to "completed",
            "message" to "Backfill not needed with shared keypair (v3)",
            "completedAt" to ServerValue.TIMESTAMP
        )).await()
        return

        /* OLD BACKFILL CODE - DISABLED
        if (isE2eeBackfillRunning) {
        if (isE2eeBackfillRunning) {
            Log.w(TAG, "E2EE backfill already running, skipping request for $requesterDeviceId")
            return
        }

        isE2eeBackfillRunning = true

        val userId = getCurrentUserId()
        val requestRef = database.reference
            .child(USERS_PATH)
            .child(userId)
            .child(E2EE_KEY_BACKFILL_REQUESTS_PATH)
            .child(requesterDeviceId)

        try {
            requestRef.updateChildren(mapOf(
                "status" to "processing",
                "startedAt" to ServerValue.TIMESTAMP
            )).await()

            e2eeManager.initializeKeys()
            val androidDeviceId = e2eeManager.getDeviceId()
            if (androidDeviceId.isNullOrBlank()) {
                requestRef.updateChildren(mapOf(
                    "status" to "error",
                    "error" to "Android device ID not available",
                    "completedAt" to ServerValue.TIMESTAMP
                )).await()
                return
            }

            val messagesRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child(MESSAGES_PATH)

            val pageSize = 400
            var lastKey: String? = null
            var scanned = 0
            var updated = 0
            var skipped = 0
            var pageCount = 0

            while (true) {
                val query = if (lastKey == null) {
                    messagesRef.orderByKey().limitToFirst(pageSize)
                } else {
                    messagesRef.orderByKey().startAt(lastKey).limitToFirst(pageSize + 1)
                }

                val snapshot = query.get().await()
                if (!snapshot.exists()) break

                val children = snapshot.children.toList()
                val page = if (lastKey == null) children else children.drop(1)
                if (page.isEmpty()) break

                val updates = mutableMapOf<String, Any?>()

                for (child in page) {
                    val messageId = child.key ?: continue
                    val isEncrypted = child.child("encrypted").getValue(Boolean::class.java) == true
                    if (!isEncrypted) {
                        skipped++
                        scanned++
                        continue
                    }

                    val keyMapAny = child.child("keyMap").value
                    @Suppress("UNCHECKED_CAST")
                    val keyMap = keyMapAny as? Map<String, Any?>
                    if (keyMap == null || keyMap.containsKey(requesterDeviceId)) {
                        skipped++
                        scanned++
                        continue
                    }

                    val envelopeForAndroid = keyMap[androidDeviceId] as? String
                    if (envelopeForAndroid.isNullOrBlank()) {
                        skipped++
                        scanned++
                        continue
                    }

                    val dataKey = e2eeManager.decryptDataKeyFromEnvelope(envelopeForAndroid)
                    if (dataKey == null) {
                        skipped++
                        scanned++
                        continue
                    }

                    val newEnvelope = e2eeManager.encryptDataKeyForDevice(requesterPublicKeyX963, dataKey)
                    if (newEnvelope.isNullOrBlank()) {
                        skipped++
                        scanned++
                        continue
                    }

                    updates["$USERS_PATH/$userId/$MESSAGES_PATH/$messageId/keyMap/$requesterDeviceId"] = newEnvelope
                    updated++
                    scanned++
                }

                if (updates.isNotEmpty()) {
                    database.reference.updateChildren(updates).await()
                }

                lastKey = page.last().key
                pageCount++

                requestRef.updateChildren(mapOf(
                    "status" to "processing",
                    "scanned" to scanned,
                    "updated" to updated,
                    "skipped" to skipped,
                    "lastKey" to lastKey,
                    "updatedAt" to ServerValue.TIMESTAMP
                )).await()

                if (page.size < pageSize) break

                if (pageCount % 10 == 0) {
                    delay(50)
                }
            }

            requestRef.updateChildren(mapOf(
                "status" to "completed",
                "scanned" to scanned,
                "updated" to updated,
                "skipped" to skipped,
                "completedAt" to ServerValue.TIMESTAMP
            )).await()

            Log.i(TAG, "E2EE key backfill completed for device: $requesterDeviceId (updated=$updated, scanned=$scanned)")
        } catch (e: Exception) {
            Log.e(TAG, "E2EE key backfill failed for device $requesterDeviceId", e)
            requestRef.updateChildren(mapOf(
                "status" to "error",
                "error" to (e.message ?: "Key backfill failed"),
                "completedAt" to ServerValue.TIMESTAMP
            )).await()
        } finally {
            isE2eeBackfillRunning = false
        }
        */
    }

    /**
     * Get current sync settings (last synced date range)
     */
    suspend fun getSyncSettings(): SyncSettings? {
        return try {
            val userId = getCurrentUserId()
            val settingsRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child("sync_settings")

            val snapshot = settingsRef.get().await()
            if (!snapshot.exists()) return null

            val data = snapshot.value as? Map<String, Any?> ?: return null
            SyncSettings(
                lastSyncDays = (data["lastSyncDays"] as? Number)?.toInt() ?: 30,
                lastFullSyncAt = (data["lastFullSyncAt"] as? Number)?.toLong()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting sync settings", e)
            null
        }
    }
}

// ==========================================
// REGION: Data Classes
// ==========================================

/**
 * Represents a sync history request from a Mac/Web client.
 *
 * @property id Unique request identifier
 * @property days Number of days of history to sync (-1 for all history)
 * @property requestedAt Timestamp when the request was created
 * @property requestedBy Device ID that initiated the request
 */
data class SyncHistoryRequest(
    val id: String,
    val days: Int, // Number of days to sync, or -1 for all
    val requestedAt: Long,
    val requestedBy: String // Device ID that requested the sync
)

/**
 * User's sync settings stored in Firebase.
 *
 * @property lastSyncDays Number of days synced in the last sync operation
 * @property lastFullSyncAt Timestamp of the last complete history sync
 */
data class SyncSettings(
    val lastSyncDays: Int = 30,
    val lastFullSyncAt: Long? = null
)

/**
 * Represents a paired desktop or web device.
 *
 * @property id Unique device identifier (persistent across sessions)
 * @property name User-friendly device name (e.g., "MacBook Pro", "Chrome Browser")
 * @property platform Device platform: "macos", "windows", "web"
 * @property lastSeen Timestamp of last activity from this device
 * @property syncStatus Optional sync progress information
 */
data class PairedDevice(
    val id: String,
    val name: String,
    val platform: String,
    val lastSeen: Long,
    val syncStatus: SyncStatus? = null
)

/**
 * Sync progress status for a paired device.
 *
 * @property status Current state: "idle", "starting", "syncing", "completed", "failed"
 * @property syncedMessages Number of messages synced so far
 * @property totalMessages Total messages to sync
 * @property lastSyncAttempt Timestamp of the last sync attempt
 * @property lastSyncCompleted Timestamp of the last successful sync
 * @property errorMessage Error message if status is "failed"
 */
data class SyncStatus(
    val status: String, // "idle", "starting", "syncing", "completed", "failed"
    val syncedMessages: Int = 0,
    val totalMessages: Int = 0,
    val lastSyncAttempt: Long = 0,
    val lastSyncCompleted: Long? = null,
    val errorMessage: String? = null
)

/**
 * Sealed class representing the result of a pairing completion attempt.
 *
 * - [Approved]: Pairing was successful, deviceId contains the new device's ID
 * - [Rejected]: User declined the pairing request
 * - [Error]: Pairing failed due to an error (e.g., device limit reached)
 */
sealed class CompletePairingResult {
    data class Approved(val deviceId: String?) : CompletePairingResult()
    data object Rejected : CompletePairingResult()
    data class Error(val message: String) : CompletePairingResult()
}

/**
 * Result from the getDeviceInfoV2 Cloud Function.
 *
 * Contains device count, limits based on subscription plan, and the list
 * of currently paired devices.
 *
 * @property deviceCount Current number of paired devices
 * @property deviceLimit Maximum allowed devices for user's plan
 * @property plan Subscription plan: "free", "pro", etc.
 * @property canAddDevice True if user can pair another device
 * @property devices List of currently paired devices
 */
data class DeviceInfoResult(
    val deviceCount: Int,
    val deviceLimit: Int,
    val plan: String,
    val canAddDevice: Boolean,
    val devices: List<PairedDevice> = emptyList()
)

/**
 * Data parsed from a pairing QR code displayed by desktop/web clients.
 *
 * @property token Unique pairing token for this session
 * @property name Device name to display in the pairing dialog
 * @property platform Client platform: "macos", "windows", "web"
 * @property version Client app version for compatibility checking
 * @property syncGroupId Optional sync group for enterprise deployments
 */
data class PairingQrData(
    val token: String,
    val name: String,
    val platform: String,
    val version: String,
    val syncGroupId: String?
) {
    val displayName: String
        get() = "$name ($platform)"
}
