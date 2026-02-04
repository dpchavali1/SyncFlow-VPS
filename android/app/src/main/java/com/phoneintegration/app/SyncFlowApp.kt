package com.phoneintegration.app

// =============================================================================
// ARCHITECTURE OVERVIEW
// =============================================================================
//
// SyncFlowApp is the Android Application class and primary entry point for the
// SyncFlow Android application. It initializes all core services and managers
// required for the app to function.
//
// INITIALIZATION ORDER:
// ---------------------
// The initialization order is critical for proper app function:
// 1. ErrorHandler - Must be first to catch any initialization errors
// 2. Firebase Security Config - Certificate pinning for secure communication
// 3. AuthManager - Session management and authentication
// 4. SecurityMonitor - Monitors for security threats and anomalies
// 5. InputValidation - Sanitizes user input to prevent injection attacks
// 6. SQLCipher - Encrypted local database for sensitive data
// 7. DealNotificationScheduler - Background job for promotional notifications
// 8. IntelligentSyncManager - Cross-platform message sync coordination
// 9. UnifiedIdentityManager - Single user identity across all devices
// 10. DataCleanupService - Firebase storage cost management
// 11. SpamFilterWorker - Background spam detection and filtering
//
// ERROR HANDLING STRATEGY:
// ------------------------
// Each initialization step is wrapped in try-catch to ensure:
// - Individual failures don't crash the entire app
// - Failures are logged for debugging
// - The app continues with reduced functionality if non-critical services fail
// - Only truly critical failures (caught in outer try-catch) cause app termination
//
// COIL IMAGE LOADING:
// -------------------
// The app implements ImageLoaderFactory to provide a custom Coil ImageLoader
// with optimized caching for contact photos and message images:
// - Memory cache: 25% of available memory
// - Disk cache: 100MB for persistent image storage
//
// SERVICE ARCHITECTURE:
// ---------------------
// Services use the singleton pattern accessed via getInstance():
// - Thread-safe lazy initialization
// - Single instance shared across all components
// - Lifecycle managed by the Application
//
// LIFECYCLE CONSIDERATIONS:
// -------------------------
// - Application.onCreate() runs before any Activity/Service
// - CallMonitorService is started from MainActivity to avoid
//   ForegroundServiceStartNotAllowedException on Android 14+
// - Background workers are scheduled via WorkManager for battery efficiency
//
// =============================================================================

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.phoneintegration.app.auth.AuthManager
import com.phoneintegration.app.deals.notify.DealNotificationScheduler
import com.phoneintegration.app.network.FirebaseSecurityConfig
import com.phoneintegration.app.security.SecurityMonitor
import com.phoneintegration.app.spam.SpamFilterWorker
import com.phoneintegration.app.utils.ErrorHandler
import com.phoneintegration.app.utils.InputValidation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.sqlcipher.database.SQLiteDatabase
import java.io.File

/**
 * Main Application class for SyncFlow Android app.
 *
 * This class serves as the entry point for the Android application and is responsible
 * for initializing all core services, managers, and configurations before any Activity
 * or Service is created.
 *
 * ## Initialization Flow
 * The `onCreate()` method initializes services in a specific order to ensure proper
 * dependency resolution. Each initialization is wrapped in try-catch to allow graceful
 * degradation if a non-critical service fails.
 *
 * ## Image Loading
 * Implements [ImageLoaderFactory] to provide a custom Coil [ImageLoader] with
 * optimized caching strategies for contact photos and message attachments.
 *
 * ## Security
 * Initializes multiple security layers:
 * - Firebase certificate pinning for network security
 * - SQLCipher for encrypted local database
 * - SecurityMonitor for threat detection
 * - InputValidation for injection prevention
 *
 * @see MainActivity Main activity that starts after Application initialization
 * @see AuthManager Authentication and session management
 * @see FirebaseSecurityConfig Network security configuration
 */
class SyncFlowApp : Application(), ImageLoaderFactory {

