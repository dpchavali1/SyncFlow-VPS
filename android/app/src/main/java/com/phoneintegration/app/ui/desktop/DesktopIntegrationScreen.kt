/**
 * DesktopIntegrationScreen.kt
 *
 * This file implements the desktop integration screen, allowing users to pair
 * their Android device with desktop (macOS) and web applications for cross-device
 * SMS synchronization.
 *
 * Key Features:
 * - QR code scanning for device pairing
 * - Paired device management (view, unpair)
 * - Sync settings configuration (background sync, notification mirroring)
 * - Recovery code management for account recovery
 * - Initial data sync (messages, contacts, call history)
 * - Device limit tracking based on user plan
 * - Photo sync capability
 *
 * Architecture:
 * - Uses DesktopSyncService for pairing and sync operations
 * - Uses UnifiedIdentityManager for authentication
 * - Global PairedDevicesCache for efficient device list management
 * - Supports both V1 and V2 QR code formats
 * - Firebase Realtime Database for cloud sync
 *
 * Navigation:
 * - Modal screen navigated from Settings
 * - No outbound navigation (self-contained)
 *
 * @see DesktopSyncService for sync implementation
 * @see UnifiedIdentityManager for authentication
 * @see PairedDevicesCache for device caching
 */
package com.phoneintegration.app.ui.desktop

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.phoneintegration.app.auth.UnifiedIdentityManager
import com.phoneintegration.app.desktop.DesktopSyncService
import com.phoneintegration.app.desktop.CompletePairingResult
import com.phoneintegration.app.desktop.PairedDevice
import com.phoneintegration.app.data.PreferencesManager
import com.phoneintegration.app.e2ee.SignalProtocolManager
import com.phoneintegration.app.desktop.NotificationMirrorService
import com.phoneintegration.app.desktop.PhotoSyncService
import com.phoneintegration.app.SmsRepository
import com.phoneintegration.app.CallMonitorService
import com.phoneintegration.app.vps.VPSClient
import com.phoneintegration.app.vps.VPSSyncService
import com.phoneintegration.app.ui.components.getRegisteredPhoneNumber
import com.phoneintegration.app.ui.components.PhoneNumberRegistrationDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.tasks.await
import androidx.compose.material3.Switch
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

// =============================================================================
// region DEVICE CACHE
// =============================================================================

/**
 * Global cache for paired devices that survives screen navigation.
 *
 * This singleton object maintains the paired device list across screen
 * recompositions and navigations, preventing unnecessary network requests.
 *
 * Cache Features:
 * - 30-second validity period
 * - Stores device count, limit, and plan info
 * - Thread-safe with Compose state
 * - Supports force refresh when needed
 */
private object PairedDevicesCache {
    private val _devices = mutableStateListOf<PairedDevice>()
    val devices: List<PairedDevice> get() = _devices.toList()

    var isLoading by mutableStateOf(false)
    var lastError by mutableStateOf<String?>(null)
    var deviceCount by mutableStateOf(0)
    var deviceLimit by mutableStateOf(2)
    var userPlan by mutableStateOf("free")
    var canAddDevice by mutableStateOf(true)

    private var lastLoadTime = 0L
    private const val CACHE_VALID_MS = 30_000L // 30 seconds

    fun isCacheValid(): Boolean {
        return _devices.isNotEmpty() && (System.currentTimeMillis() - lastLoadTime) < CACHE_VALID_MS
    }

    fun updateDevices(newDevices: List<PairedDevice>) {
        android.util.Log.d("PairedDevicesCache", "Updating cache with ${newDevices.size} devices")
        _devices.clear()
        _devices.addAll(newDevices)
        deviceCount = newDevices.size
        lastLoadTime = System.currentTimeMillis()
        lastError = null
    }

    fun setError(error: String) {
        lastError = error
    }

    fun clear() {
        _devices.clear()
        lastLoadTime = 0L
    }
}

// =============================================================================
// endregion
// =============================================================================

// =============================================================================
// region MAIN COMPOSABLE
// =============================================================================

