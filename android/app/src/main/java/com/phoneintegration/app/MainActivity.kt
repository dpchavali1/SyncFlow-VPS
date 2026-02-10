package com.phoneintegration.app

// =============================================================================
// ARCHITECTURE OVERVIEW
// =============================================================================
//
// MainActivity is the primary entry point Activity for the SyncFlow Android app.
// It serves as the host for the Jetpack Compose UI and coordinates the various
// services, permissions, and features of the application.
//
// ARCHITECTURE PATTERN:
// ---------------------
// The app follows the MVVM (Model-View-ViewModel) architecture:
// - View: Jetpack Compose UI defined in setContent{}
// - ViewModel: SmsViewModel for SMS data and business logic
// - Model: Repository layer and Firebase Realtime Database
//
// INITIALIZATION FLOW:
// --------------------
// onCreate() performs the following in order:
// 1. Essential setup (PreferencesManager, AuthManager, RecoveryCodeManager)
// 2. Permission checking (request if core permissions missing)
// 3. Intent handling (calls, shares, conversation launches)
// 4. Broadcast receiver registration (call dismissal events)
// 5. Background initialization (Signal Protocol, FCM, MobileAds)
// 6. Service management (via BatteryAwareServiceManager)
// 7. UI composition (setContent with PhoneIntegrationTheme)
//
// PERMISSION STRATEGY:
// --------------------
// Uses a minimal permission approach to reduce antivirus false positives:
// - CORE_PERMISSIONS: Essential for SMS sync (requested at startup)
// - CALL_PERMISSIONS: Requested when using call features
// - MEDIA_PERMISSIONS: Requested when starting video calls
// - STORAGE_PERMISSIONS: Requested when attaching files
//
// SERVICE MANAGEMENT:
// -------------------
// Services are managed by BatteryAwareServiceManager for optimal battery:
// - Services start/stop based on battery level and charging status
// - Network-dependent operations prefer WiFi over mobile data
// - SyncFlowCallService starts on-demand via FCM push notifications
//
// INTENT HANDLING:
// ----------------
// MainActivity handles multiple intent types:
// - Incoming calls (from FCM/notification): Shows call UI over lock screen
// - Active calls (already answered): Shows ongoing call screen
// - Share intents: Opens conversation with shared content
// - Conversation launches: Opens specific message thread
//
// LIFECYCLE CONSIDERATIONS:
// -------------------------
// - onResume: Updates auth activity, reconciles deleted messages
// - onDestroy: Unregisters broadcast receivers, cleans up services
// - onNewIntent: Handles new intents while activity is running
//
// LOCK SCREEN INTEGRATION:
// ------------------------
// For incoming calls, the activity can display over the lock screen using:
// - setShowWhenLocked(true) on API 27+
// - FLAG_SHOW_WHEN_LOCKED flag on older APIs
// The call UI shows over the lock screen without forcing unlock.
//
// =============================================================================

import android.Manifest
import android.app.KeyguardManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import android.view.WindowManager
import com.google.android.gms.ads.MobileAds
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.phoneintegration.app.CallMonitorService
import com.phoneintegration.app.R
import com.phoneintegration.app.SmsPermissions
import com.phoneintegration.app.SmsViewModel
import com.phoneintegration.app.auth.AuthManager
import com.phoneintegration.app.auth.RecoveryCodeManager
import com.phoneintegration.app.auth.UnifiedIdentityManager
import com.phoneintegration.app.auth.ExistingAccountInfo
import com.phoneintegration.app.desktop.*
import com.phoneintegration.app.ui.auth.RecoveryCodeScreen
import com.phoneintegration.app.share.SharePayload
import com.phoneintegration.app.utils.DefaultSmsHelper
import com.phoneintegration.app.data.PreferencesManager
import com.phoneintegration.app.e2ee.SignalProtocolManager
import com.phoneintegration.app.webrtc.SyncFlowCallManager
import com.phoneintegration.app.services.BatteryAwareServiceManager
import com.phoneintegration.app.ui.call.SyncFlowCallScreen
import com.phoneintegration.app.ui.call.IncomingSyncFlowCallScreen
import com.phoneintegration.app.ui.navigation.MainNavigation
import com.phoneintegration.app.ui.theme.PhoneIntegrationTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Main Activity and entry point for the SyncFlow Android application.
 *
 * This activity hosts the Jetpack Compose UI and coordinates between various
 * services, permissions, and system features. It handles:
 * - Permission requests and runtime permission management
 * - System role requests (default SMS app, default dialer)
 * - Incoming and active call UI overlay management
 * - Share intent handling for sending content via SMS
 * - Lock screen integration for incoming calls
 *
 * ## State Management
 * Uses a combination of:
 * - [SmsViewModel] for SMS data (via viewModels delegate)
 * - [PreferencesManager] for user preferences
 * - Compose State for UI-specific state
 * - MutableState triggers for intent-driven state changes
 *
 * ## Service Integration
 * Services are managed through [BatteryAwareServiceManager] which optimizes
 * for battery life by adjusting sync frequency based on power conditions.
 *
 * @see SyncFlowApp Application class that initializes core services
 * @see SmsViewModel ViewModel for SMS data management
 * @see BatteryAwareServiceManager Intelligent service management
 */
class MainActivity : ComponentActivity() {

    // =========================================================================
    // MARK: - ViewModel and Core Dependencies
    // =========================================================================

    /** SMS ViewModel providing message data and business logic */
    val viewModel: SmsViewModel by viewModels()

    /** User preferences manager for theme, sync settings, etc. */
    private lateinit var preferencesManager: PreferencesManager

    /** Authentication manager for session and token management */
    private lateinit var authManager: AuthManager

    /** Recovery code manager for account recovery */
    private lateinit var recoveryCodeManager: RecoveryCodeManager

    // =========================================================================
    // MARK: - Call State (Intent-Driven)
    // =========================================================================
    // These mutable state objects bridge between onNewIntent() and Compose.
    // When onNewIntent receives a call-related intent, it updates the pending
    // values and increments the trigger, causing Compose to recompose.

    /** Trigger for active call state changes (incremented to trigger recomposition) */
    private val _activeCallTrigger = mutableStateOf(0)
    private var _pendingActiveCallId: String? = null
    private var _pendingActiveCallName: String? = null
    private var _pendingActiveCallVideo: Boolean = false

    /** Trigger for incoming call state changes */
    private val _incomingCallTrigger = mutableStateOf(0)
    private var _pendingIncomingCallId: String? = null
    private var _pendingIncomingCallName: String? = null
    private var _pendingIncomingCallVideo: Boolean = false

    /** Triggers for call dismissal (from broadcast receivers) */
    private val _dismissCallTrigger = mutableStateOf(0)
    private val _endCallTrigger = mutableStateOf(0)

    // =========================================================================
    // MARK: - Broadcast Receivers
    // =========================================================================
    // Registered at Activity level for reliability across configuration changes

    /** Receiver for incoming call UI dismissal events */
    private var callDismissReceiver: android.content.BroadcastReceiver? = null

    /** Receiver for call ended by remote party events */
    private var callEndedReceiver: android.content.BroadcastReceiver? = null

    // Services are now managed by BatteryAwareServiceManager for optimal battery usage

    // =========================================================================
    // MARK: - Share Intent State
    // =========================================================================

    /** Pending share payload from ACTION_SEND intent */
    private val pendingSharePayload = mutableStateOf<SharePayload?>(null)

    /** Pending conversation to open from notification or deep link */
    private val pendingConversationLaunch = mutableStateOf<ConversationLaunch?>(null)

