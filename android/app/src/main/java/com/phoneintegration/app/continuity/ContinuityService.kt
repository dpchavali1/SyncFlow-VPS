package com.phoneintegration.app.continuity

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.phoneintegration.app.vps.VPSClient
import com.phoneintegration.app.vps.VPSContinuityState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Continuity Service - VPS Backend Only
 *
 * Syncs conversation state across devices using VPS API and WebSocket
 * instead of Firebase Realtime Database.
 */
class ContinuityService(context: Context) {

    companion object {
        private const val TAG = "ContinuityService"
        private const val POLL_INTERVAL_MS = 5000L
        private const val STATE_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes
    }

    data class ContinuityState(
        val deviceId: String,
        val deviceName: String,
        val platform: String,
        val type: String,
        val address: String,
        val contactName: String?,
        val threadId: Long?,
        val draft: String?,
        val timestamp: Long
    )

    private val appContext = context.applicationContext
    private val vpsClient = VPSClient.getInstance(appContext)

    private val deviceId = Settings.Secure.getString(
        appContext.contentResolver,
        Settings.Secure.ANDROID_ID
    ) ?: "android_unknown"
    private val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "Android" }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollingJob: Job? = null

    private val _continuityState = MutableStateFlow<ContinuityState?>(null)
    val continuityState: StateFlow<ContinuityState?> = _continuityState.asStateFlow()

    private var lastSeenDeviceId: String? = null
    private var lastSeenTimestamp: Long = 0L
    private var lastPublishAt: Long = 0L
    private var lastPayloadHash: Int = 0

    fun updateConversationState(
        address: String,
        contactName: String?,
        threadId: Long,
        draft: String?
    ) {
        if (vpsClient.userId == null) return
        val now = System.currentTimeMillis()
        val payloadHash = listOf(address, contactName ?: "", threadId.toString(), draft ?: "").hashCode()

        if (now - lastPublishAt < 800 && payloadHash == lastPayloadHash) {
            return
        }

        lastPublishAt = now
        lastPayloadHash = payloadHash

        val trimmedDraft = draft?.take(1000)

        scope.launch {
            try {
                vpsClient.updateContinuityState(
                    deviceId = deviceId,
                    deviceName = deviceName,
                    platform = "android",
                    type = "conversation",
                    address = address,
                    contactName = contactName ?: "",
                    threadId = threadId,
                    draft = trimmedDraft ?: ""
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update continuity state", e)
            }
        }
    }

    fun startListening(onUpdate: (ContinuityState?) -> Unit) {
        if (vpsClient.userId == null) return

        // Start polling for continuity state updates
        pollingJob = scope.launch {
            while (isActive) {
                try {
                    val states = vpsClient.getContinuityStates().map { it.toContinuityState() }
                    val latest = states
                        .filter { it.deviceId != deviceId }
                        .filter { it.timestamp > 0 }
                        .maxByOrNull { it.timestamp }

                    if (latest == null) {
                        if (_continuityState.value != null) {
                            _continuityState.value = null
                            onUpdate(null)
                        }
                    } else {
                        if (latest.deviceId == lastSeenDeviceId && latest.timestamp <= lastSeenTimestamp) {
                            // No change
                        } else if (System.currentTimeMillis() - latest.timestamp > STATE_TIMEOUT_MS) {
                            // State is stale
                        } else {
                            lastSeenDeviceId = latest.deviceId
                            lastSeenTimestamp = latest.timestamp
                            _continuityState.value = latest
                            onUpdate(latest)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error polling continuity state", e)
                }

                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stopListening() {
        pollingJob?.cancel()
        pollingJob = null
    }

    fun cleanup() {
        stopListening()
        scope.cancel()
    }

    private fun VPSContinuityState.toContinuityState(): ContinuityState {
        return ContinuityState(
            deviceId = deviceId,
            deviceName = deviceName,
            platform = platform,
            type = type,
            address = address,
            contactName = contactName,
            threadId = threadId,
            draft = draft,
            timestamp = timestamp
        )
    }
}
