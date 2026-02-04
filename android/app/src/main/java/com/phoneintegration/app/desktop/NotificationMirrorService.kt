package com.phoneintegration.app.desktop

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.phoneintegration.app.auth.UnifiedIdentityManager
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream

/**
 * Service that mirrors Android notifications to Firebase for display on macOS.
 * Requires user to enable Notification Access in Settings.
 */
class NotificationMirrorService : NotificationListenerService() {

    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Track sent notifications to avoid duplicates
    private val sentNotificationKeys = mutableSetOf<String>()
    private val notificationKeyToFirebaseId = mutableMapOf<String, String>()

    // Apps to exclude from mirroring (system apps, messaging apps already handled)
    private val excludedPackages = setOf(
        "com.phoneintegration.app", // Our app
        "com.android.systemui",
        "com.android.vending", // Play Store
        "com.google.android.gms", // Google Play Services
        "com.google.android.apps.messaging", // Google Messages
        "com.samsung.android.messaging", // Samsung Messages
        "android", // System
    )

    companion object {
        private const val TAG = "NotificationMirror"
        private const val NOTIFICATIONS_PATH = "mirrored_notifications"
        private const val USERS_PATH = "users"
        private const val MAX_NOTIFICATIONS = 20 // Max notifications to keep
        private const val ICON_SIZE = 48 // Icon size in pixels
        private const val MEDIA_INFO_TTL_MS = 30_000L

        data class MediaInfo(
            val title: String?,
            val artist: String?,
            val album: String?,
            val packageName: String?,
            val appName: String?,
            val timestamp: Long
        )

        @Volatile
        private var lastMediaInfo: MediaInfo? = null

        fun getLastMediaInfo(): MediaInfo? {
            val info = lastMediaInfo ?: return null
            return if (System.currentTimeMillis() - info.timestamp <= MEDIA_INFO_TTL_MS) {
                info
            } else {
                null
            }
        }

        /**
         * Check if notification listener permission is granted
         */
        fun isEnabled(context: Context): Boolean {
            val componentName = ComponentName(context, NotificationMirrorService::class.java)
            val enabledListeners = android.provider.Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            )
            return enabledListeners?.contains(componentName.flattenToString()) == true
        }