    /** Pending request to open spam folder from notification */
    private val pendingOpenSpam = mutableStateOf(false)

    /**
     * Data class representing a request to open a specific conversation.
     * Used when launching from notifications or deep links.
     */
    data class ConversationLaunch(
        val threadId: Long,
        val address: String,
        val name: String
    )

    // =========================================================================
    // MARK: - Role Request Launchers
    // =========================================================================
    // Activity result launchers for system role requests. These must be
    // registered at class initialization time, not dynamically.

    /**
     * Launcher for default SMS app role request.
     * When the user grants this role, the app can send/receive SMS directly.
     */
    private val defaultSmsRoleLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            android.util.Log.d("MainActivity", "=== Default SMS launcher callback ===")
            android.util.Log.d("MainActivity", "Result code: ${result.resultCode}")
            val isDefault = DefaultSmsHelper.isDefaultSmsApp(this)
            android.util.Log.d("MainActivity", "Is now default SMS app: $isDefault")
            viewModel.onDefaultSmsAppChanged(isDefault)
        }

    /**
     * Launcher for default dialer role request.
     * When granted, enables direct call handling and caller ID features.
     */
    private val defaultDialerRoleLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            android.util.Log.d("MainActivity", "=== Default Dialer launcher callback ===")
            android.util.Log.d("MainActivity", "Result code: ${result.resultCode}")
            val isDefault = isDefaultDialer()
            android.util.Log.d("MainActivity", "Is now default dialer: $isDefault")
        }

    /**
     * Requests the default SMS app role from the system.
     *
     * On Android 10+, uses RoleManager.ROLE_SMS for the proper system dialog.
     * On older versions, uses the legacy ACTION_CHANGE_DEFAULT intent.
     *
     * When granted, the app can:
     * - Receive SMS/MMS directly
     * - Send SMS without going through the default app
     * - Access the SMS inbox programmatically
     */
    fun requestDefaultSmsAppViaRole() {
        android.util.Log.d("MainActivity", "=== requestDefaultSmsAppViaRole() called ===")
        android.util.Log.d("MainActivity", "Android version: ${Build.VERSION.SDK_INT}")

        val intent =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.util.Log.d("MainActivity", "Using RoleManager (Android 10+)")
                val rm = getSystemService(RoleManager::class.java) as RoleManager
                val isRoleAvailable = rm.isRoleAvailable(RoleManager.ROLE_SMS)
                android.util.Log.d("MainActivity", "SMS Role available: $isRoleAvailable")
                rm.createRequestRoleIntent(RoleManager.ROLE_SMS)
            } else {
                android.util.Log.d("MainActivity", "Using legacy ACTION_CHANGE_DEFAULT")
                Intent("android.telephony.action.CHANGE_DEFAULT").apply {
                    putExtra("android.telephony.extra.CHANGE_DEFAULT_DIALER_PACKAGE_NAME", packageName)
                }
            }

        android.util.Log.d("MainActivity", "Launching intent: $intent")
        defaultSmsRoleLauncher.launch(intent)
        android.util.Log.d("MainActivity", "Intent launched successfully")
    }

    private fun requestDefaultDialerRole() {
        android.util.Log.d("MainActivity", "=== requestDefaultDialerRole() called ===")
        android.util.Log.d("MainActivity", "Android version: ${Build.VERSION.SDK_INT}")

        val intent =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.util.Log.d("MainActivity", "Using RoleManager (Android 10+)")
                val rm = getSystemService(RoleManager::class.java) as RoleManager
                val isRoleAvailable = rm.isRoleAvailable(RoleManager.ROLE_DIALER)
                android.util.Log.d("MainActivity", "Dialer Role available: $isRoleAvailable")
                rm.createRequestRoleIntent(RoleManager.ROLE_DIALER)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.util.Log.d("MainActivity", "Using legacy ACTION_CHANGE_DEFAULT_DIALER")
                Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                    putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
                }
            } else {
                android.util.Log.w("MainActivity", "Default dialer role not supported on this Android version")
                return
            }

        android.util.Log.d("MainActivity", "Launching dialer intent: $intent")
        defaultDialerRoleLauncher.launch(intent)
        android.util.Log.d("MainActivity", "Dialer intent launched successfully")
    }

    private fun isDefaultDialer(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val telecomManager = getSystemService(TelecomManager::class.java) as TelecomManager
        val currentDefault = telecomManager?.defaultDialerPackage
        val isDefault = currentDefault == packageName
        android.util.Log.d("MainActivity", "Current default dialer: $currentDefault, ours: $isDefault")
        return isDefault
    }

    /**
     * Request ROLE_CALL_SCREENING to get phone numbers for incoming calls
     * This allows getting caller info without being the default dialer
     */
    private fun requestCallScreeningRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(RoleManager::class.java) as RoleManager
            if (rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
                if (!rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
                    android.util.Log.d("MainActivity", "Requesting ROLE_CALL_SCREENING")
                    val intent = rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
                    callScreeningRoleLauncher.launch(intent)
                } else {
                    android.util.Log.d("MainActivity", "Already have ROLE_CALL_SCREENING")
                }
            } else {
                android.util.Log.w("MainActivity", "ROLE_CALL_SCREENING not available on this device")
            }
        }
    }

    private val callScreeningRoleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        android.util.Log.d("MainActivity", "Call screening role result: ${result.resultCode}")
        if (result.resultCode == RESULT_OK) {
            android.util.Log.d("MainActivity", "ROLE_CALL_SCREENING granted!")
        }
    }

    /**
     * Request exemption from battery optimization to ensure reliable background operation
     * This allows the app to run in background without being killed by Doze mode
     */
    @android.annotation.SuppressLint("BatteryLife")
    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                android.util.Log.d("MainActivity", "Requesting battery optimization exemption")
                try {
                    val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Failed to request battery optimization exemption", e)
                }
            } else {
                android.util.Log.d("MainActivity", "Already exempt from battery optimization")
            }
        }
    }

    // =========================================================================
    // MARK: - Permissions
    // =========================================================================
    //
    // PERMISSION STRATEGY:
    // --------------------
    // SyncFlow uses a minimal permission approach to reduce antivirus false positives
    // and user friction. Permissions are categorized into:
    //
    // 1. CORE_PERMISSIONS - Required for basic functionality (requested at startup)
    // 2. CALL_PERMISSIONS - For phone call features (requested on demand)
    // 3. MEDIA_PERMISSIONS - For video calls (requested on demand)
    // 4. STORAGE_PERMISSIONS - For file attachments (requested on demand)
    //
    // This approach:
    // - Reduces initial permission requests (less intimidating for users)
    // - Reduces antivirus false positive rates
    // - Provides context when permissions are requested (user understands why)
    // - Allows app to function with reduced features if permissions denied
    //

    /**
     * Core permissions required for basic SMS sync functionality.
     * These are requested at app startup and are essential for the app to work.
     */
    private val CORE_PERMISSIONS = arrayOf(
        Manifest.permission.READ_SMS,           // Read SMS inbox
        Manifest.permission.SEND_SMS,           // Send SMS from desktop
        Manifest.permission.RECEIVE_SMS,        // Receive new messages
        Manifest.permission.READ_CONTACTS,      // Show contact names
        Manifest.permission.READ_CALL_LOG,      // Sync call history
        Manifest.permission.POST_NOTIFICATIONS  // Show sync notifications
    )

    /**
     * Optional permissions for phone call features.
     * Requested when user accesses call-related functionality.
     */
    private val CALL_PERMISSIONS = arrayOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.ANSWER_PHONE_CALLS,
        Manifest.permission.MANAGE_OWN_CALLS,
        Manifest.permission.READ_CALL_LOG
    )

    /**
     * Optional permissions for camera and microphone.
     * Requested when user starts a video call.
     */
    private val MEDIA_PERMISSIONS = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    /**
     * Optional permissions for file access.
     * Uses READ_MEDIA_IMAGES on Android 13+ for scoped storage compliance.
     */
    private val STORAGE_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    // Additional permissions for enhanced features (requested separately)
    private val ENHANCED_PERMISSIONS_GROUP_1 = arrayOf( // Phone & Calls
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_CALL_LOG
    )

    private val ENHANCED_PERMISSIONS_GROUP_2 = arrayOf( // Media & Notifications
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    ).let { permissions ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions + arrayOf(
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.READ_MEDIA_IMAGES
            )
        } else {
            permissions + arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        }
    }

    /** All permissions combined for reference */
    private val ALL_PERMISSIONS = (CORE_PERMISSIONS + ENHANCED_PERMISSIONS_GROUP_1 + ENHANCED_PERMISSIONS_GROUP_2).toMutableList()

    /** Tracks if permissions have been requested this session to avoid repeated prompts */
    private var hasRequestedPermissions = false

    private data class PendingCallPermission(
        val callId: String,
        val callerName: String?,
        val withVideo: Boolean
    )

    private var pendingCallPermissionRequest: PendingCallPermission? = null

    /**
     * Activity result launcher for permission requests.
     * Handles the result of requestPermissions() calls.
     */
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            hasRequestedPermissions = true
            val coreGranted = hasCorePermissions()
            if (!coreGranted) {
                // Only show dialog if CORE permissions are missing
                showPermissionDialog()
            }
            // If core permissions are granted, proceed even if optional ones are denied

            val pendingCall = pendingCallPermissionRequest
            if (pendingCall != null) {
                val hasAudio = hasAudioPermission()
                val hasCamera = hasCameraPermission()
                if (!hasAudio) {
                    android.util.Log.w("MainActivity", "Call permission request denied (audio)")
                    pendingCallPermissionRequest = null
                    return@registerForActivityResult
                }

                val answerWithVideo = pendingCall.withVideo && hasCamera
                if (pendingCall.withVideo && !hasCamera) {
                    android.util.Log.w("MainActivity", "Camera denied, answering audio-only")
                }

                val answerIntent = Intent(this, SyncFlowCallService::class.java).apply {
                    action = SyncFlowCallService.ACTION_ANSWER_CALL
                    putExtra(SyncFlowCallService.EXTRA_CALL_ID, pendingCall.callId)
                    putExtra(SyncFlowCallService.EXTRA_WITH_VIDEO, answerWithVideo)
                }
                startService(answerIntent)
                pendingCallPermissionRequest = null
            }
        }

    // =========================================================================
    // MARK: - Activity Lifecycle
    // =========================================================================

    /**
     * Called when the activity is resumed.
     *
     * Performs:
     * - Auth activity tracking for session management
     * - Default SMS app status check
     * - Deleted message reconciliation
     * - Call service startup (needed for incoming calls in foreground)
     */
    override fun onResume() {
        super.onResume()
        // Track user activity for session management
        authManager.updateActivity()

        viewModel.onDefaultSmsAppChanged(DefaultSmsHelper.isDefaultSmsApp(this))
        viewModel.reconcileDeletedMessages()
        // Ensure call service is running while app is in foreground (needed for incoming calls).
        SyncFlowCallService.startService(this)
    }

    /**
     * Called when the activity is being destroyed.
     *
     * Cleans up:
     * - Broadcast receivers for call events
     * - BatteryAwareServiceManager resources
     *
     * Note: This may be called during configuration changes, so only
     * clean up activity-scoped resources here.
     */
    override fun onDestroy() {
        super.onDestroy()
        // Unregister broadcast receivers
        unregisterCallBroadcastReceivers()

        // Clean up BatteryAwareServiceManager (it handles all service cleanup)
        try {
            BatteryAwareServiceManager.getInstance(applicationContext).cleanup()
            android.util.Log.d("MainActivity", "BatteryAwareServiceManager cleaned up")
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error cleaning up BatteryAwareServiceManager", e)
        }
    }

    /**
     * Registers broadcast receivers for call-related events.
     *
     * Two receivers are registered:
     * 1. DISMISS_INCOMING_CALL - When an incoming call should be dismissed
     *    (e.g., answered on phone, cancelled by caller)
     * 2. CALL_ENDED_BY_REMOTE - When the remote party ends an active call
     *
     * These receivers update the Compose state via trigger variables,
     * causing the UI to update appropriately.
     */
    private fun registerCallBroadcastReceivers() {
        // Receiver for dismissing incoming call UI
        callDismissReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val callId = intent?.getStringExtra("call_id")
                val reason = intent?.getStringExtra("reason") ?: "unknown"
                android.util.Log.d("MainActivity", "📞 BROADCAST: Dismiss incoming call - callId=$callId, reason=$reason")
                _dismissCallTrigger.value++
            }
        }
        val dismissFilter = android.content.IntentFilter("com.phoneintegration.app.DISMISS_INCOMING_CALL")
        ContextCompat.registerReceiver(
            this,
            callDismissReceiver,
            dismissFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        android.util.Log.d("MainActivity", "📞 Registered DISMISS_INCOMING_CALL receiver")

        // Receiver for call ended by remote
        callEndedReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val callId = intent?.getStringExtra("call_id")
                android.util.Log.d("MainActivity", "📞 BROADCAST: Call ended by remote - callId=$callId")
                _endCallTrigger.value++
            }
        }
        val endedFilter = android.content.IntentFilter("com.phoneintegration.app.CALL_ENDED_BY_REMOTE")
        ContextCompat.registerReceiver(
            this,
            callEndedReceiver,
            endedFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        android.util.Log.d("MainActivity", "📞 Registered CALL_ENDED_BY_REMOTE receiver")
    }

    private fun unregisterCallBroadcastReceivers() {
        try {
            callDismissReceiver?.let { unregisterReceiver(it) }
            callEndedReceiver?.let { unregisterReceiver(it) }
            android.util.Log.d("MainActivity", "📞 Unregistered call broadcast receivers")
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error unregistering call receivers", e)
        }
    }

    /**
     * Called when the activity is first created.
     *
     * This method performs extensive initialization in a carefully ordered sequence:
     *
     * ## Essential Setup (Main Thread, Blocking)
     * - PreferencesManager, AuthManager, RecoveryCodeManager
     * - Permission checking
     * - Intent handling for calls/shares
     * - Broadcast receiver registration
     *
     * ## Background Initialization (IO Dispatcher, Non-Blocking)
     * - Signal Protocol for E2EE
     * - FCM token registration
     * - MobileAds initialization
     * - Role requests (default dialer, call screening)
     * - Battery optimization exemption
     * - WorkManager scheduling
     *
     * ## UI Composition
     * Sets up Jetpack Compose UI with:
     * - Theme based on user preference
     * - Main navigation
     * - Call screen overlays (incoming/active)
     *
     * ## Performance Optimizations
     * - Background init is deferred to avoid blocking UI render
     * - Sync operations delayed 3 seconds after UI is up
     * - Services managed by BatteryAwareServiceManager
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            val startTime = System.currentTimeMillis()
            android.util.Log.d("MainActivity", "=== onCreate START ===")

            // Essential setup only - must be on main thread
            preferencesManager = PreferencesManager(this)
            authManager = AuthManager.getInstance(this)
            recoveryCodeManager = RecoveryCodeManager.getInstance(this)

            // Only start OutgoingMessageService if user has paired devices
        // This prevents Firebase listener downloads on fresh install
        if (preferencesManager.backgroundSyncEnabled.value &&
            com.phoneintegration.app.desktop.DesktopSyncService.hasPairedDevices(applicationContext)) {
            com.phoneintegration.app.desktop.OutgoingMessageService.start(applicationContext)
        }

        // Account recovery is now opt-in via Settings > Account Recovery
        // Removing auto-check prevents Cloud Function call on every fresh install
        // checkForAccountRecovery()

        // Request permissions if core ones are missing (non-blocking)
        if (!hasCorePermissions()) {
            requestCorePermissions()
        }

        // CallMonitorService is now started on-demand to save battery:
        // - Incoming calls: Started by SyncFlowCallScreeningService
        // - Outgoing calls from Mac/Web: Started by FCM notification (make_phone_call)
        // - Auto-stops after call ends
        android.util.Log.d("MainActivity", "CallMonitorService will start on-demand (battery optimization)")

        // Handle incoming/active call intent immediately
        // Only enable lock screen override if this is an incoming call
        val isIncomingCall = intent?.hasExtra("incoming_syncflow_call_id") == true ||
                intent?.hasExtra("active_syncflow_call") == true ||
                intent?.hasExtra("syncflow_call_action") == true
        if (isIncomingCall) {
            enableCallScreenOverLockScreen()
        }
        handleCallIntent(intent)
        handleShareIntent(intent)?.let { pendingSharePayload.value = it }
        handleConversationIntent(intent)?.let { pendingConversationLaunch.value = it }
        if (intent?.getBooleanExtra("open_spam", false) == true) {
            pendingOpenSpam.value = true
        }

        // Register broadcast receivers for call dismissal (at Activity level for reliability)
        registerCallBroadcastReceivers()

        android.util.Log.d("MainActivity", "Essential setup done in ${System.currentTimeMillis() - startTime}ms")

        // Defer ALL heavy initializations to background using lifecycle-aware scope
        // Use applicationContext to prevent Activity memory leaks
        val appContext = applicationContext
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val bgStartTime = System.currentTimeMillis()

            // Authenticate with VPS on startup (ensures userId is available for UI)
            try {
                val unifiedIdentityManager = com.phoneintegration.app.auth.UnifiedIdentityManager.getInstance(appContext)
                val userId = unifiedIdentityManager.getUnifiedUserId()
                android.util.Log.d("MainActivity", "VPS authenticated on startup: userId=$userId")
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "VPS startup auth failed: ${e.message}")
            }

            // Only initialize E2EE and register FCM token if user has paired devices
            // This prevents Firebase writes/reads on fresh install
            val hasPairedDevices = com.phoneintegration.app.desktop.DesktopSyncService.hasPairedDevices(appContext)

            if (hasPairedDevices) {
                // Initialize Signal Protocol Manager for E2EE (can be slow)
                try {
                    val signalProtocolManager = SignalProtocolManager(appContext)
                    signalProtocolManager.initializeKeys()
                    android.util.Log.d("MainActivity", "Signal Protocol initialized")
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Error initializing Signal Protocol", e)
                }

                // VPS handles device registration - no FCM token needed
                android.util.Log.d("MainActivity", "VPS mode - skipping FCM token registration")
            } else {
                android.util.Log.d("MainActivity", "Skipping E2EE and FCM init - no paired devices yet")
            }

            // Initialize MobileAds (slow network call)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                MobileAds.initialize(this@MainActivity)
            }

            // Request default dialer role (if not already)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (!isDefaultDialer()) {
                    // Delay to avoid UI interruption during app launch
                    kotlinx.coroutines.delay(1500)
                    requestDefaultDialerRole()
                }
            }

            // Request call screening role to get phone numbers for incoming calls
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                kotlinx.coroutines.delay(500)
                requestCallScreeningRole()
            }

            // Request battery optimization exemption for reliable background operation
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                kotlinx.coroutines.delay(500)
                requestBatteryOptimizationExemption()
            }

            // Schedule background workers (low priority) - use appContext
            // Workers will check hasPairedDevices() internally before doing work
            com.phoneintegration.app.desktop.SmsSyncWorker.schedule(appContext)
            com.phoneintegration.app.desktop.ContactsSyncWorker.schedule(appContext)
            com.phoneintegration.app.desktop.CallHistorySyncWorker.schedule(appContext)

            android.util.Log.d("MainActivity", "Background init done in ${System.currentTimeMillis() - bgStartTime}ms")

            // Restore phone registration from VPS if missing locally (e.g., after reinstall)
            try {
                com.phoneintegration.app.ui.components.restorePhoneRegistrationFromVPS(appContext)
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Failed to restore phone registration", e)
            }

            // Only trigger immediate sync if user has paired devices
            // This prevents unnecessary Cloud Function calls on fresh install
            if (com.phoneintegration.app.desktop.DesktopSyncService.hasPairedDevices(appContext)) {
                // Delay sync operations to avoid competing with UI
                kotlinx.coroutines.delay(3000)

                // Trigger sync (very low priority - after UI is responsive)
                com.phoneintegration.app.desktop.ContactsSyncWorker.syncNow(appContext)
                com.phoneintegration.app.desktop.CallHistorySyncWorker.syncNow(appContext)
            } else {
                android.util.Log.d("MainActivity", "Skipping immediate sync - no paired devices yet")
            }
        }

        // Start services with slight delay (avoid blocking UI)
        // Use BatteryAwareServiceManager for intelligent service management
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            kotlinx.coroutines.delay(500) // Let UI render first

            try {
                // Initialize battery-aware service manager
                val serviceManager = BatteryAwareServiceManager.getInstance(appContext)
                android.util.Log.d("MainActivity", "BatteryAwareServiceManager initialized - services will start intelligently based on battery and conditions")

                // Note: SyncFlowCallService and OutgoingMessageService are NOT started here.
                // They are started on-demand via FCM push notifications when:
                // - An incoming video call arrives (triggers FCM -> starts SyncFlowCallService)
                // - A message needs to be sent from desktop (triggers FCM -> starts OutgoingMessageService)
                // This eliminates the persistent "Ready to receive calls" notification.

                // Services are now managed by BatteryAwareServiceManager based on:
                // - Battery level and charging status
                // - Network conditions (WiFi vs mobile data)
                // - App lifecycle (foreground/background)
                // - User preferences
                // This should reduce battery usage by 50-70%

                android.util.Log.i("MainActivity", "Service management delegated to BatteryAwareServiceManager")

            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error initializing BatteryAwareServiceManager", e)
            }

            // Services are now managed by BatteryAwareServiceManager
            // No manual service starting needed - BatteryAwareServiceManager handles this intelligently
        }

        android.util.Log.d("MainActivity", "=== onCreate UI setup starting at ${System.currentTimeMillis() - startTime}ms ===")

        setContent {
            val systemInDarkTheme = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            val isDarkTheme by remember {
                derivedStateOf {
                    try {
                        if (preferencesManager.isAutoTheme.value) systemInDarkTheme
                        else preferencesManager.isDarkMode.value
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "Error getting theme preferences", e)
                        false // Default to light theme
                    }
                }
            }

            // SyncFlow call state - poll for call manager since it might not be available immediately
            var callManager by remember { mutableStateOf(SyncFlowCallService.getCallManager()) }

            // Keep checking for call manager if it's null
            LaunchedEffect(callManager) {
                if (callManager == null) {
                    while (callManager == null) {
                        kotlinx.coroutines.delay(200)
                        callManager = SyncFlowCallService.getCallManager()
                    }
                    android.util.Log.d("MainActivity", "CallManager became available")
                }
            }

            val callState by callManager?.callState?.collectAsState() ?: remember { mutableStateOf(null) }
            val currentCall by callManager?.currentCall?.collectAsState() ?: remember { mutableStateOf(null) }

            // Observe the service's pending incoming call flow directly (more reliable than broadcasts)
            val servicePendingCall by SyncFlowCallService.pendingIncomingCallFlow.collectAsState()

            // Incoming call state
            var incomingCallId by remember { mutableStateOf<String?>(null) }
            var incomingCallerName by remember { mutableStateOf<String?>(null) }
            var incomingIsVideo by remember { mutableStateOf(false) }

            // Active call state (when answering from notification)
            var showActiveCallScreen by remember { mutableStateOf(false) }

            // Track if we launched from a locked screen - used to finish activity when call ends
            var wasLaunchedFromLockedScreen by remember { mutableStateOf(false) }

            // Check if device is currently locked
            LaunchedEffect(Unit) {
                val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                wasLaunchedFromLockedScreen = keyguardManager.isKeyguardLocked
                if (wasLaunchedFromLockedScreen) {
                    android.util.Log.d("MainActivity", "App launched from locked screen")
                }
            }

            // React to service's pending incoming call flow changes (most reliable method)
            LaunchedEffect(servicePendingCall) {
                android.util.Log.d("MainActivity", "📞 servicePendingCall changed: ${servicePendingCall?.id}")
                if (servicePendingCall != null && incomingCallId == null && !showActiveCallScreen) {
                    // Service has a pending call that UI doesn't know about.
                    // This happens when the activity is recreated after unlock,
                    // or when the intent extras are lost.
                    android.util.Log.d("MainActivity", "📞 Restoring incoming call UI from service flow: ${servicePendingCall!!.id}")
                    incomingCallId = servicePendingCall!!.id
                    incomingCallerName = servicePendingCall!!.callerName
                    incomingIsVideo = servicePendingCall!!.isVideo
                    enableCallScreenOverLockScreen()
                } else if (servicePendingCall == null && incomingCallId != null) {
                    // Service says no incoming call, but we're showing one - dismiss it
                    android.util.Log.d("MainActivity", "📞 Service has no pending call, dismissing incoming call UI")
                    incomingCallId = null
                    incomingCallerName = null
                    incomingIsVideo = false
                    disableCallScreenOverLockScreen()

                    // If we were launched from locked screen just for this call, finish the activity
                    if (wasLaunchedFromLockedScreen) {
                        android.util.Log.d("MainActivity", "📞 Was launched from locked screen, finishing activity")
                        finish()
                    }
                }
            }

            // React to dismiss call trigger from broadcast receiver (registered at Activity level)
            val dismissTrigger by _dismissCallTrigger
            LaunchedEffect(dismissTrigger) {
                if (dismissTrigger > 0) {
                    android.util.Log.d("MainActivity", "📞 Dismiss trigger received, clearing incoming call UI")
                    incomingCallId = null
                    incomingCallerName = null
                    incomingIsVideo = false

                    // If we were launched from locked screen just for this call, finish the activity
                    if (wasLaunchedFromLockedScreen) {
                        android.util.Log.d("MainActivity", "📞 Was launched from locked screen, finishing activity")
                        finish()
                    }
                }
            }

            // React to call ended trigger from broadcast receiver (registered at Activity level)
            val endCallTrigger by _endCallTrigger
            LaunchedEffect(endCallTrigger) {
                if (endCallTrigger > 0) {
                    android.util.Log.d("MainActivity", "📞 End call trigger received, clearing all call UI")
                    showActiveCallScreen = false
                    incomingCallId = null
                    incomingCallerName = null
                    incomingIsVideo = false

                    // If we were launched from locked screen just for this call, finish the activity
                    if (wasLaunchedFromLockedScreen) {
                        android.util.Log.d("MainActivity", "📞 Was launched from locked screen, finishing activity")
                        finish()
                    }
                }
            }

            // Observe the trigger for active calls from onNewIntent
            val activeCallTrigger by _activeCallTrigger
            val incomingCallTrigger by _incomingCallTrigger

            // Also check activeCallTrigger to refresh callManager
            LaunchedEffect(activeCallTrigger, incomingCallTrigger) {
                if (callManager == null) {
                    callManager = SyncFlowCallService.getCallManager()
                }
            }

            // Check for incoming call or active call from intent (initial launch)
            LaunchedEffect(Unit) {
                // Check for incoming call (unanswered)
                val callId = intent.getStringExtra("incoming_syncflow_call_id")
                val callerName = intent.getStringExtra("incoming_syncflow_call_name")
                val isVideo = intent.getBooleanExtra("incoming_syncflow_call_video", false)

                if (callId != null) {
                    android.util.Log.d("MainActivity", "Incoming SyncFlow call: $callId from $callerName")
                    incomingCallId = callId
                    incomingCallerName = callerName
                    incomingIsVideo = isVideo
                }

                // Check for active call (already answered from notification)
                val isActiveCall = intent.getBooleanExtra("active_syncflow_call", false)
                if (isActiveCall) {
                    android.util.Log.d("MainActivity", "Active SyncFlow call - showing call screen")
                    showActiveCallScreen = true
                }
            }

            // React to active call trigger from onNewIntent
            LaunchedEffect(activeCallTrigger) {
                if (_pendingActiveCallId != null) {
                    android.util.Log.d("MainActivity", "Active call triggered from onNewIntent: $_pendingActiveCallId")
                    showActiveCallScreen = true
                    incomingCallId = null // Clear any incoming call UI
                    _pendingActiveCallId = null
                }
            }

            // React to incoming call trigger from onNewIntent
            LaunchedEffect(incomingCallTrigger) {
                if (_pendingIncomingCallId != null) {
                    android.util.Log.d("MainActivity", "Incoming call triggered from onNewIntent: $_pendingIncomingCallId")
                    incomingCallId = _pendingIncomingCallId
                    incomingCallerName = _pendingIncomingCallName
                    incomingIsVideo = _pendingIncomingCallVideo
                    showActiveCallScreen = false
                    _pendingIncomingCallId = null
                }
            }

            // Also watch for call state changes to show/hide call screen
            LaunchedEffect(callState) {
                when (callState) {
                    is SyncFlowCallManager.CallState.Connected,
                    is SyncFlowCallManager.CallState.Connecting -> {
                        showActiveCallScreen = true
                        incomingCallId = null // Clear incoming call UI
                    }
                    is SyncFlowCallManager.CallState.Ended,
                    is SyncFlowCallManager.CallState.Failed -> {
                        showActiveCallScreen = false
                        disableCallScreenOverLockScreen()
                        // If we launched from a locked screen, finish the activity
                        // so user returns to lock screen instead of seeing messages
                        if (wasLaunchedFromLockedScreen) {
                            android.util.Log.d("MainActivity", "Call ended on locked screen - finishing activity")
                            // Small delay to let the UI update before finishing
                            delay(500)
                            this@MainActivity.finish()
                        }
                    }
                    is SyncFlowCallManager.CallState.Idle -> {
                        showActiveCallScreen = false
                        disableCallScreenOverLockScreen()
                    }
                    else -> {}
                }
            }

            // Poll for call manager when showing active call screen but manager is null
            // This handles race condition when answering from locked screen
            LaunchedEffect(showActiveCallScreen) {
                if (showActiveCallScreen && callManager == null) {
                    android.util.Log.d("MainActivity", "Polling for call manager (answering from locked screen)")
                    repeat(20) { attempt ->
                        callManager = SyncFlowCallService.getCallManager()
                        if (callManager != null) {
                            android.util.Log.d("MainActivity", "Call manager found on attempt $attempt")
                            return@LaunchedEffect
                        }
                        delay(100)
                    }
                    android.util.Log.w("MainActivity", "Failed to get call manager after 20 attempts")
                }
            }

            val coroutineScope = rememberCoroutineScope()

            PhoneIntegrationTheme(darkTheme = isDarkTheme) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    // No recovery code screen on startup - it will be shown when user tries to pair
                    // This allows Android-only users to use the app without friction
                        Box(modifier = Modifier.fillMaxSize()) {
                            MainNavigation(
                                viewModel = viewModel,
                                preferencesManager = preferencesManager,
                                pendingShare = pendingSharePayload.value,
                                onShareHandled = { pendingSharePayload.value = null },
                                pendingConversation = pendingConversationLaunch.value,
                                onConversationHandled = { pendingConversationLaunch.value = null },
                                pendingOpenSpam = pendingOpenSpam.value,
                                onSpamHandled = { pendingOpenSpam.value = false }
                            )

                        // Show incoming call screen
                        if (incomingCallId != null && callState != SyncFlowCallManager.CallState.Connected) {
                            IncomingSyncFlowCallScreen(
                                callerName = incomingCallerName ?: "Unknown",
                                isVideo = incomingIsVideo,
                                onAcceptVideo = {
                                    // Use the service to answer the call - this ensures ringtone stops
                                    val answerIntent = Intent(this@MainActivity, SyncFlowCallService::class.java).apply {
                                        action = SyncFlowCallService.ACTION_ANSWER_CALL
                                        putExtra(SyncFlowCallService.EXTRA_CALL_ID, incomingCallId)
                                        putExtra(SyncFlowCallService.EXTRA_WITH_VIDEO, true)
                                    }
                                    startService(answerIntent)
                                    incomingCallId = null
                                    showActiveCallScreen = true
                                },
                                onAcceptAudio = {
                                    // Use the service to answer the call - this ensures ringtone stops
                                    val answerIntent = Intent(this@MainActivity, SyncFlowCallService::class.java).apply {
                                        action = SyncFlowCallService.ACTION_ANSWER_CALL
                                        putExtra(SyncFlowCallService.EXTRA_CALL_ID, incomingCallId)
                                        putExtra(SyncFlowCallService.EXTRA_WITH_VIDEO, false)
                                    }
                                    startService(answerIntent)
                                    incomingCallId = null
                                    showActiveCallScreen = true
                                },
                                onDecline = {
                                    // Use the service to reject the call - this ensures ringtone stops
                                    val rejectIntent = Intent(this@MainActivity, SyncFlowCallService::class.java).apply {
                                        action = SyncFlowCallService.ACTION_REJECT_CALL
                                        putExtra(SyncFlowCallService.EXTRA_CALL_ID, incomingCallId)
                                    }
                                    startService(rejectIntent)
                                    incomingCallId = null
                                    // If we launched from a locked screen, finish the activity
                                    if (wasLaunchedFromLockedScreen) {
                                        android.util.Log.d("MainActivity", "Call declined on locked screen - finishing activity")
                                        this@MainActivity.finish()
                                    }
                                }
                            )
                        }

                        // Show active call screen
                        // Note: Don't gate on showActiveCallScreen || currentCall because the callee
                        // never sets _currentCall, and showActiveCallScreen resets on activity recreation.
                        // Instead, trust callState as the authoritative source.
                        val currentCallManager = callManager
                        if (currentCallManager != null &&
                            (callState == SyncFlowCallManager.CallState.Connected ||
                             callState == SyncFlowCallManager.CallState.Connecting ||
                             callState == SyncFlowCallManager.CallState.Ringing)) {
                            SyncFlowCallScreen(
                                callManager = currentCallManager,
                                onCallEnded = {
                                    android.util.Log.d("MainActivity", "Call ended from SyncFlowCallScreen")
                                    showActiveCallScreen = false
                                    // If we launched from a locked screen, finish the activity
                                    if (wasLaunchedFromLockedScreen) {
                                        android.util.Log.d("MainActivity", "Finishing activity (was locked screen)")
                                        this@MainActivity.finish()
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Critical error during MainActivity initialization", e)
            // Show error dialog or finish activity
            runOnUiThread {
                try {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("App Initialization Error")
                        .setMessage("Failed to initialize the app. Please restart.\n\nError: ${e.message}")
                        .setCancelable(false)
                        .setPositiveButton("Restart") { _, _ ->
                            val intent = packageManager.getLaunchIntentForPackage(packageName)
                            intent?.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            startActivity(intent)
                            finish()
                        }
                        .setNegativeButton("Close") { _, _ ->
                            finish()
                        }
                        .show()
                } catch (dialogError: Exception) {
                    android.util.Log.e("MainActivity", "Failed to show error dialog", dialogError)
                    finish()
                }
            }
        }
    }

    /**
     * Called when a new intent is delivered to an existing activity instance.
     *
     * This handles:
     * - Incoming call intents from FCM notifications
     * - Active call intents (when answering from notification)
     * - Share intents when app is already running
     * - Conversation launch intents from notifications
     *
     * For call intents, also enables lock screen override if needed.
     *
     * @param intent The new intent that was started
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // Update the intent so getIntent() returns the new one

        // Enable lock screen override only for incoming calls
        val isIncomingCall = intent.hasExtra("incoming_syncflow_call_id") ||
                intent.getBooleanExtra("active_syncflow_call", false) ||
                intent.hasExtra("syncflow_call_action")
        if (isIncomingCall) {
            enableCallScreenOverLockScreen()
        }

        handleCallIntent(intent)
        handleShareIntent(intent)?.let { pendingSharePayload.value = it }
        handleConversationIntent(intent)?.let { pendingConversationLaunch.value = it }
        if (intent.getBooleanExtra("open_spam", false)) {
            pendingOpenSpam.value = true
        }
    }

    private fun handleConversationIntent(intent: Intent?): ConversationLaunch? {
        if (intent == null) return null
        val address = intent.getStringExtra("open_address") ?: return null
        val name = intent.getStringExtra("open_name") ?: address
        val threadId = intent.getLongExtra("open_thread_id", 0L)
        return ConversationLaunch(threadId, address, name)
    }

    private fun handleCallIntent(intent: Intent?) {
        if (intent == null) return

        val action = intent.getStringExtra("syncflow_call_action")
        if (action == "answer") {
            val callId = intent.getStringExtra("incoming_syncflow_call_id")
            val callerName = intent.getStringExtra("incoming_syncflow_call_name")
            val withVideo = intent.getBooleanExtra("syncflow_call_answer_video", true)
            val dismissKeyguard = intent.getBooleanExtra("dismiss_keyguard", false)

            Log.d("MainActivity", "Answer action: callId=$callId, dismissKeyguard=$dismissKeyguard")

            // Enable lock screen override when answering from notification
            if (dismissKeyguard) {
                enableCallScreenOverLockScreen()
            }

            if (callId != null) {
                if (!hasMediaPermissions(withVideo)) {
                    pendingCallPermissionRequest = PendingCallPermission(callId, callerName, withVideo)
                    requestCallMediaPermissions(withVideo)
                    return
                }

                val answerIntent = Intent(this, SyncFlowCallService::class.java).apply {
                    this.action = SyncFlowCallService.ACTION_ANSWER_CALL
                    putExtra(SyncFlowCallService.EXTRA_CALL_ID, callId)
                    putExtra(SyncFlowCallService.EXTRA_WITH_VIDEO, withVideo)
                }
                startService(answerIntent)

                _pendingActiveCallId = callId
                _pendingActiveCallName = callerName
                _pendingActiveCallVideo = withVideo
                _activeCallTrigger.value++
            }
            return
        }

        if (action == "request_permissions") {
            val callId = intent.getStringExtra("incoming_syncflow_call_id")
            val callerName = intent.getStringExtra("incoming_syncflow_call_name")
            val withVideo = intent.getBooleanExtra("syncflow_call_answer_video", true)
            if (callId != null) {
                if (hasMediaPermissions(withVideo)) {
                    val answerIntent = Intent(this, SyncFlowCallService::class.java).apply {
                        this.action = SyncFlowCallService.ACTION_ANSWER_CALL
                        putExtra(SyncFlowCallService.EXTRA_CALL_ID, callId)
                        putExtra(SyncFlowCallService.EXTRA_WITH_VIDEO, withVideo)
                    }
                    startService(answerIntent)
                } else {
                    pendingCallPermissionRequest = PendingCallPermission(callId, callerName, withVideo)
                    requestCallMediaPermissions(withVideo)
                }
            }
            return
        }

        // Handle active call (answered from notification or service)
        val isActiveCall = intent.getBooleanExtra("active_syncflow_call", false)
        if (isActiveCall) {
            val callId = intent.getStringExtra("active_syncflow_call_id")
            val callerName = intent.getStringExtra("active_syncflow_call_name")
            val isVideo = intent.getBooleanExtra("active_syncflow_call_video", false)
            val dismissKeyguard = intent.getBooleanExtra("dismiss_keyguard", false)

            android.util.Log.d("MainActivity", "Handling active SyncFlow call: $callId from $callerName, dismissKeyguard: $dismissKeyguard")

            // Enable lock screen override for active calls
            enableCallScreenOverLockScreen()

            _pendingActiveCallId = callId
            _pendingActiveCallName = callerName
            _pendingActiveCallVideo = isVideo
            _activeCallTrigger.value++ // Trigger recomposition
            return
        }

        // Handle incoming call (unanswered)
        val callId = intent.getStringExtra("incoming_syncflow_call_id")
        if (callId != null) {
            val callerName = intent.getStringExtra("incoming_syncflow_call_name")
            val isVideo = intent.getBooleanExtra("incoming_syncflow_call_video", false)
            android.util.Log.d("MainActivity", "Handling incoming SyncFlow call intent: $callId")
            _pendingIncomingCallId = callId
            _pendingIncomingCallName = callerName
            _pendingIncomingCallVideo = isVideo
            _incomingCallTrigger.value++
        }
    }

    // =========================================================================
    // MARK: - Permission Helpers
    // =========================================================================

    /** Checks if all core permissions are granted */
    private fun hasCorePermissions(): Boolean =
        CORE_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun hasMediaPermissions(withVideo: Boolean): Boolean =
        hasAudioPermission() && (!withVideo || hasCameraPermission())

    /** Checks if all permissions (core + enhanced) are granted */
    private fun hasAllPermissions(): Boolean =
        ALL_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    /** Requests only core permissions at startup (reduces antivirus false positives) */
    private fun requestCorePermissions() {
        if (!hasRequestedPermissions) {
            permissionLauncher.launch(CORE_PERMISSIONS)
        }
    }

    /** Requests call-related permissions when call features are accessed */
    private fun requestCallPermissions() {
        if (CALL_PERMISSIONS.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            permissionLauncher.launch(CALL_PERMISSIONS)
        }
    }

    /** Requests camera/microphone permissions when video call features are accessed */
    private fun requestMediaPermissions() {
        if (MEDIA_PERMISSIONS.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            permissionLauncher.launch(MEDIA_PERMISSIONS)
        }
    }

    private fun requestCallMediaPermissions(withVideo: Boolean) {
        val permissions = if (withVideo) MEDIA_PERMISSIONS else arrayOf(Manifest.permission.RECORD_AUDIO)
        permissionLauncher.launch(permissions)
    }

    /**
     * Enables the call screen to display over the lock screen.
     *
     * This is essential for incoming calls to be visible when the device is locked.
     * Uses different APIs based on Android version:
     * - API 27+: setShowWhenLocked() and setTurnScreenOn()
     * - Older: Window flags FLAG_SHOW_WHEN_LOCKED, FLAG_TURN_SCREEN_ON
     *
     * Also requests keyguard dismissal to allow the user to interact with the call
     * without unlocking (for answering/declining).
     */
    private fun enableCallScreenOverLockScreen() {
        Log.d("MainActivity", "Enabling call screen over lock screen")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            // Show over lock screen WITHOUT dismissing the keyguard
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            Log.d("MainActivity", "Used setShowWhenLocked/setTurnScreenOn")
        } else {
            // Older API: show over lock screen without FLAG_DISMISS_KEYGUARD
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
            Log.d("MainActivity", "Used window flags for lock screen override")
        }

        // Do NOT call requestDismissKeyguard() here — the call screen (answer/decline)
        // should display over the lock screen. The keyguard is only dismissed if the
        // user answers and needs full app access.

        // Keep screen on during call
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun disableCallScreenOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(false)
            setTurnScreenOn(false)
        } else {
            @Suppress("DEPRECATION")
            window.clearFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Requests storage permissions when file attachment features are accessed */
    private fun requestStoragePermissions() {
        if (STORAGE_PERMISSIONS.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            permissionLauncher.launch(STORAGE_PERMISSIONS)
        }
    }

    private fun showPermissionDialog() {
        // Don't show dialog if we've already asked and user denied
        if (hasRequestedPermissions) {
            // Check if user has permanently denied - open settings instead
            val shouldShowRationale = CORE_PERMISSIONS.any {
                shouldShowRequestPermissionRationale(it)
            }

            if (!shouldShowRationale && !hasCorePermissions()) {
                // Permissions permanently denied, direct to settings
                android.app.AlertDialog.Builder(this)
                    .setTitle("🔒 Enable Permissions for Full Functionality")
                    .setMessage("""
                        SyncFlow requires SMS and Contacts permissions to sync messages with your desktop.

                        These permissions are standard for messaging apps like WhatsApp or Signal.

                        If antivirus software flagged this request, it's a false positive - SyncFlow is legitimate and secure.
                    """.trimIndent())
                    .setCancelable(true)
                    .setPositiveButton("Open Settings") { _, _ ->
                        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        intent.data = Uri.fromParts("package", packageName, null)
                        startActivity(intent)
                    }
                    .setNegativeButton("Continue Limited") { dialog, _ -> dialog.dismiss() }
                    .show()
            }
            return
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("📱 Enable SMS Sync")
            .setMessage("""
                SyncFlow needs minimal permissions to sync your SMS messages with your desktop:

                📱 SMS Access - Read and send your text messages
                👥 Contacts - Show contact names instead of phone numbers
                🔔 Notifications - Show sync status notifications

                🔒 Privacy Focused:
                • Only requests essential permissions for SMS sync
                • No camera, microphone, or phone call access initially
                • Additional permissions requested only when using specific features
                • Open-source and transparent about data usage

                This minimal approach reduces false antivirus warnings while maintaining full functionality.
            """.trimIndent())
            .setCancelable(true)
            .setPositiveButton("Enable SMS Sync") { _, _ ->
                hasRequestedPermissions = false
                requestCorePermissions()
            }
            .setNegativeButton("Later") { dialog, _ -> dialog.dismiss() }
            .setNeutralButton("Learn More") { _, _ ->
                showDetailedPermissionInfo()
            }
            .show()
    }

    private fun showDetailedPermissionInfo() {
        android.app.AlertDialog.Builder(this)
            .setTitle("🔒 SyncFlow Permission Strategy")
            .setMessage("""
                SyncFlow uses a minimal permission approach to reduce security concerns while maintaining full functionality:

                📱 CORE PERMISSIONS (Required for SMS Sync):
                • SMS Access - Read/send messages, sync with desktop
                • Contacts - Show names instead of phone numbers
                • Notifications - Show sync status updates

                📞 OPTIONAL PERMISSIONS (Requested when needed):
                • Phone/Call permissions - Only when using call features
                • Camera/Microphone - Only when starting video calls
                • Storage access - Only when attaching files to MMS

                🛡️ SECURITY MEASURES:
                • Minimal initial permissions reduce antivirus false positives
                • Additional permissions requested contextually
                • All data encrypted end-to-end
                • Open source for transparency
                • No data collection or sharing

                This approach balances functionality with security and user trust.
            """.trimIndent())
            .setCancelable(true)
            .setPositiveButton("Got It") { dialog, _ ->
                showPermissionDialog()
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    // =========================================================================
    // MARK: - Account Recovery
    // =========================================================================

    /**
     * Check if this device has a previous SyncFlow account.
     * Called on app launch to enable seamless recovery after reinstall.
     */
    private fun checkForAccountRecovery() {
        // Check if user already has an account (skip recovery)
        val existingUserId = UnifiedIdentityManager.getInstance(this).getUnifiedUserIdSync()
        if (existingUserId != null) {
            android.util.Log.d("MainActivity", "User already has account: $existingUserId - skipping recovery check")
            return
        }

        // Check for existing account by device fingerprint
        lifecycleScope.launch {
            try {
                val identityManager = UnifiedIdentityManager.getInstance(this@MainActivity)
                val existingAccount = identityManager.checkForExistingAccount()

                if (existingAccount != null) {
                    android.util.Log.d("MainActivity", "Found existing account: ${existingAccount.userId}")
                    // Show recovery dialog on main thread
                    runOnUiThread {
                        showAccountRecoveryDialog(existingAccount)
                    }
                } else {
                    android.util.Log.d("MainActivity", "No existing account found for this device")
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Failed to check for existing account", e)
            }
        }
    }

    /**
     * Show dialog asking user if they want to restore their previous account.
     */
    private fun showAccountRecoveryDialog(account: com.phoneintegration.app.auth.ExistingAccountInfo) {
        val deviceNames = (account.devices as? Map<*, *>)?.values
            ?.mapNotNull { it as? Map<*, *> }
            ?.mapNotNull { it["name"] as? String }
            ?.take(3)
            ?.joinToString(", ") ?: "Unknown devices"

        val lastActivityDate = if (account.lastActivity > 0) {
            java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault())
                .format(java.util.Date(account.lastActivity))
        } else {
            "Unknown"
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("Welcome Back to SyncFlow!")
            .setMessage("""
                Found your previous SyncFlow account on this device:

                • Paired devices: ${account.deviceCount} ($deviceNames)
                • Messages: ${account.messageCount}
                • Last used: $lastActivityDate

                Would you like to restore your account?

                Note: Restoring will unpair all old devices for security. You can re-pair them afterward.
            """.trimIndent())
            .setPositiveButton("Restore Account") { _, _ ->
                restoreAccount(account.userId)
            }
            .setNegativeButton("Start Fresh") { _, _ ->
                android.util.Log.d("MainActivity", "User declined account recovery - creating new account")
                android.widget.Toast.makeText(
                    this,
                    "Starting fresh with a new account",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Restore the user's previous account.
     */
    private fun restoreAccount(userId: String) {
        lifecycleScope.launch {
            try {
                val identityManager = UnifiedIdentityManager.getInstance(this@MainActivity)

                // Generate device ID
                val deviceId = "android_${android.os.Build.DEVICE}_${System.currentTimeMillis()}"
                val deviceName = android.os.Build.MODEL ?: "Android Device"

                android.util.Log.d("MainActivity", "Restoring account $userId with device $deviceId")

                // Restore account and unpair old devices
                val result = identityManager.restoreAccountAndUnpairDevices(
                    userId = userId,
                    newDeviceId = deviceId,
                    deviceName = deviceName,
                    platform = "android"
                )

                if (result.isSuccess) {
                    val devicesUnpaired = result.getOrNull() ?: 0
                    android.util.Log.d("MainActivity", "Account restored successfully. Unpaired $devicesUnpaired old devices")

                    // Store device fingerprint for future recovery
                    identityManager.storeDeviceFingerprint(userId)

                    runOnUiThread {
                        android.widget.Toast.makeText(
                            this@MainActivity,
                            "Account restored! Unpaired $devicesUnpaired old device(s) for security.",
                            android.widget.Toast.LENGTH_LONG
                        ).show()

                        // Refresh UI
                        recreate()
                    }
                } else {
                    throw result.exceptionOrNull() ?: Exception("Unknown error")
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Failed to restore account", e)
                runOnUiThread {
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "Failed to restore account: ${e.message}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // =========================================================================
    // MARK: - Share Intent Handling
    // =========================================================================

    /**
     * Extracts share payload from an incoming share intent.
     *
     * Handles:
     * - ACTION_SEND: Single item share (text, image, file)
     * - ACTION_SEND_MULTIPLE: Multiple items (multiple images)
     * - ACTION_SENDTO: SMS compose intent with recipient
     *
     * @param intent The incoming intent to parse
     * @return SharePayload if content was found, null otherwise
     */
    private fun handleShareIntent(intent: Intent?): SharePayload? {
        if (intent == null) return null

        return when (intent.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                val uris = extractSharedUris(intent)
                SharePayload(text = text, uris = uris).takeIf { it.hasContent }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                val uris = extractSharedUris(intent)
                SharePayload(text = text, uris = uris).takeIf { it.hasContent }
            }
            Intent.ACTION_SENDTO -> {
                val uri = intent.data
                val number = uri?.schemeSpecificPart?.substringBefore("?")
                val body = uri?.getQueryParameter("body")
                SharePayload(text = body, uris = emptyList(), recipient = number).takeIf { it.hasContent }
            }
            else -> null
        }
    }

    /**
     * Extracts all URIs from a share intent.
     *
     * Checks multiple sources:
     * - EXTRA_STREAM for single URI
     * - EXTRA_STREAM ArrayList for multiple URIs
     * - ClipData for additional items
     *
     * Returns deduplicated list of URIs.
     */
    private fun extractSharedUris(intent: Intent): List<Uri> {
        val uris = mutableListOf<Uri>()

        val single = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (single != null) {
            uris.add(single)
        }

        val multiple = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        if (!multiple.isNullOrEmpty()) {
            uris.addAll(multiple)
        }

        val clipData = intent.clipData
        if (clipData != null) {
            for (i in 0 until clipData.itemCount) {
                val uri = clipData.getItemAt(i).uri
                if (uri != null) {
                    uris.add(uri)
                }
            }
        }

        return uris.distinct()
    }
}