/**
 * Main composable for the desktop integration/pairing screen.
 *
 * This screen allows users to:
 * - Scan QR codes to pair with desktop/web apps
 * - View and manage paired devices
 * - Configure sync settings (background sync, notification mirror)
 * - View/copy their recovery code
 * - Manually sync photos
 *
 * State Hoisting Pattern:
 * - onBack callback is hoisted for navigation
 * - Internal state managed locally with remember
 * - Shared state via PairedDevicesCache singleton
 *
 * Side Effects:
 * - LaunchedEffect for initial device loading
 * - DisposableEffect for lifecycle-aware settings refresh
 * - Coroutine launches for async operations (pairing, sync)
 *
 * @param onBack Callback to navigate back to previous screen
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopIntegrationScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()

    // Services
    val unifiedIdentityManager = remember { UnifiedIdentityManager.getInstance(appContext) }
    val desktopSyncService = remember { DesktopSyncService(appContext) }
    val preferencesManager = remember { PreferencesManager(appContext) }

    // Use the global cache for paired devices (survives navigation)
    val pairedDevices = PairedDevicesCache.devices
    val isLoadingDevices = PairedDevicesCache.isLoading
    val deviceCount = PairedDevicesCache.deviceCount
    val deviceLimit = PairedDevicesCache.deviceLimit
    val userPlan = PairedDevicesCache.userPlan
    val canAddDevice = PairedDevicesCache.canAddDevice

    // Local UI state
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }
    var showSuccessDialog by remember { mutableStateOf(false) }
    var showManualInput by remember { mutableStateOf(false) }
    var manualCode by remember { mutableStateOf("") }
    var isScanButtonBusy by remember { mutableStateOf(false) }

    // Recovery code state - check before first pairing
    val recoveryManager = remember { com.phoneintegration.app.auth.RecoveryCodeManager.getInstance(appContext) }
    var showRecoverySetupDialog by remember { mutableStateOf(false) }
    var generatedRecoveryCode by remember { mutableStateOf<String?>(null) }
    var isGeneratingCode by remember { mutableStateOf(false) }
    var pendingPairingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    // Recovery code entry states
    var showRecoveryCodeEntry by remember { mutableStateOf(false) }
    var enteredRecoveryCode by remember { mutableStateOf("") }
    var isRecoveringAccount by remember { mutableStateOf(false) }
    var recoveryError by remember { mutableStateOf<String?>(null) }
    var recoverySuccess by remember { mutableStateOf(false) }
    var recoveredUserId by remember { mutableStateOf<String?>(null) }

    // Function to check if recovery is needed before pairing
    fun checkRecoveryBeforePairing(onProceed: () -> Unit) {
        // If user already has a recovery code or has paired devices, proceed directly
        if (recoveryManager.isSetupComplete() || pairedDevices.isNotEmpty()) {
            onProceed()
        } else {
            // First time pairing - show recovery code setup
            pendingPairingAction = onProceed
            showRecoverySetupDialog = true
        }
    }

    // Sync settings state
    var isBackgroundSyncEnabled by remember { mutableStateOf(preferencesManager.backgroundSyncEnabled.value) }
    var hasNotificationPermission by remember { mutableStateOf(NotificationMirrorService.isEnabled(appContext)) }
    var isNotificationMirrorEnabled by remember { mutableStateOf(preferencesManager.notificationMirrorEnabled.value) }

    fun refreshSettings() {
        isBackgroundSyncEnabled = preferencesManager.backgroundSyncEnabled.value
        hasNotificationPermission = NotificationMirrorService.isEnabled(appContext)
        isNotificationMirrorEnabled = preferencesManager.notificationMirrorEnabled.value
        android.util.Log.d("DesktopIntegrationScreen", "Settings refreshed: notificationPermission=$hasNotificationPermission, mirrorEnabled=$isNotificationMirrorEnabled")
    }

    // Refresh settings when screen resumes (e.g., after returning from system settings)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                android.util.Log.d("DesktopIntegrationScreen", "Screen resumed, refreshing settings")
                refreshSettings()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Simple, reliable device loading function
    fun loadDevices(forceRefresh: Boolean = false) {
        // Use cached data if valid and not forcing refresh
        if (!forceRefresh && PairedDevicesCache.isCacheValid()) {
            android.util.Log.d("DesktopIntegrationScreen", "Using cached devices: ${PairedDevicesCache.devices.size}")
            return
        }

        // If forceRefresh, wait for current load to finish then load again
        if (PairedDevicesCache.isLoading) {
            if (forceRefresh) {
                android.util.Log.d("DesktopIntegrationScreen", "Force refresh requested, will retry after delay")
                // Schedule a retry after a short delay
                scope.launch(Dispatchers.Main) {
                    kotlinx.coroutines.delay(2000) // Wait 2 seconds for current load to finish
                    if (!PairedDevicesCache.isLoading) {
                        loadDevices(forceRefresh = true)
                    }
                }
            } else {
                android.util.Log.d("DesktopIntegrationScreen", "Already loading, skipping")
            }
            return
        }

        android.util.Log.d("DesktopIntegrationScreen", "Loading devices (forceRefresh=$forceRefresh)")
        PairedDevicesCache.isLoading = true

        // Use GlobalScope to ensure this completes even if screen is disposed
        scope.launch(Dispatchers.IO) {
            try {
                // Load device info (includes total device count and desktop devices list)
                val deviceInfo = desktopSyncService.getDeviceInfo()
                if (deviceInfo != null) {
                    android.util.Log.d("DesktopIntegrationScreen", "Device info: count=${deviceInfo.deviceCount}, limit=${deviceInfo.deviceLimit}, canAdd=${deviceInfo.canAddDevice}")

                    // Get the desktop-only devices from deviceInfo (for display)
                    val desktopDevices = deviceInfo.devices
                    android.util.Log.d("DesktopIntegrationScreen", "Desktop devices: ${desktopDevices.size}")
                    desktopDevices.forEach { d ->
                        android.util.Log.d("DesktopIntegrationScreen", "  Device: ${d.name} (${d.platform})")
                    }

                    // Update cache with Cloud Function values
                    // Use deviceCount from Cloud Function (total count including Android)
                    // This ensures the count matches what the limit check uses
                    withContext(Dispatchers.Main) {
                        PairedDevicesCache.updateDevices(desktopDevices)
                        // Override deviceCount with the total from Cloud Function
                        PairedDevicesCache.deviceCount = deviceInfo.deviceCount
                        PairedDevicesCache.deviceLimit = deviceInfo.deviceLimit
                        PairedDevicesCache.userPlan = deviceInfo.plan
                        PairedDevicesCache.canAddDevice = deviceInfo.canAddDevice
                        android.util.Log.d("DesktopIntegrationScreen", "Cache updated: ${desktopDevices.size} desktop devices, total count=${deviceInfo.deviceCount}/${deviceInfo.deviceLimit}")

                        // Update fast-check cache for sync operations
                        DesktopSyncService.updatePairedDevicesCache(appContext, desktopDevices.isNotEmpty(), desktopDevices.size)
                    }
                } else {
                    android.util.Log.w("DesktopIntegrationScreen", "getDeviceInfo returned null, falling back to getPairedDevices")
                    // Fallback to direct device fetch
                    val devices = desktopSyncService.getPairedDevices()
                    withContext(Dispatchers.Main) {
                        PairedDevicesCache.updateDevices(devices)
                        // Reset count/limit to sane defaults since getDeviceInfo failed
                        PairedDevicesCache.deviceCount = devices.size
                        PairedDevicesCache.deviceLimit = 2
                        PairedDevicesCache.canAddDevice = devices.size < 2
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("DesktopIntegrationScreen", "Error loading devices", e)
                withContext(Dispatchers.Main) {
                    PairedDevicesCache.setError(e.message ?: "Failed to load devices")
                    // Reset to allow pairing on error - don't block user
                    PairedDevicesCache.deviceCount = 0
                    PairedDevicesCache.deviceLimit = 2
                    PairedDevicesCache.canAddDevice = true
                }
            } finally {
                withContext(Dispatchers.Main) {
                    PairedDevicesCache.isLoading = false
                }
            }
        }
    }

    // Sync messages after successful pairing
    // First pairing: Sync last 30 days
    // Subsequent pairings: Only sync new messages since last sync
    suspend fun syncInitialMessages() {
        try {
            val prefs = appContext.getSharedPreferences("vps_sync_prefs", Context.MODE_PRIVATE)
            val lastSyncTime = prefs.getLong("last_message_sync_time", 0L)
            val smsRepository = SmsRepository(appContext)

            val messages = if (lastSyncTime == 0L) {
                // First sync: Get last 30 days
                android.util.Log.d("DesktopIntegrationScreen", "First sync - syncing last 30 days of messages")
                smsRepository.getMessagesFromLastDays(days = 30)
            } else {
                // Subsequent sync: Get messages since last sync
                android.util.Log.d("DesktopIntegrationScreen", "Incremental sync - syncing messages since $lastSyncTime")
                smsRepository.getMessagesSince(lastSyncTime)
            }

            android.util.Log.d("DesktopIntegrationScreen", "Retrieved ${messages.size} messages for sync")

            if (messages.isNotEmpty()) {
                desktopSyncService.syncMessages(messages)
                android.util.Log.d("DesktopIntegrationScreen", "Message sync completed: ${messages.size} messages")
            }

            // Update last sync time
            prefs.edit().putLong("last_message_sync_time", System.currentTimeMillis()).apply()
            android.util.Log.d("DesktopIntegrationScreen", "Updated last sync time")
        } catch (e: Exception) {
            android.util.Log.e("DesktopIntegrationScreen", "Error during message sync", e)
        }
    }

    // Sync contacts after successful pairing
    suspend fun syncInitialContacts() {
        withContext(Dispatchers.IO) {
            try {
                android.util.Log.d("DesktopIntegrationScreen", "Starting initial contact sync")

                val vpsClient = com.phoneintegration.app.vps.VPSClient.getInstance(appContext)
                val userId = vpsClient.userId
                if (userId == null) {
                    android.util.Log.e("DesktopIntegrationScreen", "Cannot sync contacts - no authenticated user")
                    return@withContext
                }
                android.util.Log.d("DesktopIntegrationScreen", "Syncing contacts for userId: $userId")

                val contactsSyncService = com.phoneintegration.app.desktop.ContactsSyncService(appContext)
                contactsSyncService.syncContactsForUser(userId)
                android.util.Log.d("DesktopIntegrationScreen", "Initial contact sync completed")
            } catch (e: Exception) {
                android.util.Log.e("DesktopIntegrationScreen", "Error during initial contact sync", e)
            }
        }
    }

    // Sync call history after successful pairing
    suspend fun syncInitialCallHistory() {
        withContext(Dispatchers.IO) {
            try {
                android.util.Log.d("DesktopIntegrationScreen", "Starting initial call history sync")

                val vpsClient = com.phoneintegration.app.vps.VPSClient.getInstance(appContext)
                val userId = vpsClient.userId
                if (userId == null) {
                    android.util.Log.e("DesktopIntegrationScreen", "Cannot sync - no authenticated user")
                    return@withContext
                }
                android.util.Log.d("DesktopIntegrationScreen", "Syncing call history for userId: $userId")

                val callHistorySyncService = com.phoneintegration.app.desktop.CallHistorySyncService(appContext)
                callHistorySyncService.syncCallHistoryForUser(userId)
                android.util.Log.d("DesktopIntegrationScreen", "Initial call history sync completed")
            } catch (e: Exception) {
                android.util.Log.e("DesktopIntegrationScreen", "Error during initial call history sync", e)
            }
        }
    }

    // Load devices when screen appears + validate device is still registered
    LaunchedEffect(Unit) {
        android.util.Log.d("DesktopIntegrationScreen", "Screen appeared, loading devices...")
        refreshSettings()
        loadDevices(forceRefresh = false)

        // Check if this device is still registered on the server
        val vpsSyncService = VPSSyncService.getInstance(appContext)
        val stillRegistered = vpsSyncService.validateDeviceRegistration()
        if (!stillRegistered) {
            android.util.Log.w("DesktopIntegrationScreen", "Device no longer registered, clearing state")
            PairedDevicesCache.clear()
        }
    }

    // Observe remote unpairing events
    LaunchedEffect(Unit) {
        val vpsSyncService = VPSSyncService.getInstance(appContext)
        vpsSyncService.deviceUnpaired.collect {
            android.util.Log.w("DesktopIntegrationScreen", "Remote unpairing detected, clearing devices")
            PairedDevicesCache.clear()
        }
    }

    // Observe device_added events from WebSocket → refresh device list instantly
    LaunchedEffect(Unit) {
        val vpsSyncService = VPSSyncService.getInstance(appContext)
        vpsSyncService.deviceAdded.collect { addedDeviceId ->
            android.util.Log.d("DesktopIntegrationScreen", "Device added via WebSocket: $addedDeviceId, refreshing list")
            loadDevices(forceRefresh = true)
        }
    }

    // Observe other device removed events from WebSocket → refresh device list instantly
    LaunchedEffect(Unit) {
        val vpsSyncService = VPSSyncService.getInstance(appContext)
        vpsSyncService.otherDeviceRemoved.collect { removedDeviceId ->
            android.util.Log.d("DesktopIntegrationScreen", "Device removed via WebSocket: $removedDeviceId, refreshing list")
            loadDevices(forceRefresh = true)
        }
    }

    // Log when device list changes for debugging
    LaunchedEffect(pairedDevices.size) {
        android.util.Log.d("DesktopIntegrationScreen", "Device list updated: ${pairedDevices.size} devices")
    }

    // QR Scanner launcher with optimized settings for faster/better detection
    val scanLauncher = rememberLauncherForActivityResult(
        contract = ScanContract(),
        onResult = { result ->
            // Null result from cancelled scan
            if (result == null) {
                android.util.Log.w("DesktopIntegrationScreen", "QR scan cancelled by user")
                isScanButtonBusy = false
                return@rememberLauncherForActivityResult
            }
            if (result.contents == null) {
                android.util.Log.w("DesktopIntegrationScreen", "QR scan returned null content - likely no QR detected")
                isScanButtonBusy = false
                // Provide better guidance to user
                errorMessage = "No QR code detected. Make sure:\n1. The QR code is visible on your Mac screen\n2. You waited 2+ seconds for focus\n3. The QR code is fully in frame\n\nTry again."
                return@rememberLauncherForActivityResult
            }
            android.util.Log.d("DesktopIntegrationScreen", "QR code scanned, starting processing...")
            scope.launch {
                try {
                    isLoading = true
                    errorMessage = null

                    // Parse QR code and handle pairing
                    val qrContent = result.contents.trim()

                    // Check if this is a sync group QR code (format: sync_xxx, web_xxx, macos_xxx)
                    if (qrContent.startsWith("sync_") || qrContent.startsWith("web_") || qrContent.startsWith("macos_")) {
                        try {
                            val result = unifiedIdentityManager.joinSyncGroupFromQRCode(qrContent, "Android")
                            android.util.Log.d("DesktopIntegrationScreen", "Join sync group result: success=${result.isSuccess}")
                            if (result.isSuccess) {
                                val joinResult = result.getOrNull()
                                android.util.Log.d("DesktopIntegrationScreen", "Join result details: success=${joinResult?.success}, message='${joinResult?.message}'")
                                if (joinResult?.success == true) {
                                    // Immediately mark as having devices so sync UI appears
                                    withContext(Dispatchers.Main) {
                                        if (PairedDevicesCache.devices.isEmpty()) {
                                            PairedDevicesCache.updateDevices(listOf(
                                                PairedDevice(
                                                    id = "sync-group",
                                                    name = "Synced Device",
                                                    platform = "unknown",
                                                    lastSeen = System.currentTimeMillis()
                                                )
                                            ))
                                        }
                                    }
                                    successMessage = "Successfully joined sync group! (${joinResult.deviceCount}/${joinResult.deviceLimit} devices)"
                                    showSuccessDialog = true
                                    // Trigger initial data sync for newly joined group
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            // Ensure full sync on new pairing
                                            appContext.getSharedPreferences("vps_sync_prefs", Context.MODE_PRIVATE)
                                                .edit()
                                                .remove("last_message_sync_time")
                                                .apply()
                                            android.util.Log.d("DesktopIntegrationScreen", "Starting initial data sync after sync group join")
                                            syncInitialMessages()
                                            syncInitialContacts()
                                            syncInitialCallHistory()
                                        } catch (e: Exception) {
                                            android.util.Log.e("DesktopIntegrationScreen", "Error syncing data after sync group join", e)
                                        }
                                    }
                                    // Refresh device list immediately (server already registered the device)
                                    loadDevices(forceRefresh = true)
                                } else {
                                    errorMessage = joinResult?.message ?: "Failed to join sync group"
                                    android.util.Log.e("DesktopIntegrationScreen", "Join failed: ${errorMessage}")
                                }
                            } else {
                                val errorMsg = result.exceptionOrNull()?.message ?: "Failed to join sync group"
                                errorMessage = errorMsg
                                android.util.Log.e("DesktopIntegrationScreen", "Join sync group failed: $errorMsg")
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("DesktopIntegrationScreen", "Sync group join exception", e)
                            errorMessage = e.message ?: "Failed to join sync group"
                        }
                    } else {
                        // Try new unified pairing format first (token-based)
                        val tokenData = parsePairingQrCode(qrContent)
                        android.util.Log.d("DesktopIntegrationScreen", "Parsed token data: $tokenData")

                        if (tokenData != null) {
                            android.util.Log.d("DesktopIntegrationScreen", "Using new pending pairing format, version=${tokenData.version}, isVPS=${tokenData.isVPS}")

                            // VPS Pairing - use VPSClient instead of Firebase
                            if (tokenData.isVPS) {
                                android.util.Log.d("DesktopIntegrationScreen", "Processing VPS pairing request")
                                try {
                                    val vpsClient = VPSClient.getInstance(context)

                                    // CRITICAL: Authenticate with VPS before pairing
                                    // On fresh install, Android has no VPS token - must auth first
                                    if (!vpsClient.isAuthenticated) {
                                        android.util.Log.d("DesktopIntegrationScreen", "Not authenticated with VPS, authenticating first...")
                                        val userId = try {
                                            withTimeout(10000) {
                                                unifiedIdentityManager.getUnifiedUserId()
                                            }
                                        } catch (e: Exception) {
                                            android.util.Log.e("DesktopIntegrationScreen", "VPS authentication failed: ${e.message}", e)
                                            null
                                        }
                                        if (userId == null || !vpsClient.isAuthenticated) {
                                            errorMessage = "Failed to authenticate with VPS server. Please try again."
                                            isLoading = false
                                            isScanButtonBusy = false
                                            return@launch
                                        }
                                        android.util.Log.d("DesktopIntegrationScreen", "VPS authenticated as: $userId")
                                    }

                                    // Complete the VPS pairing (approve the request)
                                    android.util.Log.d("DesktopIntegrationScreen", "Completing VPS pairing with token: ${tokenData.token.take(8)}...")
                                    vpsClient.completePairing(tokenData.token)
                                    android.util.Log.d("DesktopIntegrationScreen", "VPS pairing completed successfully")

                                    // Immediately add device to cache so sync UI appears without re-navigation
                                    withContext(Dispatchers.Main) {
                                        val newDevice = PairedDevice(
                                            id = tokenData.deviceId ?: "pending",
                                            name = tokenData.name,
                                            platform = tokenData.platform,
                                            lastSeen = System.currentTimeMillis()
                                        )
                                        PairedDevicesCache.updateDevices(
                                            PairedDevicesCache.devices + newDevice
                                        )
                                    }

                                    // CRITICAL: Push E2EE keys IMMEDIATELY after pairing
                                    // This ensures macOS can decrypt messages synced below
                                    val vpsDeviceId = tokenData.deviceId
                                    if (tokenData.hasE2EEPublicKey && vpsDeviceId != null) {
                                        android.util.Log.d("DesktopIntegrationScreen", "Pushing E2EE keys to VPS device: $vpsDeviceId")
                                        // Do not block UI; retry in background until the key is visible on VPS.
                                        scope.launch(Dispatchers.IO) {
                                            try {
                                                // Ensure E2EE keys are initialized (may not be on fresh install)
                                                val signalProtocolManager = SignalProtocolManager(context)
                                                signalProtocolManager.initializeKeys()
                                                android.util.Log.d("DesktopIntegrationScreen", "E2EE keys initialized for VPS pairing")

                                                val targetPublicKey = tokenData.macPublicKeyX963!!
                                                val vpsClientRetry = VPSClient.getInstance(context)
                                                val userIdRetry = vpsClientRetry.userId

                                                android.util.Log.d("DesktopIntegrationScreen", "E2EE push context: userId=$userIdRetry, targetDeviceId=$vpsDeviceId, targetKeyLength=${targetPublicKey.length}, isAuthenticated=${vpsClientRetry.isAuthenticated}")

                                                val maxAttempts = 10
                                                val delayMs = 2000L

                                                repeat(maxAttempts) { attempt ->
                                                    // Push the key first
                                                    try {
                                                        desktopSyncService.pushE2EEKeysToDevice(
                                                            targetDeviceId = vpsDeviceId,
                                                            targetPublicKeyX963 = targetPublicKey
                                                        )
                                                        android.util.Log.d("DesktopIntegrationScreen", "E2EE keys pushed successfully (attempt ${attempt + 1})")
                                                    } catch (e: Exception) {
                                                        android.util.Log.e("DesktopIntegrationScreen", "Failed to push E2EE keys (attempt ${attempt + 1}): ${e.message}", e)
                                                    }

                                                    // Verify the key was stored by reading it back
                                                    try {
                                                        if (userIdRetry != null) {
                                                            val existing = vpsClientRetry.getDeviceE2eeKeys(userIdRetry)
                                                            android.util.Log.d("DesktopIntegrationScreen", "E2EE verify (attempt ${attempt + 1}): found ${existing.size} keys for userId=$userIdRetry, deviceIds=${existing.keys}")
                                                            val encrypted = existing[vpsDeviceId]?.get("encryptedKey") as? String
                                                            if (!encrypted.isNullOrBlank()) {
                                                                android.util.Log.d("DesktopIntegrationScreen", "✅ E2EE key VERIFIED on server for device $vpsDeviceId (keyLength=${encrypted.length})")
                                                                return@launch
                                                            } else {
                                                                android.util.Log.w("DesktopIntegrationScreen", "⚠️ E2EE key NOT found for deviceId=$vpsDeviceId in keys: ${existing.keys}")
                                                            }
                                                        } else {
                                                            android.util.Log.e("DesktopIntegrationScreen", "⚠️ userId is null, cannot verify E2EE key")
                                                        }
                                                    } catch (e: Exception) {
                                                        android.util.Log.w("DesktopIntegrationScreen", "Failed to verify E2EE key (attempt ${attempt + 1})", e)
                                                    }

                                                    kotlinx.coroutines.delay(delayMs)
                                                }
                                                android.util.Log.e("DesktopIntegrationScreen", "❌ E2EE key push failed after $maxAttempts attempts")
                                            } catch (e: Exception) {
                                                android.util.Log.e("DesktopIntegrationScreen", "Failed to initialize/push E2EE keys to VPS device: ${e.message}", e)
                                            }
                                        }
                                    } else {
                                        android.util.Log.w("DesktopIntegrationScreen", "No E2EE public key in VPS QR (hasKey=${tokenData.hasE2EEPublicKey}, deviceId=$vpsDeviceId)")
                                    }

                                    successMessage = "Successfully paired with ${tokenData.name}!"
                                    showSuccessDialog = true

                                    // Start CallMonitorService for phone call sync
                                    android.util.Log.d("DesktopIntegrationScreen", "Starting CallMonitorService for VPS phone call sync")
                                    CallMonitorService.start(context)

                                    // CRITICAL: Start IntelligentSyncManager's heavy polling
                                    // Without this, ongoing message/call sync never starts after pairing
                                    try {
                                        com.phoneintegration.app.services.IntelligentSyncManager
                                            .getInstance(context).startAfterPairing()
                                        android.util.Log.d("DesktopIntegrationScreen", "IntelligentSyncManager started after VPS pairing")
                                    } catch (e: Exception) {
                                        android.util.Log.e("DesktopIntegrationScreen", "Failed to start IntelligentSyncManager", e)
                                    }

                                    // Trigger initial VPS data sync
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            android.util.Log.d("DesktopIntegrationScreen", "Starting initial VPS data sync after pairing")
                                            val vpsSyncService = VPSSyncService.getInstance(context)
                                            val smsRepository = SmsRepository(context)

                                            // Sync messages (last 30 days)
                                            val messages = smsRepository.getMessagesFromLastDays(days = 30)
                                            android.util.Log.d("DesktopIntegrationScreen", "Syncing ${messages.size} messages to VPS")
                                            vpsSyncService.syncMessages(messages)

                                            // Contacts and call history sync handled by VPSSyncService
                                            android.util.Log.d("DesktopIntegrationScreen", "Initial VPS data sync completed")
                                        } catch (e: Exception) {
                                            android.util.Log.e("DesktopIntegrationScreen", "Error syncing VPS data", e)
                                        }
                                    }

                                    // Refresh device list immediately (server already registered the device)
                                    loadDevices(forceRefresh = true)
                                } catch (e: Exception) {
                                    android.util.Log.e("DesktopIntegrationScreen", "VPS pairing failed", e)
                                    errorMessage = e.message ?: "Failed to complete VPS pairing"
                                }

                                isLoading = false
                                isScanButtonBusy = false
                                return@launch
                            }

                            // Firebase Pairing - existing flow
                            // CRITICAL: Ensure Android is authenticated before pairing
                            // This ensures the Cloud Function receives the correct user ID
                            android.util.Log.d("DesktopIntegrationScreen", "Getting unified user ID...")
                            var userId = try {
                                withTimeout(10000) { // 10 second timeout
                                    unifiedIdentityManager.getUnifiedUserId()
                                }
                            } catch (e: TimeoutCancellationException) {
                                android.util.Log.e("DesktopIntegrationScreen", "Authentication timeout after 10 seconds")
                                errorMessage = "Authentication timeout. Please check your internet connection."
                                isLoading = false
                                isScanButtonBusy = false
                                return@launch
                            } catch (e: Exception) {
                                android.util.Log.e("DesktopIntegrationScreen", "Authentication error: ${e.message}", e)
                                errorMessage = "Authentication failed: ${e.message}"
                                isLoading = false
                                isScanButtonBusy = false
                                return@launch
                            }

                            if (userId == null) {
                                errorMessage = "Authentication failed. Please try again."
                                android.util.Log.e("DesktopIntegrationScreen", "Failed to authenticate before pairing - userId is null")
                                isLoading = false
                                isScanButtonBusy = false
                                return@launch
                            }
                            android.util.Log.d("DesktopIntegrationScreen", "Authenticated as user: $userId")

                            // CRITICAL: If authenticated as a device user, re-auth via fingerprint
                            // Do NOT use authenticateAnonymous() - it creates orphan users
                            if (userId?.startsWith("device_") == true) {
                                android.util.Log.d("DesktopIntegrationScreen", "WARNING: Android authenticated as device user, re-authenticating via fingerprint")
                                try {
                                    val vpsClient = com.phoneintegration.app.vps.VPSClient.getInstance(appContext)
                                    vpsClient.logout()
                                    // Re-authenticate via unified fingerprint flow (no orphan creation)
                                    val unifiedId = com.phoneintegration.app.auth.UnifiedIdentityManager.getInstance(appContext).getUnifiedUserId()
                                    if (unifiedId != null) {
                                        userId = unifiedId
                                        android.util.Log.d("DesktopIntegrationScreen", "Re-authenticated via fingerprint: $unifiedId")
                                    } else {
                                        android.util.Log.e("DesktopIntegrationScreen", "Fingerprint re-auth failed")
                                        errorMessage = "Authentication failed. Please try again."
                                        isLoading = false
                                        isScanButtonBusy = false
                                        return@launch
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.w("DesktopIntegrationScreen", "Failed to re-auth via fingerprint", e)
                                }
                            }

                            android.util.Log.d("DesktopIntegrationScreen", "Calling completePairing with token: ${tokenData.token.take(8)}...")
                            android.util.Log.d("DesktopIntegrationScreen", "QR Data - hasE2EEPublicKey: ${tokenData.hasE2EEPublicKey}, deviceId: ${tokenData.deviceId}, version: ${tokenData.version}")
                            android.util.Log.d("DesktopIntegrationScreen", "QR Data - macPublicKeyX963 length: ${tokenData.macPublicKeyX963?.length ?: 0}")
                            try {
                                // First, complete the pairing (this grants permission to write to user's data)
                                val pairingResult = desktopSyncService.completePairing(tokenData.token, true)
                                android.util.Log.d("DesktopIntegrationScreen", "completePairing result: $pairingResult")
                                when (pairingResult) {
                                    is CompletePairingResult.Approved -> {
                                        android.util.Log.d("DesktopIntegrationScreen", "Pairing approved! Device ID: ${pairingResult.deviceId}")

                                        // Immediately add device to cache so sync UI appears without re-navigation
                                        withContext(Dispatchers.Main) {
                                            val newDevice = PairedDevice(
                                                id = pairingResult.deviceId ?: tokenData.deviceId ?: "pending",
                                                name = tokenData.name,
                                                platform = tokenData.platform,
                                                lastSeen = System.currentTimeMillis()
                                            )
                                            PairedDevicesCache.updateDevices(
                                                PairedDevicesCache.devices + newDevice
                                            )
                                        }

                                        // Ensure E2EE keys are initialized before pushing to macOS/web and syncing messages.
                                        try {
                                            val signalProtocolManager = SignalProtocolManager(appContext)
                                            signalProtocolManager.initializeKeys()
                                            android.util.Log.d("DesktopIntegrationScreen", "E2EE keys initialized before VPS sync")
                                        } catch (e: Exception) {
                                            android.util.Log.e("DesktopIntegrationScreen", "Failed to initialize E2EE keys", e)
                                        }

                                        // CRITICAL: Push E2EE keys IMMEDIATELY after approval (synchronously!)
                                        // This ensures keys are in Firebase before macOS finishes its retry loop
                                        val finalDeviceId = pairingResult.deviceId ?: tokenData.deviceId
                                        if (tokenData.hasE2EEPublicKey && finalDeviceId != null) {
                                            android.util.Log.d("DesktopIntegrationScreen", "✅ Pushing E2EE keys to device: $finalDeviceId (after approval)")
                                            try {
                                                // Push synchronously - don't use background coroutine!
                                                desktopSyncService.pushE2EEKeysToDevice(
                                                    targetDeviceId = finalDeviceId,
                                                    targetPublicKeyX963 = tokenData.macPublicKeyX963!!
                                                )
                                                android.util.Log.d("DesktopIntegrationScreen", "✅ E2EE keys pushed successfully to $finalDeviceId")
                                            } catch (e: Exception) {
                                                android.util.Log.e("DesktopIntegrationScreen", "❌ Failed to push E2EE keys: ${e.message}", e)
                                                // Continue - device can request keys later via legacy flow
                                            }
                                        } else {
                                            android.util.Log.w("DesktopIntegrationScreen", "⚠️ No E2EE public key in QR, device will request keys")
                                        }

                                        android.util.Log.d("DesktopIntegrationScreen", "SETTING SUCCESS DIALOG: showSuccessDialog=true, message=Successfully paired")
                                        successMessage = "Successfully paired with desktop device!"
                                        showSuccessDialog = true
                                        android.util.Log.d("DesktopIntegrationScreen", "SUCCESS DIALOG STATE SET: showSuccessDialog=$showSuccessDialog, successMessage=$successMessage")

                                        // Start CallMonitorService to sync phone calls to Mac
                                        android.util.Log.d("DesktopIntegrationScreen", "Starting CallMonitorService for phone call sync")
                                        CallMonitorService.start(context)

                                        // Start IntelligentSyncManager for ongoing sync
                                        try {
                                            com.phoneintegration.app.services.IntelligentSyncManager
                                                .getInstance(context).startAfterPairing()
                                        } catch (e: Exception) {
                                            android.util.Log.e("DesktopIntegrationScreen", "Failed to start IntelligentSyncManager", e)
                                        }

                                        // Trigger initial data sync immediately
                                        scope.launch(Dispatchers.IO) {
                                            try {
                                                // Ensure full sync on new pairing
                                                appContext.getSharedPreferences("vps_sync_prefs", Context.MODE_PRIVATE)
                                                    .edit()
                                                    .remove("last_message_sync_time")
                                                    .apply()
                                                android.util.Log.d("DesktopIntegrationScreen", "Starting initial data sync after pairing")
                                                syncInitialMessages()
                                                syncInitialContacts()
                                                syncInitialCallHistory()
                                            } catch (e: Exception) {
                                                android.util.Log.e("DesktopIntegrationScreen", "Error syncing data", e)
                                            }
                                        }

                                        // Refresh device list immediately (server already registered the device)
                                        loadDevices(forceRefresh = true)
                                    }
                                    is CompletePairingResult.Rejected -> {
                                        errorMessage = "Pairing rejected by the desktop device"
                                    }
                                    is CompletePairingResult.Error -> {
                                        errorMessage = pairingResult.message
                                            ?: "Failed to pair device"
                                    }
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("DesktopIntegrationScreen", "Pairing failed", e)
                                errorMessage = e.message ?: "Failed to pair device"
                            }
                        } else if (isLegacyPairingCode(qrContent)) {
                            android.util.Log.d("DesktopIntegrationScreen", "Detected legacy pairing code - using Cloud Function system")
                            // Legacy format - use the working Cloud Function system
                            val tokenData = parsePairingQrCode(qrContent)
                            if (tokenData != null) {
                                val pairingResult = desktopSyncService.completePairing(tokenData.token, true)
                                when (pairingResult) {
                                    is CompletePairingResult.Approved -> {
                                        // Immediately add device to cache so sync UI appears
                                        withContext(Dispatchers.Main) {
                                            val newDevice = PairedDevice(
                                                id = tokenData.deviceId ?: "pending",
                                                name = tokenData.name,
                                                platform = tokenData.platform,
                                                lastSeen = System.currentTimeMillis()
                                            )
                                            PairedDevicesCache.updateDevices(
                                                PairedDevicesCache.devices + newDevice
                                            )
                                        }
                                        successMessage = "Successfully paired with desktop device!"
                                        showSuccessDialog = true
                                        // Start CallMonitorService for phone call sync
                                        CallMonitorService.start(context)
                                        scope.launch(Dispatchers.IO) {
                                            try {
                                                // Ensure full sync on new pairing
                                                appContext.getSharedPreferences("vps_sync_prefs", Context.MODE_PRIVATE)
                                                    .edit()
                                                    .remove("last_message_sync_time")
                                                    .apply()
                                                syncInitialMessages()
                                                syncInitialContacts()
                                                syncInitialCallHistory()
                                            } catch (e: Exception) {
                                                android.util.Log.e("DesktopIntegrationScreen", "Error syncing messages", e)
                                            }
                                        }
                                        // Refresh device list immediately (server already registered the device)
                                        loadDevices(forceRefresh = true)
                                    }
                                    is CompletePairingResult.Rejected -> {
                                        errorMessage = "Pairing was rejected"
                                    }
                                    is CompletePairingResult.Error -> {
                                        errorMessage = pairingResult.message
                                            ?: "Failed to pair device"
                                    }
                                }
                            } else {
                                errorMessage = "Invalid pairing code format"
                            }
                        } else {
                            android.util.Log.d("DesktopIntegrationScreen", "Invalid QR code format")
                            // Invalid format
                            errorMessage = "Invalid QR code format. Please ensure you're scanning a valid pairing code from a compatible app."
                        }
                    }
                } catch (e: Exception) {
                    errorMessage = e.message ?: "Failed to pair device"
                } finally {
                    isLoading = false
                    isScanButtonBusy = false
                }
            }
        }
    )
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Desktop Integration") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            item {
                Text(
                    text = "Connect Desktop & Web Apps",
                    style = MaterialTheme.typography.headlineMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Generate a QR code and scan it with your macOS or Web app to pair devices",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            // Device limit info card with User ID and Phone Number
            item {
                val vpsClient = com.phoneintegration.app.vps.VPSClient.getInstance(context)
                var currentUserId by remember { mutableStateOf(vpsClient.userId ?: "Not signed in") }
                var registeredPhone by remember { mutableStateOf("Loading...") }
                val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
                var showPhoneRegistrationDialog by remember { mutableStateOf(false) }

                // Auto-authenticate if not signed in
                LaunchedEffect(Unit) {
                    if (vpsClient.userId == null) {
                        android.util.Log.d("DesktopIntegrationScreen", "User not signed in, triggering VPS auth...")
                        try {
                            val userId = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                unifiedIdentityManager.getUnifiedUserId()
                            }
                            if (userId != null) {
                                currentUserId = userId
                                android.util.Log.d("DesktopIntegrationScreen", "VPS auth succeeded: $userId")
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("DesktopIntegrationScreen", "VPS auth failed: ${e.message}")
                        }
                    }
                }

                // Phone registration dialog
                if (showPhoneRegistrationDialog) {
                    PhoneNumberRegistrationDialog(
                        onDismiss = { showPhoneRegistrationDialog = false },
                        onRegistered = {
                            showPhoneRegistrationDialog = false
                            // Refresh the displayed phone number
                            val newPhone = getRegisteredPhoneNumber(context)
                            if (!newPhone.isNullOrEmpty()) {
                                registeredPhone = newPhone
                            }
                        }
                    )
                }

                // Fetch phone number - check local storage and VPS
                LaunchedEffect(currentUserId) {
                    if (currentUserId != "Not signed in") {
                        // Try local SharedPreferences
                        val localPhone = getRegisteredPhoneNumber(context)
                        android.util.Log.d("DesktopIntegrationScreen", "Local registered phone: $localPhone")

                        if (!localPhone.isNullOrEmpty()) {
                            registeredPhone = localPhone
                            android.util.Log.d("DesktopIntegrationScreen", "Using local phone: $localPhone")
                        } else {
                            // Try fetching from VPS server (after reinstall)
                            try {
                                val vpsPhone = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    vpsClient.getMyPhoneNumber()
                                }
                                if (!vpsPhone.isNullOrEmpty()) {
                                    registeredPhone = vpsPhone
                                    // Save locally for future use
                                    com.phoneintegration.app.ui.components.saveRegistrationState(context, vpsPhone)
                                    android.util.Log.d("DesktopIntegrationScreen", "Restored phone from VPS: $vpsPhone")
                                } else {
                                    registeredPhone = "Not registered"
                                }
                            } catch (e: Exception) {
                                registeredPhone = "Not registered"
                                android.util.Log.e("DesktopIntegrationScreen", "Failed to fetch phone from VPS: ${e.message}")
                            }
                        }
                    } else {
                        registeredPhone = "Not signed in"
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (canAddDevice)
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        else
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        // User ID row with copy button
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "User ID",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = currentUserId,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1
                                )
                            }
                            IconButton(
                                onClick = {
                                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(currentUserId))
                                }
                            ) {
                                Icon(
                                    Icons.Filled.ContentCopy,
                                    contentDescription = "Copy User ID",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // Registered Phone Number for Video Calls
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Video Call Number",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.VideoCall,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = if (registeredPhone != "Not registered")
                                            MaterialTheme.colorScheme.primary
                                        else
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = registeredPhone,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (registeredPhone != "Not registered")
                                            MaterialTheme.colorScheme.onSurface
                                        else
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (registeredPhone != "Not registered" && registeredPhone != "Loading...") {
                                // Copy button only - no edit to maintain call history integrity
                                IconButton(
                                    onClick = {
                                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(registeredPhone))
                                    }
                                ) {
                                    Icon(
                                        Icons.Filled.ContentCopy,
                                        contentDescription = "Copy Phone Number",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            } else if (registeredPhone == "Not registered") {
                                // Register button for users who skipped initial registration
                                TextButton(
                                    onClick = { showPhoneRegistrationDialog = true }
                                ) {
                                    Text("Register")
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Device count and plan info
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Paired Devices: $deviceCount / $deviceLimit",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "Plan: ${userPlan.replaceFirstChar { it.uppercase() }}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (!canAddDevice) {
                                TextButton(onClick = {
                                    errorMessage = "Device limit reached. Upgrade to Pro for unlimited devices."
                                }) {
                                    Text("Upgrade", color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }

            // Scan QR Button - ALWAYS enabled unless actively scanning or device limit reached
            item {
                Button(
                    onClick = {
                        android.util.Log.d("DesktopIntegrationScreen", "Scan button clicked! canAddDevice=$canAddDevice, isScanButtonBusy=$isScanButtonBusy")
                        if (!canAddDevice) {
                            errorMessage = "Device limit reached ($deviceCount/$deviceLimit). Upgrade to Pro for unlimited devices."
                            return@Button
                        }
                        // Check if recovery code setup is needed before first pairing
                        checkRecoveryBeforePairing {
                            try {
                                val options = ScanOptions().apply {
                                    setDesiredBarcodeFormats("QR_CODE")
                                    // Improved prompt to guide user better
                                    setPrompt("Point camera at the QR code on your Mac or Web app")
                                    setBeepEnabled(true)
                                    setOrientationLocked(false)
                                    setCaptureActivity(com.journeyapps.barcodescanner.CaptureActivity::class.java)
                                }
                                isScanButtonBusy = true
                                errorMessage = null
                                android.util.Log.d("DesktopIntegrationScreen", "Launching QR scanner...")
                                scanLauncher.launch(options)
                            } catch (e: Exception) {
                                android.util.Log.e("DesktopIntegrationScreen", "Error launching QR scanner", e)
                                errorMessage = "Failed to open camera: ${e.message}"
                                isScanButtonBusy = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    // Only disable when: actively scanning OR confirmed device limit reached
                    // Do NOT disable during loading - user should be able to scan immediately
                    enabled = !isScanButtonBusy && canAddDevice
                ) {
                    Icon(Icons.Default.QrCodeScanner, "Scan QR")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        when {
                            !canAddDevice -> "Device Limit Reached"
                            isScanButtonBusy -> "Scanning..."
                            else -> "Scan Desktop QR Code"
                        }
                    )
                }

                // Help text under button
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (canAddDevice)
                        "Open SyncFlow on your Mac or Web browser to see the QR code"
                    else
                        "Upgrade to Pro for unlimited devices",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (canAddDevice) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                )
            }

            // Error Message
            errorMessage?.let { error ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Error,
                                "Error",
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = error,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { errorMessage = null }) {
                                Icon(Icons.Default.Close, "Dismiss")
                            }
                        }
                    }
                }
            }

            // Only show Paired Devices section when devices exist or loading
            if (pairedDevices.isNotEmpty() || isLoadingDevices) {
                // Paired Devices Section
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Paired Devices (${pairedDevices.size})",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Refresh button
                            IconButton(
                                onClick = { loadDevices(forceRefresh = true) },
                                enabled = !isLoadingDevices
                            ) {
                                if (isLoadingDevices) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(Icons.Default.Refresh, "Refresh")
                                }
                            }
                        }
                    }
                }

                // Show loading indicator
                if (isLoadingDevices && pairedDevices.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Text("Loading devices...")
                            }
                        }
                    }
                }

                // Show paired devices list
                items(pairedDevices) { device ->
                    PairedDeviceItem(
                        device = device,
                        onUnpair = {
                            scope.launch {
                                try {
                                    isLoading = true
                                    val result = unifiedIdentityManager.unregisterDevice(device.id)
                                    result.onSuccess {
                                        successMessage = "Successfully unpaired ${device.name}"
                                        showSuccessDialog = true
                                        // Update cache when device is unpaired
                                        DesktopSyncService.updatePairedDevicesCache(appContext, false, 0)
                                        // Clear sync timestamps so re-pairing triggers full sync
                                        appContext.getSharedPreferences("vps_sync_prefs", Context.MODE_PRIVATE)
                                            .edit()
                                            .remove("last_message_sync_time")
                                            .apply()
                                        loadDevices(forceRefresh = true)
                                    }.onFailure { error ->
                                        errorMessage = error.message ?: "Failed to unpair device"
                                    }
                                } catch (e: Exception) {
                                    errorMessage = e.message ?: "Failed to unpair device"
                                } finally {
                                    isLoading = false
                                }
                            }
                        }
                    )
                }
            } // End of paired devices section

            // Show "No devices" only when not loading and list is empty
            if (!isLoadingDevices && pairedDevices.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.Devices,
                                "No devices",
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "No devices paired yet",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Scan the QR code from your Mac or Web app to pair.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            // Debug info
                            val vpsClientDebug = com.phoneintegration.app.vps.VPSClient.getInstance(context)
                            val currentUserId = vpsClientDebug.userId
                            val lastError = PairedDevicesCache.lastError
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Debug: userId=${currentUserId?.take(8) ?: "null"}...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                            if (lastError != null) {
                                Text(
                                    text = "Error: $lastError",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            // Sync Settings Section - only show when devices are paired
            if (pairedDevices.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Sync Settings",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                        IconButton(
                            onClick = { refreshSettings() },
                            modifier = Modifier.padding(vertical = 16.dp)
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                "Refresh Settings",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

            // Background Sync Setting
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Sync,
                            "Background Sync",
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Background Sync",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Keep messages and data synced in background",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isBackgroundSyncEnabled,
                            onCheckedChange = { enabled ->
                                isBackgroundSyncEnabled = enabled
                                preferencesManager.setBackgroundSyncEnabled(enabled)
                                android.util.Log.d("DesktopIntegrationScreen", "Background sync ${if (enabled) "enabled" else "disabled"}")
                            }
                        )
                    }
                }
            }

            // Notification Mirroring Setting
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Notifications,
                            "Notification Mirroring",
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Notification Mirroring",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                if (!hasNotificationPermission) {
                                    IconButton(
                                        onClick = {
                                            // Refresh permission status
                                            hasNotificationPermission = NotificationMirrorService.isEnabled(appContext)
                                            android.util.Log.d("DesktopIntegrationScreen", "Permission status refreshed: $hasNotificationPermission")
                                        },
                                        modifier = Modifier.size(20.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Refresh,
                                            "Refresh permission status",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                            Text(
                                text = "Mirror Android notifications to desktop",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (!hasNotificationPermission) {
                                Text(
                                    text = "Requires notification access permission - tap switch to enable",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        Switch(
                            checked = isNotificationMirrorEnabled && hasNotificationPermission,
                            onCheckedChange = { enabled ->
                                if (enabled && !hasNotificationPermission) {
                                    // User wants to enable but doesn't have permission - open settings
                                    android.util.Log.d("DesktopIntegrationScreen", "Opening notification access settings")
                                    try {
                                        val intent = android.content.Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                                        context.startActivity(intent)
                                        successMessage = "Opening Notification Access settings...\n\nPlease enable SyncFlow, then return to this screen and toggle the switch again."
                                        showSuccessDialog = true
                                    } catch (e: Exception) {
                                        android.util.Log.e("DesktopIntegrationScreen", "Failed to open notification settings", e)
                                        // Fallback to general settings if specific intent fails
                                        try {
                                            val intent = android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
                                            context.startActivity(intent)
                                            successMessage = "Opening Settings...\n\nPlease enable Notification Access for SyncFlow in Special Access → Notification Access."
                                            showSuccessDialog = true
                                        } catch (e2: Exception) {
                                            android.util.Log.e("DesktopIntegrationScreen", "Failed to open general settings", e2)
                                            // Show message if we can't open settings
                                            successMessage = "Please enable Notification Access for SyncFlow in Android Settings → Special Access → Notification Access."
                                            showSuccessDialog = true
                                        }
                                    }
                                } else if (hasNotificationPermission) {
                                    // Permission granted, update setting
                                    isNotificationMirrorEnabled = enabled
                                    preferencesManager.setNotificationMirrorEnabled(enabled)

                                    val userId = unifiedIdentityManager.getUnifiedUserIdSync()
                                    android.util.Log.d("DesktopIntegrationScreen", "Notification mirroring ${if (enabled) "enabled" else "disabled"} for user: $userId")
                                    android.util.Log.d("DesktopIntegrationScreen", "Notifications will be stored under: /users/$userId/mirrored_notifications/")

                                    if (enabled) {
                                        successMessage = "Notification mirroring enabled!\n\nNotifications will be sent to: /users/$userId/mirrored_notifications/\n\nMake sure your macOS app is authenticated with the same user ID."
                                        showSuccessDialog = true
                                    }
                                }
                            }
                        )
                    }
                }
            }
            } // End of Sync Settings section (only shown when devices are paired)

            // Recovery Code Section
            item {
                var showRecoveryCode by remember { mutableStateOf(false) }
                var recoveryCode by remember { mutableStateOf<String?>(null) }
                var isLoadingCode by remember { mutableStateOf(false) }
                val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Key,
                                "Recovery Code",
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Recovery Code",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "Use this code to recover your account after reinstall",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(
                                onClick = {
                                    if (showRecoveryCode) {
                                        showRecoveryCode = false
                                    } else {
                                        scope.launch {
                                            isLoadingCode = true
                                            val recoveryManager = com.phoneintegration.app.auth.RecoveryCodeManager.getInstance(appContext)
                                            recoveryCode = recoveryManager.getRecoveryCode()
                                            isLoadingCode = false
                                            showRecoveryCode = true
                                        }
                                    }
                                },
                                enabled = !isLoadingCode
                            ) {
                                Text(if (isLoadingCode) "Loading..." else if (showRecoveryCode) "Hide" else "View")
                            }
                        }

                        // Show recovery code when expanded
                        if (showRecoveryCode && recoveryCode != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = recoveryCode ?: "",
                                        style = MaterialTheme.typography.titleLarge.copy(
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                            letterSpacing = 2.sp
                                        ),
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Button(
                                        onClick = {
                                            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(recoveryCode ?: ""))
                                            successMessage = "Recovery code copied to clipboard!"
                                            showSuccessDialog = true
                                        }
                                    ) {
                                        Icon(
                                            Icons.Default.ContentCopy,
                                            "Copy",
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Copy Code")
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Save this code securely. You'll need it to recover your account if you reinstall the app.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else if (showRecoveryCode && recoveryCode == null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "No recovery code found. You may have skipped recovery setup.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            // Photo Sync Setting
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Photo,
                            "Photo Sync",
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Photo Sync",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Sync recent photos to desktop (Premium feature)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(
                            onClick = {
                                scope.launch {
                                    try {
                                        isLoading = true
                                        val photoSyncService = PhotoSyncService(appContext)
                                        val result = photoSyncService.syncRecentPhotos()

                                        result.onSuccess { message ->
                                            successMessage = message
                                            showSuccessDialog = true
                                        }.onFailure { error ->
                                            errorMessage = error.message ?: "Photo sync failed"
                                        }

                                    } catch (e: Exception) {
                                        errorMessage = "Photo sync failed: ${e.message}"
                                    } finally {
                                        isLoading = false
                                    }
                                }
                            },
                            enabled = !isLoading
                        ) {
                            Text(if (isLoading) "Syncing..." else "Sync Now")
                        }
                    }
                }
            }
        }
    }

    // Manual Code Input Dialog
    if (showManualInput) {
        AlertDialog(
            onDismissRequest = { showManualInput = false },
            title = { Text("Enter Pairing Token") },
            text = {
                Column {
                    Text("Enter the pairing token:")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "You can generate a test token using the button above, or get a token from a compatible desktop app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = manualCode,
                        onValueChange = { manualCode = it.trim() },
                        label = { Text("Pairing Token") },
                        placeholder = { Text("Enter token here...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            try {
                                isLoading = true
                                errorMessage = null

                                val result = unifiedIdentityManager.redeemPairingToken(
                                    manualCode.trim(),
                                    "Manual Entry Device",
                                    "android"
                                )

                                result.onSuccess { deviceInfo ->
                                    successMessage = "Successfully paired device: ${deviceInfo.name}!"
                                    showSuccessDialog = true
                                    showManualInput = false
                                    manualCode = ""
                                }.onFailure { error ->
                                    when {
                                        error.message?.contains("expired") == true ->
                                            errorMessage = "Token has expired. Please generate a new one."
                                        error.message?.contains("Invalid") == true ->
                                            errorMessage = "Invalid token format. Please check and try again."
                                        else ->
                                            errorMessage = "Failed to pair device: ${error.message}"
                                    }
                                }

                            } catch (e: Exception) {
                                errorMessage = e.message ?: "Failed to pair device"
                            } finally {
                                isLoading = false
                            }
                        }
                    },
                    enabled = manualCode.isNotBlank() && !isLoading
                ) {
                    Text("Pair")
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualInput = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Success Dialog
    if (showSuccessDialog && successMessage != null) {
        AlertDialog(
            onDismissRequest = {
                showSuccessDialog = false
                successMessage = null
            },
            icon = {
                Icon(
                    Icons.Default.CheckCircle,
                    "Success",
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = { Text("Success!") },
            text = { Text(successMessage!!) },
            confirmButton = {
                TextButton(onClick = {
                    showSuccessDialog = false
                    successMessage = null
                }) {
                    Text("OK")
                }
            }
        )
    }

    // Recovery Code Setup Dialog - shows before first pairing
    if (showRecoverySetupDialog) {
        AlertDialog(
            onDismissRequest = {
                // Don't allow dismissing without action
            },
            icon = {
                Icon(
                    Icons.Default.Key,
                    "Recovery Code",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                )
            },
            title = { Text(if (showRecoveryCodeEntry) "Enter Recovery Code" else "Save Your Recovery Code") },
            text = {
                Column {
                    if (showRecoveryCodeEntry) {
                        // Recovery code entry mode
                        Text(
                            text = "Enter your recovery code to restore your account:",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedTextField(
                            value = enteredRecoveryCode,
                            onValueChange = {
                                enteredRecoveryCode = it.uppercase().filter { c -> c.isLetterOrDigit() || c == '-' }
                                recoveryError = null
                            },
                            label = { Text("Recovery Code") },
                            placeholder = { Text("SYNC-XXXX-XXXX-XXXX") },
                            singleLine = true,
                            enabled = !isRecoveringAccount,
                            isError = recoveryError != null,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                letterSpacing = 1.sp
                            )
                        )
                        if (recoveryError != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = recoveryError!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        if (isRecoveringAccount) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("Recovering account...")
                            }
                        }
                        if (recoverySuccess) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        "Success",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = "Account Recovered Successfully!",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        if (recoveredUserId != null) {
                                            Text(
                                                text = "User ID: ${recoveredUserId!!.take(8)}...",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Text(
                                            text = "Loading your data...",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    } else if (generatedRecoveryCode == null && !isGeneratingCode) {
                        Text(
                            text = "Before pairing with your Mac or Web app, we recommend saving a recovery code.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "This code lets you recover your data if you reinstall the app or switch phones.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    Icons.Default.Warning,
                                    "Warning",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Without a recovery code, reinstalling this app will result in losing access to your synced messages.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    } else if (isGeneratingCode) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Text("Generating secure code...")
                        }
                    } else if (generatedRecoveryCode != null) {
                        Text(
                            text = "Your recovery code has been generated. Save it somewhere safe!",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = generatedRecoveryCode!!,
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                        letterSpacing = 2.sp
                                    ),
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                OutlinedButton(
                                    onClick = {
                                        clipboardManager.setText(
                                            androidx.compose.ui.text.AnnotatedString(generatedRecoveryCode!!)
                                        )
                                        successMessage = "Recovery code copied!"
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.ContentCopy,
                                        "Copy",
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Copy to Clipboard")
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "You can also view this code later in Settings > Recovery Code.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                if (showRecoveryCodeEntry) {
                    // Recovery mode - show "Recover Account" button
                    Button(
                        onClick = {
                            scope.launch {
                                isRecoveringAccount = true
                                recoveryError = null
                                val result = recoveryManager.recoverWithCode(enteredRecoveryCode.trim())
                                result.onSuccess { userId ->
                                    // Show success state in dialog for 2 seconds
                                    recoverySuccess = true
                                    recoveredUserId = userId
                                    isRecoveringAccount = false

                                    // Wait 2 seconds to show success message
                                    kotlinx.coroutines.delay(2000)

                                    // Then close dialog and proceed
                                    successMessage = "Account recovered successfully! Loading your data..."
                                    showRecoverySetupDialog = false
                                    showRecoveryCodeEntry = false
                                    enteredRecoveryCode = ""
                                    recoverySuccess = false
                                    recoveredUserId = null

                                    // Proceed with pairing
                                    pendingPairingAction?.invoke()
                                    pendingPairingAction = null
                                }.onFailure { error ->
                                    recoveryError = error.message ?: "Recovery failed"
                                    isRecoveringAccount = false
                                }
                            }
                        },
                        enabled = enteredRecoveryCode.length >= 12 && !isRecoveringAccount
                    ) {
                        Icon(Icons.Default.Restore, "Recover", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Recover Account")
                    }
                } else if (generatedRecoveryCode == null && !isGeneratingCode) {
                    // Show "Generate Code" button
                    Button(
                        onClick = {
                            scope.launch {
                                isGeneratingCode = true
                                val result = recoveryManager.setupRecoveryCode()
                                result.onSuccess { code ->
                                    generatedRecoveryCode = code
                                }.onFailure { error ->
                                    errorMessage = "Failed to generate recovery code: ${error.message}"
                                    showRecoverySetupDialog = false
                                    pendingPairingAction = null
                                }
                                isGeneratingCode = false
                            }
                        }
                    ) {
                        Icon(Icons.Default.Key, "Generate", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Generate Recovery Code")
                    }
                } else if (generatedRecoveryCode != null) {
                    // Show "Continue to Pairing" button
                    Button(
                        onClick = {
                            showRecoverySetupDialog = false
                            // Proceed with the pending pairing action
                            pendingPairingAction?.invoke()
                            pendingPairingAction = null
                            generatedRecoveryCode = null
                        }
                    ) {
                        Text("Continue to Pairing")
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(Icons.Default.Check, "Continue", modifier = Modifier.size(18.dp))
                    }
                }
            },
            dismissButton = {
                if (showRecoveryCodeEntry) {
                    // Back button in recovery mode
                    TextButton(
                        onClick = {
                            showRecoveryCodeEntry = false
                            enteredRecoveryCode = ""
                            recoveryError = null
                        },
                        enabled = !isRecoveringAccount
                    ) {
                        Text("Back")
                    }
                } else if (!isGeneratingCode) {
                    Row {
                        // "I have a code" button
                        if (generatedRecoveryCode == null) {
                            TextButton(
                                onClick = {
                                    showRecoveryCodeEntry = true
                                }
                            ) {
                                Text("I have a code")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        TextButton(
                            onClick = {
                                if (generatedRecoveryCode == null) {
                                    // User is skipping recovery setup
                                    scope.launch {
                                        recoveryManager.skipSetup()
                                        showRecoverySetupDialog = false
                                        // Proceed with pairing anyway
                                        pendingPairingAction?.invoke()
                                        pendingPairingAction = null
                                    }
                                } else {
                                    // User already generated code, just cancel
                                    showRecoverySetupDialog = false
                                    pendingPairingAction = null
                                    generatedRecoveryCode = null
                                }
                            }
                        ) {
                            Text(
                                text = if (generatedRecoveryCode == null) "Skip" else "Cancel",
                                color = if (generatedRecoveryCode == null)
                                    MaterialTheme.colorScheme.error
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        )
    }
}

// =============================================================================
// endregion
// =============================================================================

// =============================================================================
// region QR CODE PARSING
// =============================================================================

/**
 * Parses QR code data for device pairing.
 *
 * Supports two formats:
 * - V1 format: {"token": "...", "name": "...", "platform": "...", "syncGroupId": "..."}
 * - V2 format: {"v": 2, "token": "...", "device": {"id": "...", "name": "...", "type": "..."}, "expires": ...}
 *
 * @param qrData Raw QR code content string
 * @return PairingQrData if parsing succeeds, null otherwise
 */
