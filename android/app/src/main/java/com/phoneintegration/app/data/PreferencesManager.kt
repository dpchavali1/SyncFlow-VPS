package com.phoneintegration.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import org.json.JSONObject

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("syncflow_prefs", Context.MODE_PRIVATE)
    private val defaultE2eeEnabled: Boolean =
        if (prefs.contains("e2ee_enabled")) {
            prefs.getBoolean("e2ee_enabled", false)
        } else {
            true
        }

    companion object {
        private const val PREFS_NAME = "syncflow_prefs"
        private const val PREFERRED_SEND_PREFIX = "preferred_send_address_"

        fun isDesktopCallSyncEnabled(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean("desktop_call_sync", false)
        }
    }

    // Theme Settings
    var isDarkMode = mutableStateOf(prefs.getBoolean("dark_mode", false))
        private set
    
    var isAutoTheme = mutableStateOf(prefs.getBoolean("auto_theme", true))
        private set

    // Notification Settings
    var notificationsEnabled = mutableStateOf(prefs.getBoolean("notifications_enabled", true))
        private set
    
    var notificationSound = mutableStateOf(prefs.getBoolean("notification_sound", true))
        private set
    
    var notificationVibrate = mutableStateOf(prefs.getBoolean("notification_vibrate", true))
        private set
    
    var notificationPreview = mutableStateOf(prefs.getBoolean("notification_preview", true))
        private set

    // Message Settings
    var sendOnEnter = mutableStateOf(prefs.getBoolean("send_on_enter", false))
        private set
    
    var showTimestamps = mutableStateOf(prefs.getBoolean("show_timestamps", true))
        private set
    
    var groupMessagesByDate = mutableStateOf(prefs.getBoolean("group_by_date", true))
        private set


    
    // Default to OFF to avoid accidental deletes/swipes on first launch
    var swipeGesturesEnabled = mutableStateOf(prefs.getBoolean("swipe_gestures_enabled", false))
        private set

    var autoDeleteOld = mutableStateOf(prefs.getBoolean("auto_delete_old", false))
        private set
    
    var deleteAfterDays = mutableStateOf(prefs.getInt("delete_after_days", 90))
        private set

    // Privacy Settings
    var requireFingerprint = mutableStateOf(prefs.getBoolean("require_fingerprint", false))
        private set

    var hideMessagePreview = mutableStateOf(prefs.getBoolean("hide_message_preview", false))
        private set

    var incognitoMode = mutableStateOf(prefs.getBoolean("incognito_mode", false))
        private set

    // E2EE Settings
    var e2eeEnabled = mutableStateOf(defaultE2eeEnabled)
        private set

    // Spam Filter Settings
    var spamFilterEnabled = mutableStateOf(prefs.getBoolean("spam_filter_enabled", true))
        private set

    // Spam sensitivity: 0 = Low (0.7 threshold), 1 = Medium (0.5), 2 = High (0.3)
    var spamFilterSensitivity = mutableStateOf(prefs.getInt("spam_filter_sensitivity", 1))
        private set

    // Desktop Sync Settings
    var desktopCallSyncEnabled = mutableStateOf(prefs.getBoolean("desktop_call_sync", false))
        private set

    var backgroundSyncEnabled = mutableStateOf(prefs.getBoolean("background_sync_enabled", false))
        private set

    var notificationMirrorEnabled = mutableStateOf(prefs.getBoolean("notification_mirror_enabled", false))
        private set

    // Recovery Backup Settings (enabled by default for seamless account recovery)
    var recoveryBackupEnabled = mutableStateOf(prefs.getBoolean("recovery_backup_enabled", true))
        private set

    // Subscription/Plan Settings
    var userPlan = mutableStateOf(prefs.getString("user_plan", null) ?: "free")
        private set

    var planExpiresAt = mutableStateOf(prefs.getLong("plan_expires_at", 0L))
        private set

    // Free tier trial expiry (7 days from first use)
    var freeTrialExpiresAt = mutableStateOf(prefs.getLong("free_trial_expires_at", 0L))
        private set

    init {
        if (!prefs.contains("e2ee_enabled")) {
            prefs.edit().putBoolean("e2ee_enabled", true).apply()
            e2eeEnabled.value = true
        }
    }

    // Helper to check if user is on paid plan
    fun isPaidUser(): Boolean {
        val plan = userPlan.value.lowercase()
        val isPaid = plan in listOf("monthly", "yearly", "lifetime", "3year")
        val now = System.currentTimeMillis()

        // Check expiration
        if (isPaid && planExpiresAt.value > 0 && planExpiresAt.value < now) {
            return false // Plan expired, treat as free
        }
        return isPaid
    }

    // Check if free trial is still active (7 days)
    fun isFreeTrial(): Boolean {
        if (isPaidUser()) return false

        val now = System.currentTimeMillis()

        // Initialize trial on first use
        if (freeTrialExpiresAt.value == 0L) {
            val trialExpiry = now + (7 * 24 * 60 * 60 * 1000) // 7 days from now
            setFreeTrialExpiry(trialExpiry)
            return true
        }

        return freeTrialExpiresAt.value > now
    }

    // Get remaining trial days
    fun getTrialDaysRemaining(): Int {
        if (isPaidUser()) return 0
        val now = System.currentTimeMillis()
        val remaining = (freeTrialExpiresAt.value - now) / (24 * 60 * 60 * 1000)
        return maxOf(0, remaining.toInt())
    }

    // Helper for SMS-only restriction
    fun isSmsOnlyUser(): Boolean = !isPaidUser()

    init {
        // Debug logging for settings initialization
        android.util.Log.d("PreferencesManager", "Initialized with background_sync_enabled: ${backgroundSyncEnabled.value}, notification_mirror_enabled: ${notificationMirrorEnabled.value}")

        // If settings appear to be reset (both false when they shouldn't be), try to restore from backup
        val allSettingsFalse = !backgroundSyncEnabled.value && !notificationMirrorEnabled.value && !desktopCallSyncEnabled.value
        if (allSettingsFalse) {
            // Try to restore from backup
            val restored = restoreFromAutoBackup()
            if (restored) {
                android.util.Log.w("PreferencesManager", "Detected settings reset, restored from backup")
            }
        }
    }

    // Appearance Settings
    var bubbleStyle = mutableStateOf(prefs.getString("bubble_style", "rounded") ?: "rounded")
        private set
    
    var fontSize = mutableStateOf(prefs.getInt("font_size", 14))
        private set
    
    var chatWallpaper = mutableStateOf(prefs.getString("chat_wallpaper", "default") ?: "default")
        private set

    // Signature
    var messageSignature = mutableStateOf(prefs.getString("message_signature", "") ?: "")
        private set
    
    var addSignature = mutableStateOf(prefs.getBoolean("add_signature", false))
        private set

    // Message Reactions (local-only)
    private val reactionsKey = "message_reactions"

    // Quick Reply Templates
    fun getQuickReplyTemplates(): List<String> {
        val templatesJson = prefs.getString("quick_reply_templates", "[]") ?: "[]"
        return try {
            templatesJson.split("|").filter { it.isNotEmpty() }
        } catch (e: Exception) {
            listOf()
        }
    }

    fun saveQuickReplyTemplates(templates: List<String>) {
        prefs.edit().putString("quick_reply_templates", templates.joinToString("|")).apply()
    }

    // Update methods
    fun setDarkMode(enabled: Boolean) {
        isDarkMode.value = enabled
        prefs.edit().putBoolean("dark_mode", enabled).apply()
    }

    fun setAutoTheme(enabled: Boolean) {
        isAutoTheme.value = enabled
        prefs.edit().putBoolean("auto_theme", enabled).apply()
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        notificationsEnabled.value = enabled
        prefs.edit().putBoolean("notifications_enabled", enabled).apply()
    }

    fun setNotificationSound(enabled: Boolean) {
        notificationSound.value = enabled
        prefs.edit().putBoolean("notification_sound", enabled).apply()
    }

    fun setNotificationVibrate(enabled: Boolean) {
        notificationVibrate.value = enabled
        prefs.edit().putBoolean("notification_vibrate", enabled).apply()
    }

    fun setNotificationPreview(enabled: Boolean) {
        notificationPreview.value = enabled
        prefs.edit().putBoolean("notification_preview", enabled).apply()
    }

    fun setSendOnEnter(enabled: Boolean) {
        sendOnEnter.value = enabled
        prefs.edit().putBoolean("send_on_enter", enabled).apply()
    }

    fun setShowTimestamps(enabled: Boolean) {
        showTimestamps.value = enabled
        prefs.edit().putBoolean("show_timestamps", enabled).apply()
    }

    fun setGroupByDate(enabled: Boolean) {
        groupMessagesByDate.value = enabled
        prefs.edit().putBoolean("group_by_date", enabled).apply()
    }

    fun setSwipeGesturesEnabled(enabled: Boolean) {
        swipeGesturesEnabled.value = enabled
        prefs.edit().putBoolean("swipe_gestures_enabled", enabled).apply()
    }

    fun setAutoDeleteOld(enabled: Boolean) {
        autoDeleteOld.value = enabled
        prefs.edit().putBoolean("auto_delete_old", enabled).apply()
    }

    fun setDeleteAfterDays(days: Int) {
        deleteAfterDays.value = days
        prefs.edit().putInt("delete_after_days", days).apply()
    }

    fun setRequireFingerprint(enabled: Boolean) {
        requireFingerprint.value = enabled
        prefs.edit().putBoolean("require_fingerprint", enabled).apply()
    }

    fun setHideMessagePreview(enabled: Boolean) {
        hideMessagePreview.value = enabled
        prefs.edit().putBoolean("hide_message_preview", enabled).apply()
    }

    fun setIncognitoMode(enabled: Boolean) {
        incognitoMode.value = enabled
        prefs.edit().putBoolean("incognito_mode", enabled).apply()
    }

    fun setE2eeEnabled(enabled: Boolean) {
        e2eeEnabled.value = enabled
        prefs.edit().putBoolean("e2ee_enabled", enabled).apply()
    }

    fun setSpamFilterEnabled(enabled: Boolean) {
        spamFilterEnabled.value = enabled
        prefs.edit().putBoolean("spam_filter_enabled", enabled).apply()
    }

    fun setSpamFilterSensitivity(sensitivity: Int) {
        spamFilterSensitivity.value = sensitivity.coerceIn(0, 2)
        prefs.edit().putInt("spam_filter_sensitivity", sensitivity.coerceIn(0, 2)).apply()
    }

    // Helper to get spam threshold based on sensitivity
    fun getSpamThreshold(): Float {
        return when (spamFilterSensitivity.value) {
            0 -> 0.7f  // Low - only obvious spam
            1 -> 0.5f  // Medium (default)
            2 -> 0.3f  // High - catch more spam
            else -> 0.5f
        }
    }

    fun setDesktopCallSyncEnabled(enabled: Boolean) {
        desktopCallSyncEnabled.value = enabled
        prefs.edit().putBoolean("desktop_call_sync", enabled).apply()
        autoBackupSyncSettings()
    }

    fun setBackgroundSyncEnabled(enabled: Boolean) {
        backgroundSyncEnabled.value = enabled
        prefs.edit().putBoolean("background_sync_enabled", enabled).apply()
        android.util.Log.d("PreferencesManager", "Background sync enabled set to: $enabled")
        autoBackupSyncSettings()
    }

    fun setNotificationMirrorEnabled(enabled: Boolean) {
        notificationMirrorEnabled.value = enabled
        prefs.edit().putBoolean("notification_mirror_enabled", enabled).apply()
        android.util.Log.d("PreferencesManager", "Notification mirror enabled set to: $enabled")
        autoBackupSyncSettings()
    }

    fun setRecoveryBackupEnabled(context: Context, enabled: Boolean) {
        val wasEnabled = recoveryBackupEnabled.value
        recoveryBackupEnabled.value = enabled
        prefs.edit().putBoolean("recovery_backup_enabled", enabled).apply()
        android.util.Log.d("PreferencesManager", "Recovery backup enabled set to: $enabled")

        // Migrate recovery data between storage locations if setting changed
        if (wasEnabled != enabled) {
            try {
                val recoveryManager = com.phoneintegration.app.auth.RecoveryCodeManager.getInstance(context)
                recoveryManager.migrateStorageForBackupSetting(enabled)
                android.util.Log.d("PreferencesManager", "Migrated recovery data for backup=$enabled")
            } catch (e: Exception) {
                android.util.Log.e("PreferencesManager", "Failed to migrate recovery data", e)
            }
        }
    }

    // Overload for backward compatibility
    fun setRecoveryBackupEnabled(enabled: Boolean) {
        recoveryBackupEnabled.value = enabled
        prefs.edit().putBoolean("recovery_backup_enabled", enabled).apply()
        android.util.Log.d("PreferencesManager", "Recovery backup enabled set to: $enabled (no migration)")
    }

    fun setUserPlan(plan: String, expiresAt: Long = 0) {
        userPlan.value = plan
        planExpiresAt.value = expiresAt
        prefs.edit()
            .putString("user_plan", plan)
            .putLong("plan_expires_at", expiresAt)
            .apply()
        android.util.Log.d("PreferencesManager", "User plan set to: $plan, expires: $expiresAt")
    }

    fun setFreeTrialExpiry(expiryTime: Long) {
        freeTrialExpiresAt.value = expiryTime
        prefs.edit().putLong("free_trial_expires_at", expiryTime).apply()
        android.util.Log.d("PreferencesManager", "Free trial expires at: $expiryTime")
    }

    fun setBubbleStyle(style: String) {
        bubbleStyle.value = style
        prefs.edit().putString("bubble_style", style).apply()
    }

    fun setFontSize(size: Int) {
        fontSize.value = size
        prefs.edit().putInt("font_size", size).apply()
    }

    fun setChatWallpaper(wallpaper: String) {
        chatWallpaper.value = wallpaper
        prefs.edit().putString("chat_wallpaper", wallpaper).apply()
    }

    fun setMessageSignature(signature: String) {
        messageSignature.value = signature
        prefs.edit().putString("message_signature", signature).apply()
    }

    fun setAddSignature(enabled: Boolean) {
        addSignature.value = enabled
        prefs.edit().putBoolean("add_signature", enabled).apply()
    }

    fun getPreferredSendAddress(conversationKey: String): String? {
        if (conversationKey.isBlank()) return null
        return prefs.getString("$PREFERRED_SEND_PREFIX$conversationKey", null)
    }

    fun setPreferredSendAddress(conversationKey: String, address: String?) {
        if (conversationKey.isBlank()) return
        val editor = prefs.edit()
        if (address.isNullOrBlank()) {
            editor.remove("$PREFERRED_SEND_PREFIX$conversationKey")
        } else {
            editor.putString("$PREFERRED_SEND_PREFIX$conversationKey", address)
        }
        editor.apply()
    }

    fun getMessageReaction(messageId: Long): String? {
        val reactions = loadReactions()
        return reactions[messageId.toString()]
    }

    fun setMessageReaction(messageId: Long, reaction: String) {
        val reactions = loadReactions().toMutableMap()
        reactions[messageId.toString()] = reaction
        saveReactions(reactions)
    }

    fun clearMessageReaction(messageId: Long) {
        val reactions = loadReactions().toMutableMap()
        reactions.remove(messageId.toString())
        saveReactions(reactions)
    }

    private fun loadReactions(): Map<String, String> {
        val raw = prefs.getString(reactionsKey, "") ?: ""
        if (raw.isBlank()) return emptyMap()
        return raw.split("|")
            .mapNotNull { entry ->
                val parts = entry.split("=", limit = 2)
                if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                    parts[0] to parts[1]
                } else null
            }
            .toMap()
    }

    private fun saveReactions(reactions: Map<String, String>) {
        val raw = reactions.entries.joinToString("|") { "${it.key}=${it.value}" }
        prefs.edit().putString(reactionsKey, raw).apply()
    }

    // Backup and restore functionality for critical settings
    fun exportSyncSettings(): Map<String, Any> {
        return mapOf(
            "background_sync_enabled" to backgroundSyncEnabled.value,
            "notification_mirror_enabled" to notificationMirrorEnabled.value,
            "desktop_call_sync" to desktopCallSyncEnabled.value,
            "exported_at" to System.currentTimeMillis()
        )
    }

    fun importSyncSettings(settings: Map<String, Any>): Boolean {
        return try {
            val backgroundSync = settings["background_sync_enabled"] as? Boolean ?: false
            val notificationMirror = settings["notification_mirror_enabled"] as? Boolean ?: false
            val desktopCallSync = settings["desktop_call_sync"] as? Boolean ?: false

            // Apply settings
            setBackgroundSyncEnabled(backgroundSync)
            setNotificationMirrorEnabled(notificationMirror)
            setDesktopCallSyncEnabled(desktopCallSync)

            android.util.Log.d("PreferencesManager", "Imported sync settings - Background: $backgroundSync, Notification: $notificationMirror, Call: $desktopCallSync")
            true
        } catch (e: Exception) {
            android.util.Log.e("PreferencesManager", "Failed to import sync settings", e)
            false
        }
    }

    // Auto-backup sync settings to prevent loss
    fun autoBackupSyncSettings() {
        try {
            val settings = exportSyncSettings()
            val jsonObject = JSONObject()
            settings.forEach { (key, value) ->
                jsonObject.put(key, value)
            }
            val settingsJson = jsonObject.toString()
            prefs.edit().putString("sync_settings_backup", settingsJson).apply()
            android.util.Log.d("PreferencesManager", "Auto-backed up sync settings: $settingsJson")
        } catch (e: Exception) {
            android.util.Log.e("PreferencesManager", "Failed to auto-backup sync settings", e)
        }
    }

    fun restoreFromAutoBackup(): Boolean {
        return try {
            val backupJson = prefs.getString("sync_settings_backup", null)
            if (backupJson != null) {
                val jsonObject = JSONObject(backupJson)
                val settings = mutableMapOf<String, Any>()
                jsonObject.keys().forEach { key ->
                    settings[key] = jsonObject.get(key)
                }
                val result = importSyncSettings(settings)
                if (result) {
                    android.util.Log.d("PreferencesManager", "Restored sync settings from auto-backup")
                }
                result
            } else {
                android.util.Log.d("PreferencesManager", "No auto-backup found")
                false
            }
        } catch (e: Exception) {
            android.util.Log.e("PreferencesManager", "Failed to restore from auto-backup", e)
            false
        }
    }
}
