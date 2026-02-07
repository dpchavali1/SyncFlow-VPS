package com.phoneintegration.app.sync

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Log
import com.phoneintegration.app.vps.VPSClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Manages sync group operations for Android device - VPS Backend Only
 * Handles pairing, device registration, and sync group recovery via VPS API
 */
class SyncGroupManager(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("syncflow", Context.MODE_PRIVATE)
    private val vpsClient = VPSClient.getInstance(context)
    private val TAG = "SyncGroupManager"

    companion object {
        private const val SYNC_GROUP_ID_KEY = "sync_group_id"
        private const val DEVICE_ID_KEY = "device_id"
    }

    /**
     * Get the stable device ID for this Android device
     * Uses Android Security.Secure.ANDROID_ID as primary, fallback to stored UUID
     */
    val deviceId: String
        get() {
            var id = prefs.getString(DEVICE_ID_KEY, null)
            if (id == null) {
                id = try {
                    Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                } catch (e: Exception) {
                    "android_${UUID.randomUUID()}"
                }
                prefs.edit().putString(DEVICE_ID_KEY, id).apply()
            }
            return id
        }

    /**
     * Get the current sync group ID (user ID in VPS mode)
     * Returns null if device not yet paired
     */
    val syncGroupId: String?
        get() = vpsClient.userId ?: prefs.getString(SYNC_GROUP_ID_KEY, null)

    /**
     * Check if device is part of a sync group
     */
    fun isPaired(): Boolean {
        return vpsClient.isAuthenticated
    }

    /**
     * Set the sync group ID (usually from pairing flow)
     */
    fun setSyncGroupId(groupId: String) {
        prefs.edit().putString(SYNC_GROUP_ID_KEY, groupId).apply()
    }

    /**
     * Join an existing sync group (when user scans QR code from macOS/Web)
     * In VPS mode, this completes the pairing via VPS API
     */
    suspend fun joinSyncGroup(
        scannedSyncGroupId: String,
        deviceName: String = "Android Phone"
    ): Result<JoinResult> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "[SyncGroupManager] joinSyncGroup called with token: $scannedSyncGroupId")

            // In VPS mode, the scanned code is a pairing token
            val user = vpsClient.redeemPairing(scannedSyncGroupId, deviceName, "android")

            setSyncGroupId(user.userId)
            Log.d(TAG, "[SyncGroupManager] Pairing completed, user ID: ${user.userId}")

            // Get device count from devices list
            val devicesResponse = vpsClient.getDevices()
            val deviceCount = devicesResponse.devices.size

            Result.success(
                JoinResult(
                    success = true,
                    deviceCount = deviceCount,
                    limit = 999 // VPS doesn't have strict device limits
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "[SyncGroupManager] joinSyncGroup failed", e)
            Result.failure(e)
        }
    }

    /**
     * Create a new sync group (called when first device initializes)
     * In VPS mode, this creates a new anonymous user
     */
    suspend fun createSyncGroup(deviceName: String = "Android Phone"): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val user = vpsClient.authenticateAnonymous()
                setSyncGroupId(user.userId)
                Result.success(user.userId)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Recover sync group on reinstall
     * In VPS mode, this tries to restore session from saved tokens
     */
    suspend fun recoverSyncGroup(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val initialized = vpsClient.initialize()
            if (initialized) {
                val userId = vpsClient.userId
                if (userId != null) {
                    setSyncGroupId(userId)
                    return@withContext Result.success(userId)
                }
            }
            Result.failure(Exception("No previous sync group found for this device"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get current sync group information
     */
    suspend fun getSyncGroupInfo(): Result<SyncGroupInfo> = withContext(Dispatchers.IO) {
        try {
            if (!vpsClient.isAuthenticated) {
                return@withContext Result.failure(Exception("Device not paired to any sync group"))
            }

            val devicesResponse = vpsClient.getDevices()
            val devices = devicesResponse.devices

            val devicesList = devices.map { device ->
                DeviceInfo(
                    deviceId = device.id,
                    deviceType = device.deviceType,
                    joinedAt = 0L, // VPS doesn't expose this
                    lastSyncedAt = null,
                    status = "active",
                    deviceName = device.deviceName
                )
            }

            Result.success(
                SyncGroupInfo(
                    plan = "vps", // VPS mode
                    deviceLimit = 999,
                    deviceCount = devices.size,
                    devices = devicesList
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Update device last synced time
     * In VPS mode, this is handled automatically by the server
     */
    suspend fun updateLastSyncTime(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            // VPS tracks this automatically via API calls
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Remove this device from sync group (user action)
     */
    suspend fun leaveGroup(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val deviceId = vpsClient.deviceId
            if (deviceId != null) {
                vpsClient.removeDevice(deviceId)
            }

            // Clear local storage
            prefs.edit().remove(SYNC_GROUP_ID_KEY).apply()
            vpsClient.logout()

            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Data classes for responses
     */
    data class JoinResult(
        val success: Boolean,
        val deviceCount: Int = 0,
        val limit: Int = 3
    )

    data class SyncGroupInfo(
        val plan: String,
        val deviceLimit: Int,
        val deviceCount: Int,
        val devices: List<DeviceInfo>
    )

    data class DeviceInfo(
        val deviceId: String,
        val deviceType: String,
        val joinedAt: Long,
        val lastSyncedAt: Long?,
        val status: String,
        val deviceName: String?
    )
}
