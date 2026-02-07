package com.phoneintegration.app.desktop

import android.content.Context
import android.util.Log
import com.phoneintegration.app.auth.UnifiedIdentityManager
import com.phoneintegration.app.vps.VPSClient

/**
 * Device-aware data storage that organizes data under main user account
 * with device-specific sub-paths for proper cleanup on unpairing.
 *
 * VPS Backend Only - Uses VPS API instead of Firebase.
 */
class DeviceAwareStorage(context: Context) {

    private val context: Context = context.applicationContext
    private val vpsClient = VPSClient.getInstance(context)
    private val unifiedIdentity = UnifiedIdentityManager.getInstance(context)

    companion object {
        private const val TAG = "DeviceAwareStorage"
    }

    /**
     * Store message data under device-specific path
     */
    suspend fun storeMessage(userId: String, messageId: String, messageData: Map<String, Any>) {
        try {
            vpsClient.storeDeviceMessage(getDeviceId(), messageId, messageData)
            Log.d(TAG, "Stored message $messageId for device ${getDeviceId()}")
        } catch (e: Exception) {
            Log.e(TAG, "Error storing message", e)
        }
    }

    /**
     * Get messages for current device
     */
    suspend fun getDeviceMessages(userId: String): List<Map<String, Any>> {
        return try {
            val messages = vpsClient.getDeviceMessages(getDeviceId())
            Log.d(TAG, "Retrieved ${messages.size} messages for device ${getDeviceId()}")
            messages
        } catch (e: Exception) {
            Log.e(TAG, "Error getting device messages", e)
            emptyList()
        }
    }

    /**
     * Store device-specific settings
     */
    suspend fun storeDeviceSettings(userId: String, settings: Map<String, Any>) {
        try {
            vpsClient.storeDeviceSettings(getDeviceId(), settings)
            Log.d(TAG, "Stored settings for device ${getDeviceId()}")
        } catch (e: Exception) {
            Log.e(TAG, "Error storing device settings", e)
        }
    }

    /**
     * Get device-specific settings
     */
    suspend fun getDeviceSettings(userId: String): Map<String, Any>? {
        return try {
            vpsClient.getDeviceSettings(getDeviceId())
        } catch (e: Exception) {
            Log.e(TAG, "Error getting device settings", e)
            null
        }
    }

    /**
     * Store shared data (accessible by all devices)
     */
    suspend fun storeSharedData(userId: String, path: String, data: Any) {
        try {
            vpsClient.storeSharedData(path, data)
            Log.d(TAG, "Stored shared data at $path")
        } catch (e: Exception) {
            Log.e(TAG, "Error storing shared data", e)
        }
    }

    /**
     * Get shared data
     */
    suspend fun getSharedData(userId: String, path: String): Any? {
        return try {
            vpsClient.getSharedData(path)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting shared data", e)
            null
        }
    }

    /**
     * Clean up all data for current device (called on unpairing)
     */
    suspend fun cleanupDeviceData(userId: String) {
        try {
            val deviceId = getDeviceId()
            vpsClient.cleanupDeviceData(deviceId)
            Log.i(TAG, "Cleaned up all data for device: $deviceId")
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up device data", e)
        }
    }

    /**
     * Migrate data from legacy anonymous user to main account
     * Note: In VPS mode, migration is handled server-side
     */
    suspend fun migrateLegacyData(legacyUserId: String, mainUserId: String) {
        try {
            Log.i(TAG, "Starting data migration from $legacyUserId to $mainUserId")
            vpsClient.migrateUserData(legacyUserId, mainUserId, getDeviceId())
            Log.i(TAG, "Data migration completed for user $legacyUserId")
        } catch (e: Exception) {
            Log.e(TAG, "Error migrating legacy data", e)
        }
    }

    /**
     * Get device-specific storage usage
     */
    suspend fun getDeviceStorageUsage(userId: String): Long {
        return try {
            vpsClient.getDeviceStorageUsage(getDeviceId())
        } catch (e: Exception) {
            Log.e(TAG, "Error getting storage usage", e)
            0L
        }
    }

    private fun getDeviceId(): String {
        return android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
            ?: "unknown_device"
    }
}
