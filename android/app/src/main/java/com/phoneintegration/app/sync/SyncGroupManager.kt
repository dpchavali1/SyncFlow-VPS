package com.phoneintegration.app.sync

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Log
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await
import java.util.UUID

/**
 * Manages sync group operations for Android device
 * Handles pairing, device registration, and sync group recovery
 */
class SyncGroupManager(
    private val context: Context,
    private val database: FirebaseDatabase = FirebaseDatabase.getInstance()
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("syncflow", Context.MODE_PRIVATE)
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
     * Get the current sync group ID
     * Returns null if device not yet paired
     */
    val syncGroupId: String?
        get() = prefs.getString(SYNC_GROUP_ID_KEY, null)

    /**
     * Check if device is part of a sync group
     */
    fun isPaired(): Boolean {
        return syncGroupId != null
    }

    /**
     * Set the sync group ID (usually from scanned QR code)
     */
    fun setSyncGroupId(groupId: String) {
        prefs.edit().putString(SYNC_GROUP_ID_KEY, groupId).apply()
    }

    /**
     * Join an existing sync group (when user scans QR code from macOS/Web)
     * Validates device limit before allowing join
     */
    suspend fun joinSyncGroup(
        scannedSyncGroupId: String,
        deviceName: String = "Android Phone"
    ): Result<JoinResult> {
        return try {
            Log.d(TAG, "[SyncGroupManager] joinSyncGroup called with ID: $scannedSyncGroupId")
            val groupRef = database.reference.child("syncGroups").child(scannedSyncGroupId)
            val groupSnapshot = groupRef.get().await()

            Log.d(TAG, "[SyncGroupManager] Group snapshot exists: ${groupSnapshot.exists()}")
            if (!groupSnapshot.exists()) {
                Log.e(TAG, "[SyncGroupManager] Sync group not found: $scannedSyncGroupId")
                return Result.failure(Exception("Sync group not found"))
            }

            val groupData = groupSnapshot.value as? Map<*, *> ?: emptyMap<String, Any>()
            val plan = (groupData["plan"] as? String) ?: "free"
            val deviceLimit = if (plan == "free") 3 else 999
            val devicesMap = (groupData["devices"] as? Map<*, *>) ?: emptyMap<String, Any>()
            val currentDevices = devicesMap.size

            Log.d(TAG, "[SyncGroupManager] Group data: plan=$plan, deviceLimit=$deviceLimit, currentDevices=$currentDevices")

            // If this device is already in the group, treat as success and refresh metadata
            if (devicesMap.containsKey(deviceId)) {
                Log.d(TAG, "[SyncGroupManager] Device already in sync group, refreshing status")
                setSyncGroupId(scannedSyncGroupId)

                val now = System.currentTimeMillis()
                val existingUpdate = mapOf(
                    "status" to "active",
                    "deviceName" to deviceName,
                    "lastSyncedAt" to now
                )
                groupRef.child("devices").child(deviceId).updateChildren(existingUpdate).await()

                groupRef.child("history").child(now.toString()).setValue(
                    mapOf(
                        "action" to "device_rejoined",
                        "deviceId" to deviceId,
                        "deviceType" to "android",
                        "deviceName" to deviceName
                    )
                ).await()

                return Result.success(
                    JoinResult(
                        success = true,
                        deviceCount = currentDevices,
                        limit = deviceLimit
                    )
                )
            }

            // Check device limit
            if (currentDevices >= deviceLimit) {
                Log.w(TAG, "[SyncGroupManager] Device limit reached: $currentDevices/$deviceLimit")
                return Result.failure(
                    Exception(
                        "Device limit reached: $currentDevices/$deviceLimit. " +
                                "Upgrade to Pro for unlimited devices."
                    )
                )
            }

            // Save locally
            setSyncGroupId(scannedSyncGroupId)
            Log.d(TAG, "[SyncGroupManager] Saved sync group ID locally: $scannedSyncGroupId")

            // Register device in Firebase
            val now = System.currentTimeMillis()
            val deviceData = mapOf(
                "deviceType" to "android",
                "joinedAt" to now,
                "status" to "active",
                "deviceName" to deviceName
            )

            Log.d(TAG, "[SyncGroupManager] Registering device: $deviceId with data: $deviceData")
            groupRef.child("devices").child(deviceId).setValue(deviceData).await()
            Log.d(TAG, "[SyncGroupManager] Device registered successfully")

            // Log to history
            Log.d(TAG, "[SyncGroupManager] Logging to history")
            groupRef.child("history").child(now.toString()).setValue(
                mapOf(
                    "action" to "device_joined",
                    "deviceId" to deviceId,
                    "deviceType" to "android",
                    "deviceName" to deviceName
                )
            ).await()

            Log.d(TAG, "[SyncGroupManager] joinSyncGroup completed successfully")
            Result.success(
                JoinResult(
                    success = true,
                    deviceCount = currentDevices + 1,
                    limit = deviceLimit
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "[SyncGroupManager] joinSyncGroup failed", e)
            Result.failure(e)
        }
    }

    /**
     * Create a new sync group (called when first device initializes)
     * Only should be called by macOS/Web - Android typically scans and joins
     */
    suspend fun createSyncGroup(deviceName: String = "Android Phone"): Result<String> {
        return try {
            val newGroupId = "sync_${UUID.randomUUID()}"
            val now = System.currentTimeMillis()

            val groupData = mapOf(
                "plan" to "free",
                "deviceLimit" to 3,
                "masterDevice" to deviceId,
                "createdAt" to now,
                "devices" to mapOf(
                    deviceId to mapOf(
                        "deviceType" to "android",
                        "joinedAt" to now,
                        "status" to "active",
                        "deviceName" to deviceName
                    )
                )
            )

            database.reference.child("syncGroups").child(newGroupId)
                .setValue(groupData).await()

            setSyncGroupId(newGroupId)

            Result.success(newGroupId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Recover sync group on reinstall
     * Searches Firebase for existing device entry
     */
    suspend fun recoverSyncGroup(): Result<String> {
        return try {
            val groupsRef = database.reference.child("syncGroups")
            val allGroupsSnapshot = groupsRef.get().await()

            if (!allGroupsSnapshot.exists()) {
                return Result.failure(Exception("No sync groups found"))
            }

            // Search for sync group containing this device
            for (groupSnapshot in allGroupsSnapshot.children) {
                val groupId = groupSnapshot.key ?: continue
                val devices = groupSnapshot.child("devices").value as? Map<*, *> ?: emptyMap<String, Any>()

                if (devices.containsKey(deviceId)) {
                    setSyncGroupId(groupId)
                    return Result.success(groupId)
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
    suspend fun getSyncGroupInfo(): Result<SyncGroupInfo> {
        return try {
            val groupId = syncGroupId
                ?: return Result.failure(Exception("Device not paired to any sync group"))

            val groupRef = database.reference.child("syncGroups").child(groupId)
            val groupSnapshot = groupRef.get().await()

            if (!groupSnapshot.exists()) {
                return Result.failure(Exception("Sync group no longer exists"))
            }

            val groupData = groupSnapshot.value as? Map<*, *> ?: emptyMap<String, Any>()
            val plan = (groupData["plan"] as? String) ?: "free"
            val deviceLimit = if (plan == "free") 3 else 999
            val devices = (groupData["devices"] as? Map<*, *>) ?: emptyMap<String, Any>()

            val devicesList = devices.map { (deviceId, info) ->
                val infoMap = info as? Map<*, *> ?: emptyMap<String, Any>()
                DeviceInfo(
                    deviceId = deviceId as String,
                    deviceType = (infoMap["deviceType"] as? String) ?: "unknown",
                    joinedAt = (infoMap["joinedAt"] as? Long) ?: 0L,
                    lastSyncedAt = infoMap["lastSyncedAt"] as? Long,
                    status = (infoMap["status"] as? String) ?: "active",
                    deviceName = infoMap["deviceName"] as? String
                )
            }

            Result.success(
                SyncGroupInfo(
                    plan = plan,
                    deviceLimit = deviceLimit,
                    deviceCount = devices.size,
                    devices = devicesList
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Update device last synced time (call this on periodic sync)
     */
    suspend fun updateLastSyncTime(): Result<Boolean> {
        return try {
            val groupId = syncGroupId
                ?: return Result.failure(Exception("Device not paired"))

            val now = System.currentTimeMillis()
            database.reference.child("syncGroups").child(groupId)
                .child("devices").child(deviceId).child("lastSyncedAt")
                .setValue(now).await()

            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Remove this device from sync group (user action)
     */
    suspend fun leaveGroup(): Result<Boolean> {
        return try {
            val groupId = syncGroupId
                ?: return Result.failure(Exception("Device not paired"))

            val groupRef = database.reference.child("syncGroups").child(groupId)

            // Remove device
            groupRef.child("devices").child(deviceId).removeValue().await()

            // Log to history
            groupRef.child("history").child(System.currentTimeMillis().toString())
                .setValue(
                    mapOf(
                        "action" to "device_removed",
                        "deviceId" to deviceId
                    )
                ).await()

            // Clear local storage
            prefs.edit().remove(SYNC_GROUP_ID_KEY).apply()

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
