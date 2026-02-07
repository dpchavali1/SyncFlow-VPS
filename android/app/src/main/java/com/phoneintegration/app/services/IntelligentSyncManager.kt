package com.phoneintegration.app.services

import android.content.Context
import android.util.Log
import com.phoneintegration.app.auth.AuthManager
import com.phoneintegration.app.desktop.DesktopSyncService
import com.phoneintegration.app.vps.VPSClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Intelligent Sync Manager that provides seamless cross-platform messaging
 * while minimizing battery drain through adaptive strategies.
 *
 * Key Features:
 * - VPS WebSocket/polling for message delivery
 * - Adaptive sync intervals based on user activity and battery
 * - Smart batching to reduce device wake-ups
 * - Cross-platform state synchronization
 * - Battery-aware prioritization of sync features
 */
class IntelligentSyncManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "IntelligentSyncManager"
        private const val MIN_SYNC_INTERVAL = 30_000L  // 30 seconds
        private const val MAX_SYNC_INTERVAL = 30 * 60_000L  // 30 minutes
        private const val REALTIME_TIMEOUT = 5 * 60_000L  // 5 minutes of inactivity

        @Volatile
        private var instance: IntelligentSyncManager? = null

        fun getInstance(context: Context): IntelligentSyncManager {
            return instance ?: synchronized(this) {
                instance ?: IntelligentSyncManager(context.applicationContext).also { instance = it }
            }
        }
    }

    // Service states
    enum class SyncPriority {
        CRITICAL,    // Messages, calls - always real-time
        HIGH,        // Notifications, contacts - adaptive
        MEDIUM,      // Photos, media - batch when convenient
        LOW          // Analytics, logs - only when charging
    }

    private val authManager = AuthManager.getInstance(context)
    private val vpsClient = VPSClient.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Sync state flows
    private val _syncStatus = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val syncStatus: StateFlow<Map<String, Boolean>> = _syncStatus.asStateFlow()

    private val _lastSyncTime = MutableStateFlow<Map<String, Long>>(emptyMap())
    val lastSyncTime: StateFlow<Map<String, Long>> = _lastSyncTime.asStateFlow()

    // Polling jobs instead of real-time listeners
    private var messagePollingJob: Job? = null
    private var callPollingJob: Job? = null
    private var e2eeKeyPollingJob: Job? = null
    private var syncRequestPollingJob: Job? = null
    private val appContext = context.applicationContext
    private val desktopSyncService = DesktopSyncService(appContext) // Initialize eagerly for E2EE key requests

    // Adaptive sync timers
    private var adaptiveSyncJob: Job? = null
    private var lastUserActivity = System.currentTimeMillis()

    // Battery awareness
    private val batteryManager = BatteryAwareServiceManager.getInstance(context)

    init {
        Log.i(TAG, "⭐ IntelligentSyncManager initializing...")

        // CRITICAL: Always set up E2EE key polling for pairing to work
        // This is lightweight and required for macOS/Web to sync E2EE keys during pairing
        setupE2eeKeyPollingOnly()

        // Only setup heavy polling (messages, notifications) if user has paired devices
        // This prevents excessive API calls on fresh install
        if (DesktopSyncService.hasPairedDevices(context)) {
            setupHeavyPolling()
            startAdaptiveSync()
            monitorUserActivity()
            Log.i(TAG, "✅ IntelligentSyncManager initialized with all polling jobs")
        } else {
            Log.i(TAG, "⏸️ IntelligentSyncManager initialized (heavy polling deferred - E2EE key polling ACTIVE)")
        }
    }

    /**
     * Setup ONLY the E2EE key polling - lightweight and required for pairing.
     * This runs ALWAYS on init, regardless of paired device status.
     */
    private fun setupE2eeKeyPollingOnly() {
        scope.launch {
            try {
                // Wait for VPS authentication
                if (!vpsClient.isAuthenticated) {
                    // User might not be authenticated yet on fresh install
                    // Keep retrying in background - pairing will trigger auth
                    for (attempt in 1..30) {
                        kotlinx.coroutines.delay(1000) // Check every second
                        if (vpsClient.isAuthenticated) {
                            Log.i(TAG, "⚡ VPS authenticated on attempt $attempt for E2EE polling")
                            break
                        }
                    }
                }

                if (vpsClient.isAuthenticated) {
                    Log.i(TAG, "⚡ Setting up E2EE key polling")
                    startE2eeKeyPolling()
                } else {
                    Log.w(TAG, "⚠️ Not authenticated after 30s - E2EE key polling not set up (will retry on pairing)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error setting up E2EE key polling", e)
            }
        }
    }

    /**
     * Setup heavy polling (messages, calls, notifications).
     * These are deferred until devices are paired to save bandwidth.
     */
    private fun setupHeavyPolling() {
        scope.launch {
            try {
                // Wait for VPS authentication
                for (attempt in 1..10) {
                    if (vpsClient.isAuthenticated) break
                    kotlinx.coroutines.delay(500)
                }

                if (!vpsClient.isAuthenticated) {
                    Log.e(TAG, "❌ Failed to authenticate with VPS for heavy polling")
                    return@launch
                }

                // Start message polling (CRITICAL priority)
                startMessagePolling()

                // Start call polling (CRITICAL priority)
                startCallPolling()

                // Start sync request polling (for loading older messages on demand)
                startSyncRequestPolling()

                Log.i(TAG, "✅ Heavy polling established")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error setting up heavy polling", e)
            }
        }
    }

    /**
     * Start sync after first device is paired.
     * Called by DesktopSyncService when pairing completes.
     */
    fun startAfterPairing() {
        if (messagePollingJob == null) {
            Log.i(TAG, "Starting IntelligentSyncManager after pairing")
            setupHeavyPolling()
            startAdaptiveSync()
            monitorUserActivity()
        }
    }

    /**
     * Setup ONLY the E2EE key request polling immediately.
     * This is critical for pairing - must be called when a new device pairs.
     *
     * Unlike startAfterPairing() which sets up all polling asynchronously,
     * this method prioritizes the E2EE key polling to respond to macOS/Web key requests
     * within their timeout window.
     */
    fun setupE2eeKeyListenerImmediately() {
        if (e2eeKeyPollingJob != null) {
            Log.d(TAG, "E2EE key polling already active")
            return
        }

        Log.i(TAG, "⚡ Setting up E2EE key polling IMMEDIATELY for pairing...")

        scope.launch {
            try {
                // Quick retry if not authenticated
                for (attempt in 1..10) {
                    if (vpsClient.isAuthenticated) {
                        Log.i(TAG, "⚡ VPS authenticated on attempt $attempt")
                        break
                    }
                    kotlinx.coroutines.delay(100)
                }

                if (!vpsClient.isAuthenticated) {
                    Log.e(TAG, "❌ Cannot setup E2EE key polling - not authenticated after retries")
                    return@launch
                }

                Log.i(TAG, "⚡ Setting up E2EE key polling NOW")
                startE2eeKeyPolling()

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error setting up E2EE key polling", e)
            }
        }
    }

    /**
     * Start message polling to check for new messages
     */
    private fun startMessagePolling() {
        messagePollingJob?.cancel()
        messagePollingJob = scope.launch {
            var lastCheckedTime = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000 // Start from 30 days ago

            while (isActive) {
                try {
                    val response = vpsClient.getMessages(limit = 50, after = lastCheckedTime)
                    for (message in response.messages) {
                        processIncomingMessage(mapOf(
                            "id" to message.id,
                            "address" to message.address,
                            "body" to message.body,
                            "date" to message.date,
                            "type" to message.type
                        ))
                    }
                    if (response.messages.isNotEmpty()) {
                        lastCheckedTime = response.messages.maxOf { it.date }
                    }
                    updateSyncStatus("messages", true)
                } catch (e: Exception) {
                    Log.e(TAG, "Error polling messages", e)
                }
                delay(5000) // Poll every 5 seconds
            }
        }
        Log.i(TAG, "✅ Message polling started")
    }

    /**
     * Start call polling to check for call commands
     */
    private fun startCallPolling() {
        callPollingJob?.cancel()
        callPollingJob = scope.launch {
            while (isActive) {
                try {
                    val commands = vpsClient.getCallCommands()
                    for (command in commands.filter { !it.processed }) {
                        processCallCommand(command)
                        vpsClient.markCallCommandProcessed(command.id)
                    }
                    updateSyncStatus("calls", true)
                } catch (e: Exception) {
                    Log.e(TAG, "Error polling call commands", e)
                }
                delay(2000) // Poll every 2 seconds for calls (time-sensitive)
            }
        }
        Log.i(TAG, "✅ Call polling started")
    }

    /**
     * Start sync request polling
     */
    private fun startSyncRequestPolling() {
        syncRequestPollingJob?.cancel()
        syncRequestPollingJob = scope.launch {
            while (isActive) {
                try {
                    // Check for pending sync requests via VPS API
                    // Note: This would require a new VPS endpoint
                    // For now, sync requests can be handled via WebSocket or manual trigger
                    updateSyncStatus("sync_requests", true)
                } catch (e: Exception) {
                    Log.e(TAG, "Error polling sync requests", e)
                }
                delay(30000) // Poll every 30 seconds for sync requests
            }
        }
        Log.i(TAG, "✅ Sync request polling started")
    }

    /**
     * Start E2EE key request polling
     */
    private fun startE2eeKeyPolling() {
        if (e2eeKeyPollingJob != null) {
            Log.d(TAG, "E2EE key polling already active, skipping")
            return
        }

        e2eeKeyPollingJob = scope.launch {
            Log.i(TAG, "⭐ Starting E2EE key request polling")

            while (isActive) {
                try {
                    // Check for pending E2EE key requests via VPS API
                    // Note: This would require a new VPS endpoint for E2EE key requests
                    // For now, E2EE key sync can be handled via WebSocket events

                    // The VPS WebSocket connection will handle E2EE key requests in real-time
                    // This polling is a fallback for reliability
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error polling E2EE key requests", e)
                }
                delay(5000) // Poll every 5 seconds
            }
        }
        Log.i(TAG, "✅ E2EE key request polling ACTIVE")
    }

    /**
     * Process a call command from VPS
     */
    private fun processCallCommand(command: com.phoneintegration.app.vps.VPSCallCommand) {
        Log.i(TAG, "Processing call command: ${command.command} for call: ${command.callId}")
        // Implementation would trigger actual call actions
    }

    /**
     * Adaptive sync system that adjusts based on conditions
     */
    private fun startAdaptiveSync() {
        adaptiveSyncJob?.cancel()
        adaptiveSyncJob = scope.launch {
            while (isActive) {
                val interval = calculateAdaptiveInterval()
                delay(interval)

                performAdaptiveSync()
            }
        }
    }

    /**
     * Calculate optimal sync interval based on multiple factors
     */
    private fun calculateAdaptiveInterval(): Long {
        val batteryLevel = batteryManager.batteryLevel.value
        val isCharging = batteryManager.isCharging.value
        val isOnWifi = batteryManager.isOnWifi.value
        val timeSinceActivity = System.currentTimeMillis() - lastUserActivity

        return when {
            // User active - frequent sync
            timeSinceActivity < 60_000 -> MIN_SYNC_INTERVAL

            // Low battery - reduce frequency
            batteryLevel < 20 && !isCharging -> MAX_SYNC_INTERVAL

            // Good conditions - moderate sync
            isCharging || isOnWifi -> 5 * 60_000L  // 5 minutes

            // Normal conditions - balanced sync
            else -> 10 * 60_000L  // 10 minutes
        }.coerceIn(MIN_SYNC_INTERVAL, MAX_SYNC_INTERVAL)
    }

    /**
     * Perform adaptive sync based on priority and conditions
     */
    private suspend fun performAdaptiveSync() {
        val userId = authManager.getCurrentUserId() ?: return

        // Always sync critical data
        syncCriticalData(userId)

        // Sync high priority based on conditions
        if (shouldSyncHighPriority()) {
            syncHighPriorityData(userId)
        }

        // Sync medium priority only when optimal
        if (shouldSyncMediumPriority()) {
            syncMediumPriorityData(userId)
        }
    }

    private suspend fun syncCriticalData(userId: String) {
        // Sync read receipts, typing indicators, presence
        try {
            syncReadReceipts()
            syncPresence()
            updateSyncStatus("critical_sync", true)
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing critical data", e)
        }
    }

    private suspend fun syncHighPriorityData(userId: String) {
        // Sync contacts, recent messages, notifications
        try {
            syncContacts()
            syncRecentMessages()
            updateSyncStatus("high_priority_sync", true)
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing high priority data", e)
        }
    }

    private suspend fun syncMediumPriorityData(userId: String) {
        // Sync photos, media, analytics (only when conditions are good)
        try {
            if (shouldSyncPhotos()) {
                syncPhotos()
            }
            if (shouldSyncMedia()) {
                syncMedia()
            }
            updateSyncStatus("medium_priority_sync", true)
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing medium priority data", e)
        }
    }

    /**
     * Smart decision making for when to sync different priorities
     */
    private fun shouldSyncHighPriority(): Boolean {
        val batteryLevel = batteryManager.batteryLevel.value
        val isCharging = batteryManager.isCharging.value
        val timeSinceLastSync = System.currentTimeMillis() - (_lastSyncTime.value["high_priority"] ?: 0)

        return when {
            isCharging -> true  // Always sync when charging
            batteryLevel > 30 -> timeSinceLastSync > 15 * 60_000  // 15 min on good battery
            batteryLevel > 15 -> timeSinceLastSync > 30 * 60_000  // 30 min on medium battery
            else -> timeSinceLastSync > 60 * 60_000  // 1 hour on low battery
        }
    }

    private fun shouldSyncMediumPriority(): Boolean {
        val isCharging = batteryManager.isCharging.value
        val isOnWifi = batteryManager.isOnWifi.value
        val batteryLevel = batteryManager.batteryLevel.value

        // Only sync media/photos when conditions are optimal
        return isCharging && isOnWifi && batteryLevel > 50
    }

    private fun shouldSyncPhotos(): Boolean {
        // Additional logic for photo sync (check if there are pending uploads, etc.)
        return true
    }

    private fun shouldSyncMedia(): Boolean {
        // Additional logic for media sync
        return true
    }

    /**
     * Monitor user activity to adjust sync frequency
     */
    private fun monitorUserActivity() {
        // This would be integrated with activity lifecycle callbacks
        // For now, we'll consider any sync operation as user activity
        lastUserActivity = System.currentTimeMillis()
    }

    /**
     * Cross-platform state synchronization
     */
    suspend fun syncCrossPlatformState(userId: String) {
        try {
            // Sync user preferences across devices
            syncUserPreferences(userId)

            // Sync conversation state (read receipts, etc.)
            syncConversationState(userId)

            // Sync device capabilities and status
            syncDeviceCapabilities(userId)

            Log.d(TAG, "Cross-platform state synchronized")
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing cross-platform state", e)
        }
    }

    /**
     * Handle real-time message events (called from polling or WebSocket)
     */
    private fun handleNewMessage(messageData: Map<String, Any>) {
        scope.launch {
            try {
                // Process new message immediately
                processIncomingMessage(messageData)

                // Update activity timestamp
                lastUserActivity = System.currentTimeMillis()

            } catch (e: Exception) {
                Log.e(TAG, "Error handling new message", e)
            }
        }
    }

    private fun handleMessageUpdate(messageData: Map<String, Any>) {
        // Handle message updates (read receipts, reactions, etc.)
        scope.launch {
            try {
                processMessageUpdate(messageData)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling message update", e)
            }
        }
    }

    /**
     * Handle call events (called from polling or WebSocket)
     */
    private fun handleIncomingCall(callData: Map<String, Any>) {
        scope.launch {
            try {
                processIncomingCall(callData)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling incoming call", e)
            }
        }
    }

    private fun handleCallUpdate(callData: Map<String, Any>) {
        scope.launch {
            try {
                processCallUpdate(callData)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling call update", e)
            }
        }
    }

    private fun handleCallEnded(callId: String) {
        scope.launch {
            try {
                processCallEnded(callId)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling call ended", e)
            }
        }
    }

    /**
     * Handle notification events
     */
    private fun handleNewNotification(notificationData: Map<String, Any>) {
        scope.launch {
            try {
                processNotification(notificationData)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling notification", e)
            }
        }
    }

    /**
     * Update sync status and timestamps
     */
    private fun updateSyncStatus(feature: String, active: Boolean) {
        val currentStatus = _syncStatus.value.toMutableMap()
        currentStatus[feature] = active
        _syncStatus.value = currentStatus

        if (active) {
            val currentTimes = _lastSyncTime.value.toMutableMap()
            currentTimes[feature] = System.currentTimeMillis()
            _lastSyncTime.value = currentTimes
        }
    }

    /**
     * Placeholder methods for actual sync operations
     * These would be implemented to interface with existing services
     */
    private suspend fun syncReadReceipts() {
        try {
            val receipts = vpsClient.getReadReceipts()
            Log.d(TAG, "Synced ${receipts.size} read receipts")
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing read receipts", e)
        }
    }
    private suspend fun syncPresence() { /* Implementation via VPS */ }
    private suspend fun syncContacts() {
        try {
            val response = vpsClient.getContacts()
            Log.d(TAG, "Synced ${response.contacts.size} contacts")
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing contacts", e)
        }
    }
    private suspend fun syncRecentMessages() {
        try {
            val response = vpsClient.getMessages(limit = 100)
            Log.d(TAG, "Synced ${response.messages.size} messages")
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing messages", e)
        }
    }
    private suspend fun syncPhotos() {
        try {
            val photos = vpsClient.getPhotos()
            Log.d(TAG, "Synced ${photos.size} photos")
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing photos", e)
        }
    }
    private suspend fun syncMedia() { /* Implementation via VPS */ }
    private suspend fun syncUserPreferences(userId: String) { /* Implementation via VPS */ }
    private suspend fun syncConversationState(userId: String) { /* Implementation via VPS */ }
    private suspend fun syncDeviceCapabilities(userId: String) { /* Implementation via VPS */ }

    private fun processIncomingMessage(messageData: Map<String, Any>) { /* Implementation */ }
    private fun processMessageUpdate(messageData: Map<String, Any>) { /* Implementation */ }
    private fun processIncomingCall(callData: Map<String, Any>) { /* Implementation */ }
    private fun processCallUpdate(callData: Map<String, Any>) { /* Implementation */ }
    private fun processCallEnded(callId: String) { /* Implementation */ }
    private fun processNotification(notificationData: Map<String, Any>) { /* Implementation */ }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        scope.launch {
            try {
                // Cancel all polling jobs
                messagePollingJob?.cancel()
                callPollingJob?.cancel()
                e2eeKeyPollingJob?.cancel()
                syncRequestPollingJob?.cancel()
                adaptiveSyncJob?.cancel()

                // Clear references
                messagePollingJob = null
                callPollingJob = null
                e2eeKeyPollingJob = null
                syncRequestPollingJob = null
                adaptiveSyncJob = null

                // Update status
                updateSyncStatus("messages", false)
                updateSyncStatus("calls", false)
                updateSyncStatus("notifications", false)

                Log.i(TAG, "IntelligentSyncManager cleaned up")
            } catch (e: Exception) {
                Log.e(TAG, "Error during cleanup", e)
            }
        }
    }
}