    /**
     * Called when the application is starting, before any activity, service, or receiver
     * objects have been created.
     *
     * Initializes all core services in dependency order. Uses defensive try-catch
     * wrapping to ensure partial functionality even if some services fail to initialize.
     */
    override fun onCreate() {
        super.onCreate()

        try {
            // Initialize global error handler first
            ErrorHandler.init(this)

            // Initialize custom crash reporter (free Firebase-based solution, no Gradle plugin needed)
            try {
                com.phoneintegration.app.utils.CustomCrashReporter.init(this)
                android.util.Log.i("SyncFlowApp", "Custom crash reporter initialized successfully")
            } catch (e: Exception) {
                android.util.Log.e("SyncFlowApp", "Failed to initialize custom crash reporter", e)
                // Continue without crash reporting if it fails
            }

            // Initialize Firebase with certificate pinning for security
            try {
                FirebaseSecurityConfig.initializeFirebaseWithCertificatePinning(this)
                android.util.Log.i("SyncFlowApp", "Firebase security config initialized successfully")
            } catch (e: Exception) {
                android.util.Log.e("SyncFlowApp", "Failed to initialize Firebase security config", e)
                // Continue without security features if Firebase fails
            }

            // BANDWIDTH OPTIMIZATION: Only initialize Firebase-heavy services if user has paired devices
            // This prevents 2MB+ Firebase downloads on fresh install before any device pairing
            val hasPairedDevices = com.phoneintegration.app.desktop.DesktopSyncService.hasPairedDevices(this)

            if (hasPairedDevices) {
                // Check for backed-up recovery code and auto-restore account
                // PERFORMANCE FIX: Launch async to avoid blocking app startup
                try {
                    val recoveryManager = com.phoneintegration.app.auth.RecoveryCodeManager.getInstance(this)

                    // Launch in background with IO dispatcher (don't block main thread)
                    CoroutineScope(Dispatchers.IO).launch {
                        val restoreResult = recoveryManager.checkAndRestoreFromBackup()

                        when {
                            restoreResult.isSuccess && restoreResult.getOrNull() != null -> {
                                val userId = restoreResult.getOrNull()
                                android.util.Log.i("SyncFlowApp", "Auto-restored user account from backup: $userId")
                            }
                            restoreResult.isSuccess && restoreResult.getOrNull() == null -> {
                                android.util.Log.d("SyncFlowApp", "No backup found, proceeding with fresh install")
                            }
                            else -> {
                                android.util.Log.w("SyncFlowApp", "Auto-restore failed: ${restoreResult.exceptionOrNull()?.message}")
                            }
                        }
                    }
                    android.util.Log.i("SyncFlowApp", "Backup restore check started in background")
                } catch (e: Exception) {
                    android.util.Log.e("SyncFlowApp", "Failed to check for backup recovery", e)
                }

                // Initialize authentication manager for secure session management
                // This sets up Firebase Auth state listener and token refresh - causes network traffic
                try {
                    AuthManager.getInstance(this)
                    android.util.Log.i("SyncFlowApp", "AuthManager initialized successfully")
                } catch (e: Exception) {
                    android.util.Log.e("SyncFlowApp", "Failed to initialize AuthManager", e)
                }
            } else {
                android.util.Log.i("SyncFlowApp", "Skipping RecoveryCodeManager and AuthManager - no paired devices yet")
            }

            // Initialize security monitoring
            try {
                val securityMonitor = SecurityMonitor.getInstance(this)
                setupSecurityAlertHandlers(securityMonitor)
                android.util.Log.i("SyncFlowApp", "SecurityMonitor initialized successfully")
            } catch (e: Exception) {
                android.util.Log.e("SyncFlowApp", "Failed to initialize SecurityMonitor", e)
                // Continue without security monitoring if it fails
            }

            // Initialize input validation security monitoring
            try {
                InputValidation.initializeSecurityMonitoring(this)
                android.util.Log.i("SyncFlowApp", "InputValidation security monitoring initialized successfully")
            } catch (e: Exception) {
                android.util.Log.e("SyncFlowApp", "Failed to initialize InputValidation security monitoring", e)
                // Continue without security monitoring if it fails
            }

            // Load SQLCipher native library
            try {
                SQLiteDatabase.loadLibs(this)
                android.util.Log.i("SyncFlowApp", "SQLCipher loaded successfully")
            } catch (e: Exception) {
                android.util.Log.e("SyncFlowApp", "Failed to load SQLCipher", e)
                // This might be critical, but let's continue
            }

            // Schedule daily notification windows
            try {
                DealNotificationScheduler.scheduleDailyWork(this)
                android.util.Log.i("SyncFlowApp", "DealNotificationScheduler initialized successfully")
            } catch (e: Exception) {
                android.util.Log.e("SyncFlowApp", "Failed to initialize DealNotificationScheduler", e)
                // Continue without notification scheduling if it fails
            }

            // BANDWIDTH OPTIMIZATION: Only initialize sync managers if user has paired devices
            // These services create Firebase instances that can cause network traffic
            if (hasPairedDevices) {
                // Initialize intelligent sync manager for seamless cross-platform messaging
                try {
                    com.phoneintegration.app.services.IntelligentSyncManager.getInstance(this)
                    android.util.Log.i("SyncFlowApp", "IntelligentSyncManager initialized successfully")
                } catch (e: Exception) {
                    android.util.Log.e("SyncFlowApp", "Failed to initialize IntelligentSyncManager", e)
                }

                // Initialize unified identity manager for single user across all devices
                try {
                    com.phoneintegration.app.auth.UnifiedIdentityManager.getInstance(this)
                    android.util.Log.i("SyncFlowApp", "UnifiedIdentityManager initialized successfully")
                } catch (e: Exception) {
                    android.util.Log.e("SyncFlowApp", "Failed to initialize UnifiedIdentityManager", e)
                }

                // Initialize data cleanup service to manage Firebase storage costs
                try {
                    com.phoneintegration.app.services.DataCleanupService.getInstance(this)
                    android.util.Log.i("SyncFlowApp", "DataCleanupService initialized successfully")
                } catch (e: Exception) {
                    android.util.Log.e("SyncFlowApp", "Failed to initialize DataCleanupService", e)
                }
            } else {
                android.util.Log.i("SyncFlowApp", "Skipping sync managers - no paired devices yet (will init after first pairing)")
            }

            // Initialize automatic spam filter protection
            try {
                val spamPrefs = getSharedPreferences("spam_filter", MODE_PRIVATE)
                val isFirstActivation = !spamPrefs.getBoolean("protection_shown", false)

                // Schedule daily filter updates (WiFi only, battery-efficient)
                SpamFilterWorker.scheduleDailyUpdates(this)

                // Schedule first-run scan (will scan existing messages once)
                // This runs in background with WorkManager - doesn't affect app UI
                SpamFilterWorker.scheduleFirstRunScan(this)

                // Show welcome notification on first activation
                if (isFirstActivation) {
                    spamPrefs.edit().putBoolean("protection_shown", true).apply()
                    try {
                        NotificationHelper(this).showSpamProtectionActivatedNotification()
                    } catch (e: Exception) {
                        android.util.Log.w("SyncFlowApp", "Could not show welcome notification", e)
                    }
                }

                android.util.Log.i("SyncFlowApp", "Spam filter protection initialized successfully")
            } catch (e: Exception) {
                android.util.Log.e("SyncFlowApp", "Failed to initialize spam filter protection", e)
                // Continue without spam filter if it fails
            }


        } catch (e: Exception) {
            android.util.Log.e("SyncFlowApp", "Critical error during app initialization", e)
            // If we get here, something went very wrong
            throw e
        }

        // Note: CallMonitorService is now started from MainActivity
        // to avoid ForegroundServiceStartNotAllowedException on Android 14+
    }

