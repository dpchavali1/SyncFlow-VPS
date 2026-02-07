package com.phoneintegration.app.desktop

import android.content.Context
import android.util.Log
import com.phoneintegration.app.SmsMessage
import com.phoneintegration.app.data.database.Group
import com.phoneintegration.app.data.database.GroupMember
import com.phoneintegration.app.data.database.SpamMessage
import com.phoneintegration.app.e2ee.SignalProtocolManager
import com.phoneintegration.app.vps.VPSClient
import com.phoneintegration.app.vps.VPSOutgoingMessage
import com.phoneintegration.app.vps.VPSSyncService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * DesktopSyncService (VPS mode only)
 *
 * Minimal VPS-backed implementation that replaces the old Firebase-based sync.
 * This keeps public APIs used across the app, while unsupported features become no-ops.
 */
class DesktopSyncService(context: Context) {

    private val appContext = context.applicationContext
    private val vpsClient = VPSClient.getInstance(appContext)
    private val vpsSyncService = VPSSyncService.getInstance(appContext)

    companion object {
        private const val TAG = "DesktopSyncService"

        // SharedPreferences keys for cached paired device status
        private const val PREFS_NAME = "desktop_sync_prefs"
        private const val KEY_HAS_PAIRED_DEVICES = "has_paired_devices"
        private const val KEY_PAIRED_DEVICES_COUNT = "paired_devices_count"
        private const val KEY_LAST_CHECK_TIME = "last_paired_check_time"
        private const val CACHE_VALID_MS = 5 * 60 * 1000L // 5 minutes

        fun hasPairedDevices(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_HAS_PAIRED_DEVICES, false)
        }

        fun getCachedDeviceCount(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getInt(KEY_PAIRED_DEVICES_COUNT, 0)
        }