private fun parsePairingQrCode(qrData: String): PairingQrData? {
    android.util.Log.d("QRParser", "Parsing QR code, total length: ${qrData.length}")

    // Check for VPS QR format first: syncflow://pair?token=<token>&server=<server>
    if (qrData.startsWith("syncflow://pair")) {
        android.util.Log.d("QRParser", "Detected VPS QR code format")
        return parseVpsPairingQrCode(qrData)
    }

    return try {
        // Check for E2EE public key appended after pipe separator
        // Format: {json}|macPublicKeyX963Base64
        var jsonData = qrData
        var macPublicKeyX963: String? = null

        val pipeIndex = qrData.lastIndexOf('|')
        android.util.Log.d("QRParser", "Pipe index: $pipeIndex (length: ${qrData.length})")

        if (pipeIndex > 0 && pipeIndex < qrData.length - 1) {
            // Found pipe - extract public key and JSON separately
            val potentialKey = qrData.substring(pipeIndex + 1)
            val potentialJson = qrData.substring(0, pipeIndex)

            android.util.Log.d("QRParser", "Found potential key after pipe, length: ${potentialKey.length}")
            android.util.Log.d("QRParser", "Key preview: ${potentialKey.take(20)}...${potentialKey.takeLast(20)}")

            // Validate it looks like a base64 key (no special JSON chars)
            if (!potentialKey.contains('{') && !potentialKey.contains('}') && potentialKey.length > 50) {
                macPublicKeyX963 = potentialKey
                jsonData = potentialJson
                android.util.Log.d("QRParser", "✅ Extracted E2EE public key from QR code (${potentialKey.length} chars)")
            } else {
                android.util.Log.w("QRParser", "❌ Potential key rejected: contains JSON chars or too short")
            }
        } else {
            android.util.Log.w("QRParser", "❌ No pipe separator found in QR code - E2EE key not present")
        }

        val json = org.json.JSONObject(jsonData)

        // Check for V2 format first (has "v" field and "device" object)
        val version = json.optInt("v", 1)
        if (version >= 2 && json.has("device")) {
            // V2 format: {"v": 2, "token": "...", "device": {"id": "...", "name": "...", "type": "..."}, "expires": ...}
            val token = json.optString("token", "")
            val device = json.optJSONObject("device")
            val deviceId = device?.optString("id", "") ?: ""
            val deviceName = device?.optString("name", "Desktop") ?: "Desktop"
            val deviceType = device?.optString("type", "macos") ?: "macos"
            val expiresAt = json.optLong("expires", 0)

            if (token.isBlank()) {
                android.util.Log.w("QRParser", "V2 QR code has empty token")
                null
            } else {
                android.util.Log.d("QRParser", "Parsed V2 QR: token=${token.take(8)}..., deviceId='$deviceId' (len=${deviceId.length}), type=$deviceType, hasE2EEKey=${macPublicKeyX963 != null}, keyLen=${macPublicKeyX963?.length ?: 0}")
                PairingQrData(
                    token = token,
                    name = deviceName,
                    platform = deviceType,
                    syncGroupId = null,
                    version = 2,
                    deviceId = deviceId,
                    expiresAt = expiresAt,
                    macPublicKeyX963 = macPublicKeyX963
                )
            }
        } else {
            // V1 format: {"token": "...", "name": "...", "platform": "...", "syncGroupId": "..."}
            val token = json.optString("token", "")
            val name = json.optString("name", "Desktop")
            val platform = json.optString("platform", "web")
            val rawSyncGroupId = json.optString("syncGroupId", "")
            val syncGroupId = if (rawSyncGroupId == "null") "" else rawSyncGroupId

            if (token.isBlank()) {
                android.util.Log.w("QRParser", "V1 QR code has empty token")
                null
            } else {
                android.util.Log.d("QRParser", "Parsed V1 QR: token=${token.take(8)}..., name=$name, hasE2EEKey=${macPublicKeyX963 != null}")
                PairingQrData(
                    token = token,
                    name = name,
                    platform = platform,
                    syncGroupId = syncGroupId.ifBlank { null },
                    version = 1,
                    deviceId = null,
                    expiresAt = 0,
                    macPublicKeyX963 = macPublicKeyX963
                )
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("QRParser", "Failed to parse QR code", e)
        null
    }
}

/**
 * Checks if a QR code is in legacy format (non-JSON or missing token).
 *
 * @param qrData Raw QR code content string
 * @return true if the code appears to be legacy format
 */
private fun isLegacyPairingCode(qrData: String): Boolean {
    return try {
        // Legacy codes might be plain strings or different JSON format
        !qrData.trim().startsWith("{") || !qrData.contains("token")
    } catch (e: Exception) {
        true // Assume legacy if parsing fails
    }
}

/**
 * Parses a VPS pairing QR code.
 *
 * VPS QR format: syncflow://pair?token=<token>&server=<server>&deviceId=<deviceId>&e2eeKey=<base64Key>
 *
 * @param qrData Raw QR code content string
 * @return PairingQrData if parsing succeeds, null otherwise
 */
private fun parseVpsPairingQrCode(qrData: String): PairingQrData? {
    return try {
        val uri = android.net.Uri.parse(qrData)
        val token = uri.getQueryParameter("token")
        val server = uri.getQueryParameter("server")
        val deviceId = uri.getQueryParameter("deviceId")
        val e2eeKey = uri.getQueryParameter("e2eeKey")
        // Android Uri decoding converts '+' to space; restore it for Base64 keys.
        val normalizedE2eeKey = e2eeKey?.replace(" ", "+")

        if (token.isNullOrBlank()) {
            android.util.Log.w("QRParser", "VPS QR code has empty token")
            return null
        }

        android.util.Log.d("QRParser", "Parsed VPS QR: token=${token.take(8)}..., server=$server, deviceId=$deviceId, hasE2eeKey=${!normalizedE2eeKey.isNullOrBlank()}")

        // Return PairingQrData with VPS-specific fields
        // Use version = 3 to indicate VPS pairing
        PairingQrData(
            token = token,
            name = "VPS Device",
            platform = "vps",
            syncGroupId = null,
            version = 3, // VPS version
            deviceId = deviceId,
            expiresAt = 0,
            macPublicKeyX963 = normalizedE2eeKey, // E2EE public key from macOS
            vpsServer = server // New field for VPS server URL
        )
    } catch (e: Exception) {
        android.util.Log.e("QRParser", "Failed to parse VPS QR code", e)
        null
    }
}

// =============================================================================
// endregion
// =============================================================================

// =============================================================================
// region DATA CLASSES
// =============================================================================

/**
 * Represents parsed pairing QR code data.
 *
 * Supports V1 (legacy), V2 (with device limits), and V3 (VPS) formats.
 *
 * @property token The pairing token to exchange with the server
 * @property name Display name for the device being paired
 * @property platform Platform type ("macos", "web", "vps", etc.)
 * @property syncGroupId Optional sync group ID for V1 format
 * @property version QR format version (1 = legacy, 2 = Firebase V2, 3 = VPS)
 * @property deviceId Persistent device ID (V2 only)
 * @property expiresAt Token expiration timestamp (V2 only)
 * @property vpsServer VPS server URL (V3/VPS only)
 */
data class PairingQrData(
    val token: String,
    val name: String,
    val platform: String,
    val syncGroupId: String?,
    val version: Int = 1,        // 1 = legacy, 2 = Firebase V2, 3 = VPS
    val deviceId: String? = null, // Persistent device ID (V2 only)
    val expiresAt: Long = 0,      // Token expiration timestamp (V2 only)
    val macPublicKeyX963: String? = null,  // macOS/Web public key for direct E2EE key exchange
    val vpsServer: String? = null  // VPS server URL (V3/VPS only)
) {
    /** Whether this is a V2 format QR code with device limit support */
    val isV2: Boolean get() = version == 2
    /** Whether this is a VPS (V3) format QR code */
    val isVPS: Boolean get() = version == 3
    /** Whether this QR code includes a public key for direct E2EE key exchange */
    val hasE2EEPublicKey: Boolean get() = !macPublicKeyX963.isNullOrBlank()
}

// =============================================================================
// endregion
// =============================================================================

// =============================================================================
// region PAIRED DEVICE COMPONENTS
// =============================================================================

/**
 * Displays a single paired device card with info and unpair action.
 *
 * Shows device icon (based on platform), name, platform type, last seen time,
 * and sync status. Includes an unpair button with confirmation dialog.
 *
 * @param device The PairedDevice to display
 * @param onUnpair Callback when user confirms unpair action
 */
@Composable
private fun PairedDeviceItem(
    device: PairedDevice,
    onUnpair: () -> Unit
) {
    var showUnpairDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Device Icon
            val icon = when (device.platform.lowercase()) {
                "macos", "mac" -> Icons.Default.Computer
                "windows" -> Icons.Default.DesktopWindows
                else -> Icons.Default.Web
            }

            Icon(
                icon,
                contentDescription = device.platform,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.width(16.dp))

            // Device Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = device.platform.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Last seen: ${java.text.SimpleDateFormat("MMM dd, hh:mm a", java.util.Locale.getDefault()).format(java.util.Date(device.lastSeen))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Sync Status
                device.syncStatus?.let { syncStatus ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        when (syncStatus.status) {
                            "starting" -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 1.dp
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Starting sync...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            "syncing" -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 1.dp
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Syncing: ${syncStatus.syncedMessages}/${syncStatus.totalMessages}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            "completed" -> {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    "Sync completed",
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                val completedTime = syncStatus.lastSyncCompleted?.let {
                                    java.text.SimpleDateFormat("MMM dd, hh:mm a", java.util.Locale.getDefault()).format(java.util.Date(it))
                                } ?: "Recently"
                                Text(
                                    text = "${syncStatus.syncedMessages} messages synced ($completedTime)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            "failed" -> {
                                Icon(
                                    Icons.Default.Error,
                                    "Sync failed",
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Sync failed",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            else -> {
                                // Idle state - no sync status to show
                            }
                        }
                    }
                }
            }

            // Unpair Button
            IconButton(onClick = { showUnpairDialog = true }) {
                Icon(
                    Icons.Default.Delete,
                    "Unpair",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    // Unpair Confirmation Dialog
    if (showUnpairDialog) {
        AlertDialog(
            onDismissRequest = { showUnpairDialog = false },
            title = { Text("Unpair Device?") },
            text = { Text("Are you sure you want to unpair ${device.name}? You'll need to scan the QR code again to reconnect.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onUnpair()
                        showUnpairDialog = false
                    }
                ) {
                    Text("Unpair", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnpairDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// =============================================================================
// endregion
// =============================================================================