    /**
     * Sets up handlers for security alerts from the SecurityMonitor.
     *
     * Security alerts are categorized by severity:
     * - CRITICAL: Immediate threats requiring urgent action (e.g., tampering detected)
     * - HIGH: Significant security concerns (e.g., repeated auth failures)
     * - MEDIUM/LOW: Informational alerts for logging
     *
     * All alerts are logged, and critical/high alerts could trigger notifications
     * or other defensive actions in production.
     *
     * @param securityMonitor The security monitor instance to attach handlers to
     */
    private fun setupSecurityAlertHandlers(securityMonitor: SecurityMonitor) {
        securityMonitor.addAlertHandler { alert ->
            // Log all alerts
            android.util.Log.w("SecurityAlert", "${alert.severity}: ${alert.message}")

            // Handle critical alerts (could send notifications, etc.)
            when (alert.severity) {
                com.phoneintegration.app.security.AlertSeverity.CRITICAL -> {
                    // Critical alerts could trigger immediate actions
                    android.util.Log.e("SecurityAlert", "CRITICAL SECURITY ALERT: ${alert.message}")
                    // TODO: Could send notification to user or security team
                }
                com.phoneintegration.app.security.AlertSeverity.HIGH -> {
                    android.util.Log.w("SecurityAlert", "HIGH SECURITY ALERT: ${alert.message}")
                }
                else -> {
                    android.util.Log.i("SecurityAlert", "Security alert: ${alert.message}")
                }
            }
        }
    }

    /**
     * Creates a custom Coil ImageLoader with optimized caching for contact photos
     * and message attachments.
     *
     * ## Caching Strategy
     * - **Memory Cache**: 25% of available app memory for fast access to recently
     *   viewed images. Uses strong references to prevent garbage collection of
     *   frequently accessed images.
     * - **Disk Cache**: 100MB persistent cache in the app's cache directory for
     *   images that survive app restarts.
     *
     * ## Cache Policies
     * - Both memory and disk caching are enabled
     * - Network cache headers are respected for proper invalidation
     * - Crossfade animation (200ms) for smooth image loading transitions
     *
     * @return Configured ImageLoader instance for use throughout the app
     */
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            // Memory cache configuration
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25) // Use 25% of app's available memory
                    .strongReferencesEnabled(true)
                    .build()
            }
            // Disk cache configuration
            .diskCache {
                DiskCache.Builder()
                    .directory(File(cacheDir, "image_cache"))
                    .maxSizeBytes(100L * 1024 * 1024) // 100MB disk cache
                    .build()
            }
            // Cache policies
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            // Crossfade for smoother loading
            .crossfade(true)
            .crossfade(200)
            // Respect cache headers from network
            .respectCacheHeaders(true)
            .build()
    }
}
