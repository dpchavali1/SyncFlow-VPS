package com.phoneintegration.app.services

import android.content.Context
import android.util.Log
import com.google.firebase.database.*
import com.phoneintegration.app.auth.AuthManager
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

/**
 * Data cleanup service that removes orphaned data from unpaired devices
 * and manages Firebase storage costs.
 *
 * IMPORTANT: This service performs SELECTIVE cleanup that PRESERVES user content
 * while removing temporary/cache data to prevent storage accumulation.
 *
 * 🗑️ WHAT GETS CLEANED UP:
 * - Device-specific temporary messages
 * - Device-specific temporary notifications
 * - Device preferences (not user content)
 * - Temporary cache data
 * - Expired pairing tokens
 * - Legacy anonymous user data
 *
 * 🛡️ WHAT GETS PRESERVED:
 * - User photos and documents (in shared_data/user_content)
 * - Conversation history (in shared_data/conversations)
 * - User preferences (in user_settings)
 * - Contacts (in shared_data/contacts)
 * - Important files and media (in shared_data/files)
 * - Shared content accessible by all devices
 */
class DataCleanupService private constructor(private val context: Context) {

    companion object {
        private const val TAG = "DataCleanupService"
        private const val CLEANUP_INTERVAL_DAYS = 7L // Run cleanup weekly
        private const val ORPHANED_DATA_RETENTION_DAYS = 30L // Keep orphaned data for 30 days

        @Volatile
        private var instance: DataCleanupService? = null

        fun getInstance(context: Context): DataCleanupService {
            return instance ?: synchronized(this) {
                instance ?: DataCleanupService(context.applicationContext).also { instance = it }
            }
        }
    }

