package com.phoneintegration.app.realtime

import android.content.Context
import android.util.Log
import com.phoneintegration.app.vps.VPSClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Typing status for a conversation
 */
data class TypingStatus(
    val conversationAddress: String,
    val isTyping: Boolean,
    val device: String, // "android", "macos", "web"
    val timestamp: Long
)

/**
 * Manager for real-time typing indicators across devices - VPS Backend Only
 *
 * In VPS mode, typing indicators are synced via REST API and WebSocket.
 * Real-time updates come through the WebSocket connection managed by VPSClient.
 */
class TypingIndicatorManager(private val context: Context) {

    companion object {
        private const val TAG = "TypingIndicatorManager"
        private const val TYPING_TIMEOUT_MS = 5000L // 5 seconds
        private const val DEBOUNCE_MS = 300L
    }

    private val vpsClient = VPSClient.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Debounce job for typing updates
    private var typingJob: Job? = null
    private var currentConversation: String? = null

    // Auto-clear job
    private var clearJob: Job? = null

    // Local cache of typing statuses
    private val _typingStatuses = MutableStateFlow<Map<String, TypingStatus>>(emptyMap())

    /**
     * Start typing indicator for a conversation
     */
    fun startTyping(conversationAddress: String) {
        typingJob?.cancel()

        typingJob = scope.launch {
            delay(DEBOUNCE_MS) // Debounce rapid typing

            try {
                val userId = vpsClient.userId ?: return@launch
                currentConversation = conversationAddress

                val typingData = mapOf(
                    "conversationAddress" to conversationAddress,
                    "isTyping" to true,
                    "device" to "android",
                    "timestamp" to System.currentTimeMillis()
                )

                vpsClient.updateTypingStatus(typingData)

                // Auto-clear after timeout
                scheduleClear(conversationAddress)

            } catch (e: Exception) {
                Log.e(TAG, "Error setting typing status", e)
            }
        }
    }

    /**
     * Stop typing indicator
     */
    fun stopTyping(conversationAddress: String? = null) {
        typingJob?.cancel()
        clearJob?.cancel()

        val address = conversationAddress ?: currentConversation ?: return

        scope.launch {
            try {
                val userId = vpsClient.userId ?: return@launch

                vpsClient.clearTypingStatus(address)
                currentConversation = null

            } catch (e: Exception) {
                Log.e(TAG, "Error clearing typing status", e)
            }
        }
    }

    /**
     * Schedule auto-clear of typing indicator
     */
    private fun scheduleClear(conversationAddress: String) {
        clearJob?.cancel()
        clearJob = scope.launch {
            delay(TYPING_TIMEOUT_MS)
            stopTyping(conversationAddress)
        }
    }

    /**
     * Observe typing status for a conversation (from other devices)
     */
    fun observeTypingStatus(conversationAddress: String): Flow<TypingStatus?> {
        // Return the status for this specific address
        return MutableStateFlow(_typingStatuses.value[conversationAddress]).asStateFlow()
    }

    /**
     * Observe all typing statuses
     */
    fun observeAllTypingStatuses(): Flow<Map<String, TypingStatus>> = _typingStatuses.asStateFlow()

    /**
     * BANDWIDTH OPTIMIZED: Observe all typing statuses using child events
     * In VPS mode, this uses the same StateFlow since WebSocket handles delta updates
     */
    fun observeAllTypingStatusesOptimized(): Flow<Map<String, TypingStatus>> = _typingStatuses.asStateFlow()

    /**
     * Update typing status from WebSocket event
     * Called by VPSClient when receiving typing updates
     */
    fun onTypingStatusReceived(status: TypingStatus) {
        // Don't show our own typing indicator
        if (status.device == "android") return

        // Skip stale entries
        if (System.currentTimeMillis() - status.timestamp > TYPING_TIMEOUT_MS * 2) return

        if (status.isTyping) {
            _typingStatuses.value = _typingStatuses.value + (status.conversationAddress to status)
        } else {
            _typingStatuses.value = _typingStatuses.value - status.conversationAddress
        }
    }

    /**
     * Clear a typing status from cache
     */
    fun onTypingStatusCleared(conversationAddress: String) {
        _typingStatuses.value = _typingStatuses.value - conversationAddress
    }

    /**
     * Clear all typing indicators (call on app close)
     */
    fun clearAllTyping() {
        scope.launch {
            try {
                val userId = vpsClient.userId ?: return@launch
                vpsClient.clearAllTypingStatus()
                _typingStatuses.value = emptyMap()
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing all typing", e)
            }
        }
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        typingJob?.cancel()
        clearJob?.cancel()
        clearAllTyping()
        scope.cancel()
    }
}