        fun updatePairedDevicesCache(context: Context, hasPaired: Boolean, count: Int = 0) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean(KEY_HAS_PAIRED_DEVICES, hasPaired)
                .putInt(KEY_PAIRED_DEVICES_COUNT, count)
                .putLong(KEY_LAST_CHECK_TIME, System.currentTimeMillis())
                .apply()
            Log.d(TAG, "Updated paired devices cache: hasPaired=$hasPaired, count=$count")
        }
    }

    private fun isAuthenticated(): Boolean = vpsClient.isAuthenticated

    private fun toOutgoingPayload(message: VPSOutgoingMessage): Map<String, Any?> {
        return mapOf(
            "_messageId" to message.id,
            "address" to message.address,
            "body" to message.body,
            "timestamp" to message.timestamp,
            "simSubscriptionId" to message.simSubscriptionId,
            "isMms" to false,
            "attachments" to emptyList<Map<String, Any?>>()
        )
    }

    // ===================== Messages =====================

    suspend fun syncMessage(message: SmsMessage, skipAttachments: Boolean = false) {
        if (!isAuthenticated()) return
        vpsSyncService.syncMessage(message, skipAttachments)
    }

    suspend fun syncMessages(
        messages: List<SmsMessage>,
        skipAttachments: Boolean = false,
        onProgress: ((synced: Int, total: Int) -> Unit)? = null
    ) {
        if (!isAuthenticated()) return

        var synced = 0
        val total = messages.size

        for (message in messages) {
            vpsSyncService.syncMessage(message, skipAttachments)
            synced++
            onProgress?.invoke(synced, total)
        }
    }

    suspend fun getOutgoingMessages(): Map<String, Map<String, Any?>> {
        return withContext(Dispatchers.IO) {
            try {
                val outgoing = vpsClient.getOutgoingMessages()
                outgoing.associate { it.id to toOutgoingPayload(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting outgoing messages", e)
                emptyMap()
            }
        }
    }

    fun listenForOutgoingMessages(pollIntervalMs: Long = 5000L): Flow<Map<String, Any?>> {
        return flow {
            while (true) {
                if (isAuthenticated()) {
                    try {
                        val outgoing = vpsClient.getOutgoingMessages()
                        outgoing.forEach { emit(toOutgoingPayload(it)) }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error polling outgoing messages", e)
                    }
                }
                delay(pollIntervalMs)
            }
        }
    }

    suspend fun writeSentMessage(messageId: String, address: String, body: String) {
        if (!isAuthenticated()) return
        val now = System.currentTimeMillis()
        val payload = mapOf(
            "id" to messageId,
            "address" to address,
            "body" to body,
            "date" to now,
            "type" to 2,
            "read" to true,
            "isMms" to false
        )
        vpsClient.syncMessages(listOf(payload))
    }

    suspend fun writeSentMmsMessage(
        messageId: String,
        address: String,
        body: String,
        attachments: List<Map<String, Any?>>? = null
    ) {
        if (!isAuthenticated()) return
        val now = System.currentTimeMillis()
        val payload = mapOf(
            "id" to messageId,
            "address" to address,
            "body" to body,
            "date" to now,
            "type" to 2,
            "read" to true,
            "isMms" to true,
            "attachments" to (attachments ?: emptyList<Map<String, Any?>>())
        )
        vpsClient.syncMessages(listOf(payload))
    }

    // ===================== Spam List Sync =====================

    suspend fun syncWhitelist(whitelist: Set<String>) {
        if (!isAuthenticated()) return
        try {
            for (phoneNumber in whitelist) {
                vpsClient.addToWhitelist(phoneNumber)
            }
            Log.d(TAG, "Synced ${whitelist.size} whitelist entries to VPS")
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing whitelist", e)
        }
    }

    suspend fun syncBlocklist(blocklist: Set<String>) {
        if (!isAuthenticated()) return
        try {
            for (phoneNumber in blocklist) {
                vpsClient.addToBlocklist(phoneNumber)
            }
            Log.d(TAG, "Synced ${blocklist.size} blocklist entries to VPS")
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing blocklist", e)
        }
    }

    fun listenForWhitelist(): Flow<Set<String>> = flow {
        while (true) {
            if (isAuthenticated()) {
                try {
                    val response = vpsClient.getWhitelist()
                    emit(response.whitelist.map { it.phoneNumber }.toSet())
                } catch (e: Exception) {
                    Log.e(TAG, "Error polling whitelist", e)
                }
            }
            delay(60000L) // Poll every 60 seconds
        }
    }

    fun listenForBlocklist(): Flow<Set<String>> = flow {
        while (true) {
            if (isAuthenticated()) {
                try {
                    val response = vpsClient.getBlocklist()
                    emit(response.blocklist.map { it.phoneNumber }.toSet())
                } catch (e: Exception) {
                    Log.e(TAG, "Error polling blocklist", e)
                }
            }
            delay(60000L) // Poll every 60 seconds
        }
    }

    suspend fun deleteOutgoingMessage(messageId: String) {
        if (!isAuthenticated()) return
        try {
            vpsClient.updateOutgoingStatus(messageId, "sent")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating outgoing status", e)
        }
    }

    // ===================== Reactions (unsupported) =====================

    suspend fun setMessageReaction(messageId: Long, reaction: String?) {
        Log.d(TAG, "setMessageReaction ignored in VPS mode (not implemented server-side)")
    }

    fun listenForMessageReactions(): Flow<Map<Long, String>> = emptyFlow()

    // ===================== Spam =====================

    suspend fun syncSpamMessage(message: SpamMessage) {
        if (!isAuthenticated()) return
        try {
            val payload = mapOf(
                "id" to "spam_${message.messageId}",
                "address" to message.address,
                "body" to message.body,
                "date" to message.date,
                "spamScore" to message.spamConfidence.toDouble(),
                "spamReason" to (message.spamReasons ?: "")
            )
            vpsClient.syncSpamMessage(payload)
            Log.d(TAG, "Synced spam message ${message.messageId} to VPS")
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing spam message", e)
        }
    }

    suspend fun syncSpamMessages(messages: List<SpamMessage>) {
        if (!isAuthenticated()) return
        for (message in messages) {
            syncSpamMessage(message)
        }
        Log.d(TAG, "Synced ${messages.size} spam messages to VPS")
    }

    suspend fun fetchSpamMessages(): List<SpamMessage> {
        if (!isAuthenticated()) return emptyList()
        return try {
            val response = vpsClient.getSpamMessages(100)
            response.messages.map { vpsSpam ->
                SpamMessage(
                    messageId = vpsSpam.id.removePrefix("spam_").toLongOrNull() ?: 0L,
                    address = vpsSpam.address,
                    body = vpsSpam.body ?: "",
                    date = vpsSpam.date,
                    spamConfidence = vpsSpam.spamScore ?: 0.5f,
                    spamReasons = vpsSpam.spamReason,
                    isUserMarked = false,
                    isRead = false
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching spam messages", e)
            emptyList()
        }
    }

    suspend fun deleteSpamMessage(messageId: Long) {
        if (!isAuthenticated()) return
        try {
            vpsClient.deleteSpamMessage("spam_$messageId")
            Log.d(TAG, "Deleted spam message $messageId from VPS")
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting spam message", e)
        }
    }

    suspend fun clearAllSpamMessages() {
        if (!isAuthenticated()) return
        try {
            vpsClient.clearAllSpamMessages()
            Log.d(TAG, "Cleared all spam messages from VPS")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing spam messages", e)
        }
    }

    fun listenForSpamMessages(): Flow<List<SpamMessage>> = flow {
        while (true) {
            if (isAuthenticated()) {
                try {
                    val messages = fetchSpamMessages()
                    emit(messages)
                } catch (e: Exception) {
                    Log.e(TAG, "Error polling spam messages", e)
                }
            }
            delay(30000L) // Poll every 30 seconds
        }
    }

    // ===================== Groups (unsupported) =====================

    suspend fun syncGroup(group: Group, members: List<GroupMember>) {
        Log.d(TAG, "syncGroup ignored in VPS mode (not implemented server-side)")
    }

    suspend fun syncGroups(groups: List<Group>) {
        Log.d(TAG, "syncGroups ignored in VPS mode (not implemented server-side)")
    }

    suspend fun deleteGroup(groupId: Long) {
        Log.d(TAG, "deleteGroup ignored in VPS mode (not implemented server-side)")
    }

    // ===================== Pairing / Devices =====================

    suspend fun getPairedDevices(): List<PairedDevice> = withContext(Dispatchers.IO) {
        try {
            val devices = vpsClient.getDevices().devices
            devices
                .filter { it.deviceType != "android" }
                .map {
                    PairedDevice(
                        id = it.id,
                        name = it.deviceName ?: "Unknown Device",
                        platform = it.deviceType,
                        lastSeen = 0L,
                        syncStatus = null
                    )
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting paired devices", e)
            emptyList()
        }
    }

    suspend fun getDeviceInfo(): DeviceInfoResult? = withContext(Dispatchers.IO) {
        try {
            val allDevices = vpsClient.getDevices().devices
            val desktopDevices = allDevices
                .filter { it.deviceType != "android" }
                .map {
                    PairedDevice(
                        id = it.id,
                        name = it.deviceName ?: "Unknown Device",
                        platform = it.deviceType,
                        lastSeen = 0L,
                        syncStatus = null
                    )
                }
            // Device limit applies to paired (non-Android) devices only
            // The Android phone is the host device and doesn't count toward the limit
            val pairedCount = desktopDevices.size
            val deviceLimit = 2
            val plan = "free"
            val canAdd = pairedCount < deviceLimit
            DeviceInfoResult(
                deviceCount = pairedCount,
                deviceLimit = deviceLimit,
                plan = plan,
                canAddDevice = canAdd,
                devices = desktopDevices
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting device info", e)
            null
        }
    }

    suspend fun completePairing(token: String, approved: Boolean): CompletePairingResult {
        return try {
            if (!approved) {
                CompletePairingResult.Rejected
            } else {
                vpsClient.completePairing(token)
                CompletePairingResult.Approved(null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error completing pairing", e)
            CompletePairingResult.Error(e.message ?: "Failed to complete pairing")
        }
    }

    /**
     * Push E2EE sync group keys to a newly paired device.
     *
     * This encrypts Android's sync group private key with the target device's
     * public key and sends it via VPS. The target device can then decrypt
     * and use the shared key to decrypt messages.
     */
    suspend fun pushE2EEKeysToDevice(targetDeviceId: String, targetPublicKeyX963: String) {
        try {
            Log.d(TAG, "pushE2EEKeysToDevice: targetDeviceId=$targetDeviceId, publicKeyLength=${targetPublicKeyX963.length}")

            val e2eeManager = SignalProtocolManager(appContext)

            // Get Android's sync group keys
            val privateKeyPKCS8 = e2eeManager.getSyncGroupPrivateKeyPKCS8()
            val publicKeyX963 = e2eeManager.getSyncGroupPublicKeyX963()

            if (privateKeyPKCS8 == null || publicKeyX963 == null) {
                Log.e(TAG, "E2EE keys not initialized (private=${privateKeyPKCS8 != null}, public=${publicKeyX963 != null})")
                throw IllegalStateException("E2EE sync group keys not initialized")
            }

            Log.d(TAG, "pushE2EEKeysToDevice: Got sync group keys (private=${privateKeyPKCS8.length} chars, public=${publicKeyX963.length} chars)")

            // Create key payload to encrypt
            val keyPayload = JSONObject().apply {
                put("privateKeyPKCS8", privateKeyPKCS8)
                put("publicKeyX963", publicKeyX963)
                put("version", 2)
                put("timestamp", System.currentTimeMillis())
            }.toString()

            // Encrypt the key payload with the target device's public key
            val encryptedPayload = e2eeManager.encryptDataKeyForDevice(
                targetPublicKeyX963,
                keyPayload.toByteArray(Charsets.UTF_8)
            )

            if (encryptedPayload == null) {
                Log.e(TAG, "encryptDataKeyForDevice returned null for target key length=${targetPublicKeyX963.length}")
                throw IllegalStateException("Failed to encrypt E2EE keys for device")
            }

            Log.d(TAG, "pushE2EEKeysToDevice: Encrypted payload length=${encryptedPayload.length}, posting to /api/e2ee/device-key/$targetDeviceId")

            // Store encrypted key on VPS for the target device
            val keyData = mapOf(
                "encryptedKey" to encryptedPayload,
                "keyType" to "sync_group_v2",
                "fromDevice" to (vpsClient.deviceId ?: "android"),
                "timestamp" to System.currentTimeMillis()
            )

            vpsClient.publishDeviceE2eeKey(targetDeviceId, keyData)
            Log.i(TAG, "Successfully pushed E2EE keys to device $targetDeviceId")

        } catch (e: Exception) {
            Log.e(TAG, "Error pushing E2EE keys to device: ${e.message}", e)
            throw e
        }
    }

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
}

// ==========================================
// REGION: Data Classes
// ==========================================

/**
 * Represents a sync history request from a Mac/Web client.
 */
data class SyncHistoryRequest(
    val id: String,
    val days: Int,
    val requestedAt: Long,
    val requestedBy: String
)

/**
 * User's sync settings.
 */
data class SyncSettings(
    val lastSyncDays: Int = 30,
    val lastFullSyncAt: Long? = null
)

/**
 * Represents a paired desktop or web device.
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
 */
data class SyncStatus(
    val status: String,
    val syncedMessages: Int = 0,
    val totalMessages: Int = 0,
    val lastSyncAttempt: Long = 0,
    val lastSyncCompleted: Long? = null,
    val errorMessage: String? = null
)

/**
 * Result of a pairing completion attempt.
 */
sealed class CompletePairingResult {
    data class Approved(val deviceId: String?) : CompletePairingResult()
    data object Rejected : CompletePairingResult()
    data class Error(val message: String) : CompletePairingResult()
}

/**
 * Device info summary.
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