    private val database = FirebaseDatabase.getInstance()
    private val unifiedAuthManager = AuthManager.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        schedulePeriodicCleanup()
    }

    /**
     * Schedule periodic cleanup of orphaned data
     */
    private fun schedulePeriodicCleanup() {
        scope.launch {
            while (isActive) {
                delay(TimeUnit.DAYS.toMillis(CLEANUP_INTERVAL_DAYS))
                performCleanup()
            }
        }
    }

    /**
     * Perform comprehensive data cleanup
     */
    suspend fun performCleanup() {
        try {
            Log.i(TAG, "Starting data cleanup process")

            // Get all users
            val usersRef = database.getReference("users")
            val usersSnapshot = usersRef.get().await()

            for (userSnapshot in usersSnapshot.children) {
                val userId = userSnapshot.key ?: continue
                cleanupUserData(userId)
            }

            // Clean up expired pairing tokens
            cleanupExpiredTokens()

            // Clean up old anonymous user data
            cleanupLegacyAnonymousUsers()

            Log.i(TAG, "Data cleanup process completed")

        } catch (e: Exception) {
            Log.e(TAG, "Error during data cleanup", e)
        }
    }

    /**
     * Clean up data for a specific user
     */
    private suspend fun cleanupUserData(userId: String) {
        try {
            val userRef = database.getReference("users").child(userId)

            // Get paired devices
            val devicesSnapshot = userRef.child("devices").get().await()
            val pairedDeviceIds = devicesSnapshot.children.mapNotNull { it.key }.toSet()

            // Check device_data for orphaned entries
            val deviceDataSnapshot = userRef.child("device_data").get().await()
            for (deviceDataEntry in deviceDataSnapshot.children) {
                val deviceId = deviceDataSnapshot.key ?: continue

                if (deviceId !in pairedDeviceIds) {
                    // This device is no longer paired - check if it should be removed
                    if (shouldRemoveOrphanedDevice(userId, deviceId)) {
                        removeOrphanedDeviceData(userId, deviceId)
                    }
                }
            }

            // Clean up old migration records
            cleanupOldMigrations(userId)

        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up user data for $userId", e)
        }
    }

    /**
     * Check if orphaned device data should be removed
     */
    private suspend fun shouldRemoveOrphanedDevice(userId: String, deviceId: String): Boolean {
        try {
            val deviceRef = database.getReference("users")
                .child(userId)
                .child("devices")
                .child(deviceId)

            // Check if device was ever paired (has pairing timestamp)
            val deviceSnapshot = deviceRef.get().await()
            val pairedAt = deviceSnapshot.child("pairedAt").getValue(Long::class.java)

            if (pairedAt == null) {
                // Device was never properly paired, safe to remove immediately
                return true
            }

            // Check how long ago device was unpaired
            val unpairedAt = deviceSnapshot.child("unpairedAt")?.getValue(Long::class.java)
                ?: pairedAt // If no unpaired timestamp, use paired time

            val daysSinceUnpairing = (System.currentTimeMillis() - unpairedAt) / (1000 * 60 * 60 * 24)

            return daysSinceUnpairing >= ORPHANED_DATA_RETENTION_DAYS

        } catch (e: Exception) {
            Log.e(TAG, "Error checking orphaned device status", e)
            // If we can't determine, be conservative and keep the data
            return false
        }
    }

    /**
     * Remove orphaned device data (SELECTIVE CLEANUP)
     * Only removes temporary data, preserves user content
     */
    private suspend fun removeOrphanedDeviceData(userId: String, deviceId: String) {
        try {
            Log.i(TAG, "Removing orphaned device temporary data for device: $deviceId (user: $userId)")

            // Only clean temporary device data, preserve user content
            cleanupDeviceTemporaryData(userId, deviceId)

            // Clean up device references
            cleanupDeviceReferences(userId, deviceId)

            // Note: User content in shared_data is preserved even for orphaned devices
            // This gives users time to recover important files before permanent deletion

        } catch (e: Exception) {
            Log.e(TAG, "Error removing orphaned device data", e)
        }
    }

    /**
     * Clean up references to device in shared data
     */
    private suspend fun cleanupDeviceReferences(userId: String, deviceId: String) {
        try {
            // Remove from active device lists
            val activeDevicesRef = database.getReference("users")
                .child(userId)
                .child("active_devices")

            activeDevicesRef.child(deviceId).removeValue().await()

            // Clean up device-specific notifications
            val notificationsRef = database.getReference("users")
                .child(userId)
                .child("device_notifications")
                .child(deviceId)

            notificationsRef.removeValue().await()

        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up device references", e)
        }
    }

    /**
     * Clean up device data when a device is unpaired (SELECTIVE CLEANUP)
     * IMPORTANT: Only removes temporary/cache data, PRESERVES user content
     */
    suspend fun cleanupUnpairedDevice(userId: String, deviceId: String): Result<String> {
        return try {
            Log.i(TAG, "Starting selective cleanup for unpaired device: $deviceId (user: $userId)")

            // Remove device from paired devices list
            val deviceRef = database.getReference("users").child(userId).child("devices").child(deviceId)
            deviceRef.removeValue().await()

            // 🗑️ SAFE TO DELETE - Temporary device data only
            val cleanupResults = cleanupDeviceTemporaryData(userId, deviceId)

            // 🛡️ PRESERVED - User content remains intact:
            // - Photos/documents in user_content or shared_data
            // - Conversation history in shared_data
            // - User preferences in user_settings
            // - Contacts in shared_data
            // - Important files and media

            val message = "Device unpaired successfully. Cleaned temporary data: ${cleanupResults.joinToString(", ")}. User content preserved."
            Log.i(TAG, message)

            Result.success(message)

        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up device data", e)
            Result.failure(e)
        }
    }

    /**
     * Clean up only temporary/cache data for an unpaired device
     * PRESERVES all user content (photos, documents, etc.)
     */
    private suspend fun cleanupDeviceTemporaryData(userId: String, deviceId: String): List<String> {
        val cleanupResults = mutableListOf<String>()

        try {
            // Remove device-specific messages (temporary copies only)
            val messagesRef = database.getReference("users").child(userId).child("device_messages").child(deviceId)
            val messagesSnapshot = messagesRef.get().await()
            if (messagesSnapshot.exists()) {
                messagesRef.removeValue().await()
                cleanupResults.add("${messagesSnapshot.childrenCount} temporary messages")
            }

            // Remove device-specific notifications (temporary copies only)
            val notificationsRef = database.getReference("users").child(userId).child("device_notifications").child(deviceId)
            val notificationsSnapshot = notificationsRef.get().await()
            if (notificationsSnapshot.exists()) {
                notificationsRef.removeValue().await()
                cleanupResults.add("${notificationsSnapshot.childrenCount} temporary notifications")
            }

            // Remove device-specific settings (device preferences only, not user content)
            val settingsRef = database.getReference("users").child(userId).child("device_settings").child(deviceId)
            val settingsSnapshot = settingsRef.get().await()
            if (settingsSnapshot.exists()) {
                settingsRef.removeValue().await()
                cleanupResults.add("device preferences")
            }

            // Remove device-specific cache (temporary cache data only)
            val cacheRef = database.getReference("users").child(userId).child("device_cache").child(deviceId)
            val cacheSnapshot = cacheRef.get().await()
            if (cacheSnapshot.exists()) {
                cacheRef.removeValue().await()
                cleanupResults.add("temporary cache")
            }

            // 🚫 DO NOT DELETE - User Content Preserved:
            // - Photos/documents: stored in shared_data/user_content
            // - Conversation history: stored in shared_data/conversations
            // - User preferences: stored in user_settings (not device_settings)
            // - Contacts: stored in shared_data/contacts
            // - Important files: stored in shared_data/files

            Log.i(TAG, "Device cleanup completed - user content preserved, temporary data cleaned")

        } catch (e: Exception) {
            Log.e(TAG, "Error during device temporary data cleanup", e)
            cleanupResults.add("partial cleanup (error occurred)")
        }

        return cleanupResults
    }

    /**
     * Clean up old migration records
     */
    private suspend fun cleanupOldMigrations(userId: String) {
        try {
            val migrationsRef = database.getReference("users")
                .child(userId)
                .child("migrations")

            val migrationsSnapshot = migrationsRef.get().await()

            for (migrationSnapshot in migrationsSnapshot.children) {
                val migrationId = migrationSnapshot.key ?: continue
                val migrationData = migrationSnapshot.value as? Map<String, Any> ?: continue

                val completed = migrationData["completed"] as? Boolean ?: false
                val timestamp = migrationData["timestamp"] as? Long ?: 0L

                // Remove completed migrations older than 90 days
                val daysSinceMigration = (System.currentTimeMillis() - timestamp) / (1000 * 60 * 60 * 24)
                if (completed && daysSinceMigration > 90) {
                    migrationSnapshot.ref.removeValue().await()
                    Log.d(TAG, "Removed old migration record: $migrationId")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up old migrations", e)
        }
    }

    /**
     * Clean up expired pairing tokens
     */
    private suspend fun cleanupExpiredTokens() {
        try {
            val tokensRef = database.getReference("pairing_tokens")
            val tokensSnapshot = tokensRef.get().await()

            val currentTime = System.currentTimeMillis()
            val expiryTime = 10 * 60 * 1000L // 10 minutes

            for (tokenSnapshot in tokensSnapshot.children) {
                val tokenData = tokenSnapshot.value as? Map<String, Any> ?: continue
                val timestamp = tokenData["timestamp"] as? Long ?: 0L

                if (currentTime - timestamp > expiryTime) {
                    tokenSnapshot.ref.removeValue().await()
                    Log.d(TAG, "Removed expired pairing token: ${tokenSnapshot.key}")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up expired tokens", e)
        }
    }

    /**
     * Clean up legacy anonymous user data
     */
    private suspend fun cleanupLegacyAnonymousUsers() {
        try {
            val usersRef = database.getReference("users")
            val usersSnapshot = usersRef.get().await()

            for (userSnapshot in usersSnapshot.children) {
                val userId = userSnapshot.key ?: continue

                // Check if this looks like a legacy anonymous user
                // (has data but no proper device structure)
                val devicesRef = database.getReference("users").child(userId).child("devices")
                val devicesSnapshot = devicesRef.get().await()

                val devicesChildren = devicesSnapshot.children.toList()
                if (devicesChildren.isEmpty()) {
                    // No devices - might be legacy data
                    val hasLegacyData = checkForLegacyData(userId)

                    if (hasLegacyData) {
                        // Check how old the data is
                        val dataAge = getDataAge(userId)
                        val daysOld = dataAge / (1000 * 60 * 60 * 24)

                        if (daysOld > ORPHANED_DATA_RETENTION_DAYS) {
                            Log.w(TAG, "Removing legacy anonymous user data: $userId (age: ${daysOld} days)")
                            val userRef = database.getReference("users").child(userId)
                            userRef.removeValue().await()
                        } else {
                            Log.d(TAG, "Keeping recent legacy data: $userId (age: ${daysOld} days)")
                        }
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up legacy anonymous users", e)
        }
    }

    /**
     * Check if user contains legacy data patterns
     */
    private suspend fun checkForLegacyData(userId: String): Boolean {
        return try {
            val userRef = database.getReference("users").child(userId)
            val snapshot = userRef.get().await()

            // Check for old data structures
            snapshot.hasChild("messages") ||
            snapshot.hasChild("contacts") ||
            snapshot.hasChild("calls")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get approximate age of data for user
     */
    private suspend fun getDataAge(userId: String): Long {
        return try {
            val userRef = database.getReference("users").child(userId)
            val snapshot = userRef.get().await()

            // Find the most recent timestamp in the data
            var latestTimestamp = 0L

            fun checkTimestamp(snapshot: DataSnapshot) {
                // Look for common timestamp fields
                val timestamp = snapshot.child("timestamp").getValue(Long::class.java) ?: 0L
                val date = snapshot.child("date").getValue(Long::class.java) ?: 0L
                val createdAt = snapshot.child("createdAt").getValue(Long::class.java) ?: 0L

                latestTimestamp = maxOf(latestTimestamp, timestamp, date, createdAt)

                // Recursively check children
                for (child in snapshot.children) {
                    checkTimestamp(child)
                }
            }

            checkTimestamp(snapshot)

            if (latestTimestamp > 0) {
                System.currentTimeMillis() - latestTimestamp
            } else {
                // If no timestamps found, assume it's old
                90L * 24 * 60 * 60 * 1000 // 90 days
            }
        } catch (e: Exception) {
            // If we can't determine age, assume it's old
            90L * 24 * 60 * 60 * 1000 // 90 days
        }
    }

    /**
     * Get information about what data gets cleaned up vs preserved
     */
    fun getCleanupPolicy(): Map<String, Any> {
        return mapOf(
            "cleanedUp" to listOf(
                "Temporary device messages (local copies)",
                "Temporary device notifications (local copies)",
                "Device-specific preferences (not user content)",
                "Temporary cache and session data",
                "Expired pairing tokens",
                "Legacy anonymous user accounts"
            ),
            "preserved" to listOf(
                "User photos and documents (in shared storage)",
                "Conversation history (accessible from all devices)",
                "User preferences and settings",
                "Contacts and address book",
                "Important files and media",
                "Shared content across devices"
            ),
            "retentionPolicy" to "Orphaned device data kept for 30 days before cleanup",
            "userControl" to "Users can manually manage their content in app settings"
        )
    }

    /**
     * Force cleanup for specific user (admin function)
     */
    suspend fun forceCleanupUser(userId: String): Result<String> {
        return try {
            Log.i(TAG, "Performing forced cleanup for user: $userId")

            val initialSize = estimateUserDataSize(userId)
            cleanupUserData(userId)
            val finalSize = estimateUserDataSize(userId)
            val savedBytes = initialSize - finalSize

            val message = "Cleanup completed. Saved approximately ${formatBytes(savedBytes)}"
            Log.i(TAG, message)

            Result.success(message)

        } catch (e: Exception) {
            Log.e(TAG, "Error during forced cleanup", e)
            Result.failure(e)
        }
    }

    /**
     * Estimate user's data size
     */
    private suspend fun estimateUserDataSize(userId: String): Long {
        return try {
            val userRef = database.getReference("users").child(userId)
            val snapshot = userRef.get().await()

            // Rough estimation
            estimateDataSize(snapshot)

        } catch (e: Exception) {
            0L
        }
    }

    private fun estimateDataSize(snapshot: DataSnapshot): Long {
        // Very rough estimation - in production you'd serialize to JSON and measure
        var size = 0L

        fun calculateSize(snapshot: DataSnapshot) {
            size += snapshot.key?.length?.toLong() ?: 0L

            when (val value = snapshot.value) {
                is String -> size += value.length.toLong()
                is Map<*, *> -> size += value.size.toLong() * 50 // Rough estimate per field
                is List<*> -> size += value.size.toLong() * 30 // Rough estimate per item
            }

            for (child in snapshot.children) {
                calculateSize(child)
            }
        }

        calculateSize(snapshot)
        return size
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes > 1024 * 1024 * 1024 -> "%.2f GB".format(bytes.toDouble() / (1024 * 1024 * 1024))
            bytes > 1024 * 1024 -> "%.2f MB".format(bytes.toDouble() / (1024 * 1024))
            bytes > 1024 -> "%.2f KB".format(bytes.toDouble() / 1024)
            else -> "$bytes bytes"
        }
    }

    /**
     * Get cleanup statistics
     */
    suspend fun getCleanupStats(): Map<String, Any> {
        return try {
            val usersRef = database.getReference("users")
            val usersSnapshot = usersRef.get().await()

            var totalUsers = 0
            var orphanedDevices = 0
            var legacyUsers = 0
            var totalDataSize = 0L

            for (userSnapshot in usersSnapshot.children) {
                val userId = userSnapshot.key ?: continue
                totalUsers++
                totalDataSize += estimateDataSize(userSnapshot)

                // Count orphaned devices
                val devicesRef = database.getReference("users").child(userId).child("devices")
                val devicesSnapshot = devicesRef.get().await()
                val deviceDataRef = database.getReference("users").child(userId).child("device_data")
                val deviceDataSnapshot = deviceDataRef.get().await()

                for (deviceData in deviceDataSnapshot.children) {
                    val deviceId = deviceData.key ?: continue
                    val deviceExists = devicesSnapshot.children.any { it.key == deviceId }
                    if (!deviceExists) {
                        orphanedDevices++
                    }
                }

                // Check for legacy users
                val devicesChildren = devicesSnapshot.children.toList()
                if (devicesChildren.isEmpty() && checkForLegacyData(userId)) {
                    legacyUsers++
                }
            }

            mapOf(
                "totalUsers" to totalUsers,
                "orphanedDevices" to orphanedDevices,
                "legacyUsers" to legacyUsers,
                "totalDataSize" to totalDataSize,
                "formattedDataSize" to formatBytes(totalDataSize)
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error getting cleanup stats", e)
            emptyMap()
        }
    }
}
