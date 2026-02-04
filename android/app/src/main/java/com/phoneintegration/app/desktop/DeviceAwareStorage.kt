package com.phoneintegration.app.desktop

import android.content.Context
import com.google.firebase.database.*
import com.phoneintegration.app.auth.UnifiedIdentityManager
import kotlinx.coroutines.tasks.await
import android.util.Log
import com.google.firebase.database.*
import kotlinx.coroutines.tasks.await

/**
 * Device-aware data storage that organizes data under main user account
 * with device-specific sub-paths for proper cleanup on unpairing.
 */
class DeviceAwareStorage(context: Context) {

    private val context: Context = context.applicationContext
    private val database = FirebaseDatabase.getInstance()
    private val unifiedIdentity = UnifiedIdentityManager.getInstance(context)

    companion object {
        private const val TAG = "DeviceAwareStorage"
    }

    /**
     * Get device-specific database reference
     */
    private suspend fun getDeviceRef(userId: String, path: String): DatabaseReference {
        val deviceId = getDeviceId()
        return database.getReference("users")
            .child(userId)
            .child("device_data")
            .child(deviceId)
            .child(path)
    }

    /**
     * Store message data under device-specific path
     */
    suspend fun storeMessage(userId: String, messageId: String, messageData: Map<String, Any>) {
        try {
            val messagesRef = getDeviceRef(userId, "messages")
            messagesRef.child(messageId).setValue(messageData).await()
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
            val messagesRef = getDeviceRef(userId, "messages")
            val snapshot = messagesRef.get().await()

            val messages = mutableListOf<Map<String, Any>>()
            for (messageSnapshot in snapshot.children) {
                val messageData = messageSnapshot.value as? Map<String, Any>
                if (messageData != null) {
                    messages.add(messageData)
                }
            }

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
            val settingsRef = getDeviceRef(userId, "settings")
            settingsRef.setValue(settings).await()
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
            val settingsRef = getDeviceRef(userId, "settings")
            val snapshot = settingsRef.get().await()

            snapshot.value as? Map<String, Any>

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
            val sharedRef = database.getReference("users")
                .child(userId)
                .child("shared_data")
                .child(path)

            sharedRef.setValue(data).await()
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
            val sharedRef = database.getReference("users")
                .child(userId)
                .child("shared_data")
                .child(path)

            val snapshot = sharedRef.get().await()
            snapshot.value

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
            val deviceDataRef = database.getReference("users")
                .child(userId)
                .child("device_data")
                .child(deviceId)

            deviceDataRef.removeValue().await()
            Log.i(TAG, "Cleaned up all data for device: $deviceId")

        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up device data", e)
        }
    }

    /**
     * Migrate data from legacy anonymous user to main account
     */
    suspend fun migrateLegacyData(legacyUserId: String, mainUserId: String) {
        try {
            Log.i(TAG, "Starting data migration from $legacyUserId to $mainUserId")

            // Migrate messages
            migrateCollection(legacyUserId, mainUserId, "messages")

            // Migrate contacts
            migrateCollection(legacyUserId, mainUserId, "contacts")

            // Migrate other data
            migrateCollection(legacyUserId, mainUserId, "calls")
            migrateCollection(legacyUserId, mainUserId, "notifications")

            // Mark migration as complete
            val migrationRef = database.getReference("users")
                .child(mainUserId)
                .child("migrations")
                .child(legacyUserId)

            migrationRef.setValue(mapOf(
                "completed" to true,
                "timestamp" to System.currentTimeMillis(),
                "deviceId" to getDeviceId()
            )).await()

            Log.i(TAG, "Data migration completed for user $legacyUserId")

        } catch (e: Exception) {
            Log.e(TAG, "Error migrating legacy data", e)
        }
    }

    /**
     * Migrate a specific data collection
     */
    private suspend fun migrateCollection(legacyUserId: String, mainUserId: String, collection: String) {
        try {
            val legacyRef = database.getReference("users").child(legacyUserId).child(collection)
            val snapshot = legacyRef.get().await()

            if (snapshot.exists()) {
                // Move data to device-specific path under main account
                val deviceRef = getDeviceRef(mainUserId, collection)
                deviceRef.setValue(snapshot.value).await()

                // Remove from legacy location
                legacyRef.removeValue().await()

                Log.d(TAG, "Migrated $collection collection (${snapshot.childrenCount} items)")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error migrating $collection", e)
        }
    }

    /**
     * Get device-specific storage usage
     */
    suspend fun getDeviceStorageUsage(userId: String): Long {
        return try {
            val deviceRef = getDeviceRef(userId, "")
            val snapshot = deviceRef.get().await()

            // Estimate size (rough calculation)
            estimateDataSize(snapshot)

        } catch (e: Exception) {
            Log.e(TAG, "Error getting storage usage", e)
            0L
        }
    }

    private fun estimateDataSize(snapshot: DataSnapshot): Long {
        // Rough estimation based on children count
        // In production, you'd calculate actual JSON size
        return snapshot.childrenCount * 1024 // Assume ~1KB per record
    }

    private fun getDeviceId(): String {
        return android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
            ?: "unknown_device"
    }
}