        /**
         * Check if notification mirror feature is enabled by user
         */
        fun isFeatureEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences("syncflow_prefs", Context.MODE_PRIVATE)
            return prefs.getBoolean("notification_mirror_enabled", false)
        }

        /**
         * Open notification access settings
         */
        fun openSettings(context: Context) {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
            )
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "NotificationMirrorService created")
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        Log.d(TAG, "NotificationMirrorService destroyed")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "NotificationMirrorService CONNECTED - ready to receive notifications")

        // Try to get unified user ID
        val unifiedUserId = UnifiedIdentityManager.getInstance(applicationContext).getUnifiedUserIdSync()

        if (unifiedUserId != null) {
            Log.d(TAG, "Using unified user ID for notifications: $unifiedUserId")
        } else {
            Log.w(TAG, "No unified user ID available - notifications will not sync!")
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "NotificationMirrorService DISCONNECTED")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return

        // Check if feature is enabled by user
        if (!isFeatureEnabled(applicationContext)) {
            return
        }

        // Skip excluded packages
        if (excludedPackages.contains(sbn.packageName)) return

        val notification = sbn.notification

        if (isMediaNotification(notification)) {
            updateMediaInfoFromNotification(sbn)
        }

        // Skip ongoing notifications (music players, etc.)
        if (sbn.isOngoing) return

        // Skip summary notifications (group headers)
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        // Get notification details
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()

        // Skip if no content
        if (title.isNullOrBlank() && text.isNullOrBlank()) return

        // Create unique key to avoid duplicates
        val notificationKey = "${sbn.packageName}:${sbn.id}:${title}:${text?.take(50)}"
        if (sentNotificationKeys.contains(notificationKey)) return
        sentNotificationKeys.add(notificationKey)

        // Limit cache size
        if (sentNotificationKeys.size > 100) {
            sentNotificationKeys.clear()
        }

        // Get app info
        val appName = getAppName(sbn.packageName)
        val appIcon = getAppIconBase64(sbn.packageName)

        Log.d(TAG, "Mirroring notification from $appName: $title")

        // Get unified user ID instead of Firebase auth user
        val userId = UnifiedIdentityManager.getInstance(applicationContext).getUnifiedUserIdSync()

        if (userId == null) {
            Log.w(TAG, "Cannot mirror notification - no unified user ID available")
            return
        }

        Log.d(TAG, "Syncing notification to Firebase for unified user: $userId")
        val firebaseId = database.reference
            .child(USERS_PATH)
            .child(userId)
            .child(NOTIFICATIONS_PATH)
            .push()
            .key ?: return

        notificationKeyToFirebaseId[sbn.key] = firebaseId

        // Send to Firebase
        scope.launch {
            mirrorNotification(
                appPackage = sbn.packageName,
                appName = appName,
                appIcon = appIcon,
                title = title ?: "",
                text = bigText ?: text ?: "",
                timestamp = sbn.postTime,
                notificationId = sbn.id.toString(),
                userId = userId,
                firebaseId = firebaseId
            )
        }
    }

    private fun isMediaNotification(notification: Notification): Boolean {
        val extras = notification.extras
        if (notification.category == Notification.CATEGORY_TRANSPORT) return true
        if (extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return true
        return notification.actions?.isNotEmpty() == true &&
            (extras.containsKey(Notification.EXTRA_TITLE) || extras.containsKey(Notification.EXTRA_TEXT))
    }

    private fun updateMediaInfoFromNotification(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = firstNonBlank(
            extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()
        )
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val infoText = extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString()

        val artist = if (text != null && text != title) text else null
        val album = firstNonBlank(subText, infoText)

        val appName = try {
            val appInfo = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(appInfo)?.toString()
        } catch (e: Exception) {
            null
        }

        lastMediaInfo = MediaInfo(
            title = title,
            artist = artist,
            album = album,
            packageName = sbn.packageName,
            appName = appName,
            timestamp = System.currentTimeMillis()
        )
    }

    private fun firstNonBlank(first: String?, second: String?): String? {
        return when {
            !first.isNullOrBlank() -> first
            !second.isNullOrBlank() -> second
            else -> null
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return

        val firebaseId = notificationKeyToFirebaseId.remove(sbn.key)
        if (firebaseId == null) {
            Log.d(TAG, "Notification removed (no Firebase id): ${sbn.packageName}")
            return
        }

        // Get unified user ID instead of Firebase auth user
        val userId = UnifiedIdentityManager.getInstance(applicationContext).getUnifiedUserIdSync()

        if (userId == null) {
            Log.w(TAG, "Cannot remove notification - no unified user ID available")
            return
        }

        scope.launch {
            try {
                database.reference
                    .child(USERS_PATH)
                    .child(userId)
                    .child(NOTIFICATIONS_PATH)
                    .child(firebaseId)
                    .removeValue()
                    .await()
                Log.d(TAG, "Notification removed from Firebase: ${sbn.packageName}")
            } catch (e: Exception) {
                Log.e(TAG, "Error removing mirrored notification", e)
            }
        }
    }

    /**
     * Get app name from package name
     */
    private fun getAppName(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName.substringAfterLast('.')
        }
    }

    /**
     * Get app icon as Base64 string
     */
    private fun getAppIconBase64(packageName: String): String? {
        return try {
            val drawable = packageManager.getApplicationIcon(packageName)
            val bitmap = drawableToBitmap(drawable, ICON_SIZE)
            bitmapToBase64(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting app icon", e)
            null
        }
    }

    /**
     * Convert drawable to bitmap
     */
    private fun drawableToBitmap(drawable: Drawable, size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bitmap
    }

    /**
     * Convert bitmap to Base64 string
     */
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        val bytes = outputStream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /**
     * Send notification to Firebase
     */
    private suspend fun mirrorNotification(
        appPackage: String,
        appName: String,
        appIcon: String?,
        title: String,
        text: String,
        timestamp: Long,
        notificationId: String,
        userId: String,
        firebaseId: String
    ) {
        try {
            val notificationRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child(NOTIFICATIONS_PATH)
                .child(firebaseId)

            val notificationData = mutableMapOf<String, Any>(
                "appPackage" to appPackage,
                "appName" to appName,
                "title" to title,
                "text" to text,
                "timestamp" to timestamp,
                "notificationId" to notificationId,
                "syncedAt" to ServerValue.TIMESTAMP
            )

            // Add icon if available (keep it small)
            if (appIcon != null && appIcon.length < 10000) {
                notificationData["appIcon"] = appIcon
            }

            notificationRef.setValue(notificationData).await()

            Log.d(TAG, "Notification mirrored: $appName - $title")

            // Clean up old notifications
            cleanupOldNotifications(userId)

        } catch (e: Exception) {
            Log.e(TAG, "Error mirroring notification", e)
        }
    }

    /**
     * Remove old notifications to keep database clean
     */
    private suspend fun cleanupOldNotifications(userId: String) {
        try {
            val notificationsRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child(NOTIFICATIONS_PATH)

            val snapshot = notificationsRef.orderByChild("syncedAt").get().await()
            val count = snapshot.childrenCount

            if (count > MAX_NOTIFICATIONS) {
                val toDelete = (count - MAX_NOTIFICATIONS).toInt()
                var deleted = 0

                for (child in snapshot.children) {
                    if (deleted >= toDelete) break
                    child.ref.removeValue().await()
                    deleted++
                }

                Log.d(TAG, "Cleaned up $deleted old notifications")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up notifications", e)
        }
    }
}
