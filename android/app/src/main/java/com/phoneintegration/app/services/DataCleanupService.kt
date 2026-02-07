package com.phoneintegration.app.services

import android.content.Context
import android.util.Log
import com.phoneintegration.app.vps.VPSClient
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

/**
 * Data cleanup service - VPS Backend Only
 *
 * In VPS mode, data cleanup is primarily handled server-side.
 * This service provides client-side API calls to trigger cleanup operations.
 *
 * 🗑️ WHAT GETS CLEANED UP (server-side):
 * - Device-specific temporary data
 * - Expired pairing tokens
 * - Old session data
 *
 * 🛡️ WHAT GETS PRESERVED:
 * - User photos and documents
 * - Conversation history
 * - User preferences
 * - Contacts
 * - Important files and media
 */
class DataCleanupService private constructor(private val context: Context) {

    companion object {
        private const val TAG = "DataCleanupService"
        private const val CLEANUP_INTERVAL_DAYS = 7L

        @Volatile
        private var instance: DataCleanupService? = null

        fun getInstance(context: Context): DataCleanupService {
            return instance ?: synchronized(this) {
                instance ?: DataCleanupService(context.applicationContext).also { instance = it }
            }
        }
    }

    private val vpsClient = VPSClient.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        schedulePeriodicCleanup()
    }

    /**
     * Schedule periodic cleanup
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
     * Perform data cleanup via VPS API
     */
    suspend fun performCleanup() {
        try {
            Log.i(TAG, "Starting data cleanup process")
            vpsClient.performDataCleanup()
            Log.i(TAG, "Data cleanup process completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error during data cleanup", e)
        }
    }

    /**
     * Clean up device data when a device is unpaired
     */
    suspend fun cleanupUnpairedDevice(userId: String, deviceId: String): Result<String> {
        return try {
            Log.i(TAG, "Starting cleanup for unpaired device: $deviceId")
            vpsClient.cleanupUnpairedDevice(deviceId)
            val message = "Device unpaired successfully"
            Log.i(TAG, message)
            Result.success(message)
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up device data", e)
            Result.failure(e)
        }
    }

    /**
     * Get information about what data gets cleaned up vs preserved
     */
    fun getCleanupPolicy(): Map<String, Any> {
        return mapOf(
            "cleanedUp" to listOf(
                "Temporary device data",
                "Device-specific cache",
                "Expired pairing tokens",
                "Old session data"
            ),
            "preserved" to listOf(
                "User photos and documents",
                "Conversation history",
                "User preferences and settings",
                "Contacts and address book",
                "Important files and media"
            ),
            "retentionPolicy" to "Data cleanup handled server-side",
            "userControl" to "Users can manually manage their content in app settings"
        )
    }

    /**
     * Force cleanup for current user
     */
    suspend fun forceCleanup(): Result<String> {
        return try {
            Log.i(TAG, "Performing forced cleanup")
            vpsClient.performDataCleanup()
            Result.success("Cleanup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error during forced cleanup", e)
            Result.failure(e)
        }
    }

    /**
     * Get cleanup statistics
     */
    suspend fun getCleanupStats(): Map<String, Any> {
        return try {
            vpsClient.getCleanupStats()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting cleanup stats", e)
            emptyMap()
        }
    }

    fun cleanup() {
        scope.cancel()
    }
}
