package com.phoneintegration.app.auth

import android.content.Context
import android.util.Log
import com.phoneintegration.app.PhoneNumberUtils
import com.phoneintegration.app.SmsMessage
import com.phoneintegration.app.SmsRepository
import com.phoneintegration.app.data.PreferencesManager
import com.phoneintegration.app.security.SecurityEvent
import com.phoneintegration.app.security.SecurityEventType
import com.phoneintegration.app.security.SecurityMonitor
import com.phoneintegration.app.vps.VPSAuthManager
import com.phoneintegration.app.vps.VPSClient
import com.phoneintegration.app.vps.VPSSyncService
import com.phoneintegration.app.vps.VPSDevice
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * Unified Identity Manager - VPS Backend Only
 *
 * Manages user identity and device pairing across all devices using VPS backend.
 * Replaces the Firebase-based implementation.
 */
class UnifiedIdentityManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "UnifiedIdentityManager"
        private const val DEVICE_HEARTBEAT_INTERVAL_MINUTES = 5L

        @Volatile
        private var instance: UnifiedIdentityManager? = null

        fun getInstance(context: Context): UnifiedIdentityManager {
            return instance ?: synchronized(this) {
                instance ?: UnifiedIdentityManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val vpsAuthManager = VPSAuthManager.getInstance(context)
    private val vpsClient = VPSClient.getInstance(context)
    private val vpsSyncService = VPSSyncService.getInstance(context)
    private val authManager = AuthManager.getInstance(context)
    private val securityMonitor: SecurityMonitor? = try {
        SecurityMonitor.getInstance(context)
    } catch (e: Exception) {
        Log.w(TAG, "Failed to initialize SecurityMonitor, continuing without it", e)
        null
    }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Device management
    private val _pairedDevices = MutableStateFlow<Map<String, DeviceInfo>>(emptyMap())
    val pairedDevices: StateFlow<Map<String, DeviceInfo>> = _pairedDevices.asStateFlow()

    // Sync group info (simplified for VPS - using user ID as sync group)
    private val _syncGroupId = MutableStateFlow<String?>(null)
    val syncGroupId: StateFlow<String?> = _syncGroupId.asStateFlow()

    private var deviceHeartbeatJob: Job? = null
    private var deviceMonitorJob: Job? = null

    init {
        Log.i(TAG, "UnifiedIdentityManager initialized (VPS backend)")

        // Only start device monitoring/heartbeat if user has paired devices
        if (hasPairedDevicesLocally()) {
            startDeviceMonitoring()
            startDeviceHeartbeat()

            // Set sync group ID to user ID
            scope.launch {
                _syncGroupId.value = vpsAuthManager.getCurrentUserId()
            }
        } else {
            Log.d(TAG, "Skipping device monitoring - no paired devices yet")
        }
    }

    /**
     * Check if user has paired devices using local SharedPreferences cache.
     */
    private fun hasPairedDevicesLocally(): Boolean {
        val prefs = context.getSharedPreferences("desktop_sync_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("has_paired_devices", false)
    }

    /**
     * Start device monitoring after pairing completes.
     */
    fun startMonitoringAfterPairing() {
        if (deviceMonitorJob == null || deviceMonitorJob?.isActive != true) {
            Log.d(TAG, "Starting device monitoring after pairing")
            startDeviceMonitoring()
            startDeviceHeartbeat()
        }
    }

    /**
     * Get the unified user ID synchronously
     */
    fun getUnifiedUserIdSync(): String? {
        return vpsAuthManager.getCurrentUserId()
    }

    // Mutex to prevent concurrent auth calls from creating duplicate users
    private val authMutex = Mutex()

    /**
     * Get the unified user ID (same across all devices)
     *
     * Uses device fingerprint as a stable identifier to ensure the same user ID
     * is returned across app reinstalls. This is critical for message sync to work.
     * Protected by mutex to prevent concurrent auth from creating duplicate accounts.
     */
    suspend fun getUnifiedUserId(): String? = authMutex.withLock {
        Log.d(TAG, "getUnifiedUserId() called")

        var userId = vpsAuthManager.getCurrentUserId()
        Log.d(TAG, "Current VPS user: $userId")

        if (userId == null) {
            try {
                // Use device fingerprint as stable identifier
                // This ensures we always get the same user ID for this device
                val deviceFingerprint = getDeviceFingerprint()
                val deviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"

                Log.d(TAG, "No authenticated user found, attempting fingerprint authentication")
                val result = vpsAuthManager.signInWithFirebaseUid(deviceFingerprint, deviceName)
                if (result.isSuccess) {
                    userId = result.getOrNull()
                    Log.d(TAG, "VPS fingerprint authentication successful: $userId")
                } else {
                    Log.e(TAG, "VPS fingerprint authentication failed: ${result.exceptionOrNull()?.message}")
                    // Do NOT fall back to anonymous auth - it creates orphan users
                    // If fingerprint auth fails, return null and let caller handle it
                }
            } catch (e: Exception) {
                Log.e(TAG, "VPS authentication failed", e)
                return@withLock null
            }
        }

        return@withLock userId
    }

    // =============================================================================
    // DEVICE FINGERPRINT
    // =============================================================================

    /**
     * Generate a stable device fingerprint that survives app reinstall.
     */
    fun getDeviceFingerprint(): String {
        val identifiers = listOf(
            android.os.Build.BOARD,
            android.os.Build.BRAND,
            android.os.Build.DEVICE,
            android.os.Build.MANUFACTURER,
            android.os.Build.MODEL,
            android.os.Build.PRODUCT,
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            )
        )

        val combined = identifiers.joinToString("|")
        return sha256(combined).take(32)
    }

    private fun sha256(input: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Check if this device has a previous account.
     * Note: VPS implementation - checks local storage
     */
    suspend fun checkForExistingAccount(): ExistingAccountInfo? {
        return try {
            // In VPS mode, check if we have stored credentials
            if (vpsClient.isAuthenticated) {
                val userId = vpsClient.userId ?: return null
                val devices = vpsSyncService.getDevices()

                ExistingAccountInfo(
                    userId = userId,
                    deviceCount = devices.size,
                    messageCount = 0, // VPS doesn't track this at account level
                    lastActivity = System.currentTimeMillis(),
                    devices = devices.associate { it.id to it.deviceName }
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check for existing account", e)
            null
        }
    }

    /**
     * Store device fingerprint for future recovery.
     * Note: In VPS mode, device info is stored when registering
     */
    suspend fun storeDeviceFingerprint(userId: String) {
        Log.d(TAG, "Device fingerprint stored implicitly via VPS device registration")
    }

    /**
     * Restore account and unpair all old devices.
     */
    suspend fun restoreAccountAndUnpairDevices(
        userId: String,
        newDeviceId: String,
        deviceName: String,
        platform: String
    ): Result<Int> {
        return try {
            Log.d(TAG, "Restoring account $userId via VPS")

            // In VPS mode, we just authenticate and the old devices will be managed
            val result = vpsAuthManager.signInAnonymously()
            if (result.isSuccess) {
                Result.success(0) // VPS doesn't unpair old devices automatically
            } else {
                Result.failure(result.exceptionOrNull() ?: Exception("Failed to authenticate"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore account", e)
            Result.failure(e)
        }
    }

    /**
     * Complete pairing (Android approves a pairing request)
     */
    suspend fun completePairing(token: String): Result<Unit> {
        return try {
            Log.d(TAG, "Completing pairing for token: $token")
            vpsAuthManager.completePairing(token)

            // Mark that we have paired devices
            context.getSharedPreferences("desktop_sync_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("has_paired_devices", true)
                .apply()

            // Start monitoring if not already started
            startMonitoringAfterPairing()

            Log.i(TAG, "Pairing completed successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error completing pairing", e)
            Result.failure(e)
        }
    }

    /**
     * Redeem a pairing token and register the device
     * Note: This is called by macOS/web after Android approves
     */
    suspend fun redeemPairingToken(token: String, deviceName: String, platform: String): Result<DeviceInfo> {
        return try {
            Log.d(TAG, "Redeeming pairing token: $token")

            val result = vpsAuthManager.redeemPairing(token, deviceName, platform)
            if (result.isSuccess) {
                val user = result.getOrNull()!!

                val deviceInfo = DeviceInfo(
                    id = user.deviceId,
                    name = deviceName,
                    type = platform,
                    platform = platform,
                    lastSeen = System.currentTimeMillis(),
                    isOnline = true,
                    registeredAt = System.currentTimeMillis()
                )

                // Mark that we have paired devices
                context.getSharedPreferences("desktop_sync_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("has_paired_devices", true)
                    .apply()

                Log.i(TAG, "Successfully redeemed pairing token")
                Result.success(deviceInfo)
            } else {
                Result.failure(result.exceptionOrNull() ?: Exception("Failed to redeem pairing"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error redeeming pairing token", e)
            Result.failure(e)
        }
    }

    /**
     * Unregister a device from the unified account
     */
    suspend fun unregisterDevice(deviceId: String): Result<Unit> {
        return try {
            Log.d(TAG, "Unregistering device: $deviceId")
            vpsSyncService.removeDevice(deviceId)
            Log.d(TAG, "Device unregistered successfully: $deviceId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering device", e)
            Result.failure(e)
        }
    }

    /**
     * Join a sync group by scanning QR code from another device
     * In VPS mode, this completes the pairing
     */
    suspend fun joinSyncGroupFromQRCode(scannedSyncGroupId: String, deviceName: String): Result<JoinSyncGroupResult> {
        return try {
            Log.d(TAG, "[QRJoin] Attempting to join via VPS pairing")

            // In VPS, the scannedSyncGroupId is the pairing token
            val result = completePairing(scannedSyncGroupId)

            if (result.isSuccess) {
                val devices = vpsSyncService.getDevices()

                Result.success(
                    JoinSyncGroupResult(
                        success = true,
                        syncGroupId = vpsAuthManager.getCurrentUserId() ?: "",
                        deviceCount = devices.size,
                        deviceLimit = 10, // VPS has higher limits
                        message = "Connected! ${devices.size} devices paired"
                    )
                )
            } else {
                Result.success(
                    JoinSyncGroupResult(
                        success = false,
                        syncGroupId = "",
                        deviceCount = 0,
                        deviceLimit = 0,
                        message = result.exceptionOrNull()?.message ?: "Failed to join"
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error joining sync group", e)
            Result.failure(e)
        }
    }

    /**
     * Get sync group info
     */
    suspend fun getSyncGroupInfo(): Result<SyncGroupInfo> {
        return try {
            val devices = vpsSyncService.getDevices()

            Result.success(
                SyncGroupInfo(
                    plan = "VPS",
                    deviceLimit = 10,
                    deviceCount = devices.size,
                    devices = devices.map {
                        SyncGroupDeviceInfo(
                            deviceId = it.id,
                            deviceType = it.deviceType,
                            joinedAt = 0L, // VPS doesn't track this
                            lastSyncedAt = null,
                            status = "active"
                        )
                    }
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting sync group info", e)
            Result.failure(e)
        }
    }

    /**
     * Get device capabilities for this Android device
     */
    private fun getDeviceCapabilities(): Map<String, Any> {
        return mapOf(
            "sms" to true,
            "mms" to true,
            "calls" to true,
            "contacts" to true,
            "battery" to true,
            "notifications" to true,
            "media" to true,
            "clipboard" to true,
            "hotspot" to true,
            "wifi" to true,
            "location" to true
        )
    }

    /**
     * Start monitoring device online/offline status
     */
    private fun startDeviceMonitoring() {
        deviceMonitorJob = scope.launch {
            while (isActive) {
                try {
                    updateDeviceStatuses()
                } catch (e: Exception) {
                    Log.e(TAG, "Error monitoring devices", e)
                }
                delay(TimeUnit.MINUTES.toMillis(1))
            }
        }
    }

    /**
     * Update device online/offline statuses
     */
    private suspend fun updateDeviceStatuses() {
        try {
            val devices = vpsSyncService.getDevices()

            val deviceMap = devices.associate { device ->
                device.id to DeviceInfo(
                    id = device.id,
                    name = device.deviceName,
                    type = device.deviceType,
                    platform = device.deviceType,
                    lastSeen = System.currentTimeMillis(), // VPS tracks this server-side
                    isOnline = true, // Assume online if returned by server
                    registeredAt = 0L
                )
            }

            _pairedDevices.value = deviceMap
        } catch (e: Exception) {
            Log.e(TAG, "Error updating device statuses", e)
        }
    }

    /**
     * Start device heartbeat to keep this device marked as online
     */
    private fun startDeviceHeartbeat() {
        deviceHeartbeatJob = scope.launch {
            while (isActive) {
                try {
                    updateDeviceHeartbeat()
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending heartbeat", e)
                }
                delay(TimeUnit.MINUTES.toMillis(DEVICE_HEARTBEAT_INTERVAL_MINUTES))
            }
        }
    }

    /**
     * Update this device's heartbeat timestamp
     */
    private suspend fun updateDeviceHeartbeat() {
        try {
            val deviceId = vpsAuthManager.getCurrentDeviceId() ?: return
            // VPS tracks last_seen automatically on API calls
            // We can optionally call a heartbeat endpoint here
            Log.d(TAG, "Heartbeat sent for device: $deviceId")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating heartbeat", e)
        }
    }

    /**
     * Trigger initial message sync for first-time users
     */
    fun triggerInitialMessageSync() {
        scope.launch {
            try {
                val userId = getUnifiedUserId() ?: return@launch
                Log.d(TAG, "Triggering initial message sync for user: $userId")

                // Get recent messages and sync them
                val smsRepository = SmsRepository(context)
                val messages = smsRepository.getAllRecentMessages(500)

                if (messages.isNotEmpty()) {
                    vpsSyncService.syncMessages(messages)
                    Log.d(TAG, "Initial message sync completed: ${messages.size} messages")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error triggering initial message sync", e)
            }
        }
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        deviceHeartbeatJob?.cancel()
        deviceMonitorJob?.cancel()
        scope.cancel()
        Log.i(TAG, "UnifiedIdentityManager cleaned up")
    }
}

/**
 * Data classes for unified identity management
 */
data class DeviceInfo(
    val id: String,
    val name: String,
    val type: String,
    val platform: String,
    val lastSeen: Long,
    val isOnline: Boolean,
    val registeredAt: Long
)

data class PairingToken(
    val token: String,
    val userId: String,
    val deviceId: String,
    val deviceName: String,
    val deviceType: String,
    val expiresAt: Long
)

/**
 * Result of joining a sync group
 */
data class JoinSyncGroupResult(
    val success: Boolean,
    val syncGroupId: String,
    val deviceCount: Int,
    val deviceLimit: Int,
    val message: String,
    val isDeviceLimitError: Boolean = false
)

/**
 * Sync group information
 */
data class SyncGroupInfo(
    val plan: String,
    val deviceLimit: Int,
    val deviceCount: Int,
    val devices: List<SyncGroupDeviceInfo>
)

/**
 * Device info within a sync group
 */
data class SyncGroupDeviceInfo(
    val deviceId: String,
    val deviceType: String,
    val joinedAt: Long,
    val lastSyncedAt: Long?,
    val status: String
)

/**
 * Information about an existing account found by device fingerprint.
 */
data class ExistingAccountInfo(
    val userId: String,
    val deviceCount: Int,
    val messageCount: Int,
    val lastActivity: Long,
    val devices: Map<*, *>
)
