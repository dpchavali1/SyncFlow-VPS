package com.phoneintegration.app

// =============================================================================
// ARCHITECTURE OVERVIEW - VPS BACKEND ONLY
// =============================================================================
//
// SyncFlowApp is the Android Application class and primary entry point for the
// SyncFlow Android application. It initializes all core services and managers
// required for the app to function.
//
// NOTE: This version uses VPS backend ONLY - Firebase has been completely removed.
//
// INITIALIZATION ORDER:
// ---------------------
// The initialization order is critical for proper app function:
// 1. ErrorHandler - Must be first to catch any initialization errors
// 2. VPS Security Config - Security configuration for VPS connections
// 3. VPSAuthManager - Session management and authentication via VPS
// 4. SecurityMonitor - Monitors for security threats and anomalies
// 5. InputValidation - Sanitizes user input to prevent injection attacks
// 6. SQLCipher - Encrypted local database for sensitive data
// 7. DealNotificationScheduler - Background job for promotional notifications
// 8. VPSSyncService - VPS-based cross-platform sync
// 9. SpamFilterWorker - Background spam detection and filtering
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
import com.phoneintegration.app.deals.notify.DealNotificationScheduler
import com.phoneintegration.app.security.SecurityMonitor
import com.phoneintegration.app.spam.SpamFilterWorker
import com.phoneintegration.app.utils.ErrorHandler
import com.phoneintegration.app.utils.InputValidation
import com.phoneintegration.app.vps.VPSAuthManager
import com.phoneintegration.app.vps.VPSSecurityConfig
import com.phoneintegration.app.vps.VPSSyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.sqlcipher.database.SQLiteDatabase
import java.io.File

/**
 * Main Application class for SyncFlow Android app (VPS Backend Only).
 *
 * This class serves as the entry point for the Android application and is responsible
 * for initializing all core services, managers, and configurations before any Activity
 * or Service is created.
 *
 * ## VPS Backend
 * This version uses the VPS backend exclusively. All sync, authentication, and
 * messaging operations go through the VPS server at http://5.78.188.206.
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
 * - VPS connection security configuration
 * - SQLCipher for encrypted local database
 * - SecurityMonitor for threat detection
 * - InputValidation for injection prevention
 *
 * @see MainActivity Main activity that starts after Application initialization
 * @see VPSAuthManager VPS-based authentication and session management
 * @see VPSSecurityConfig VPS security configuration
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

            // Initialize custom crash reporter (VPS-based)
            try {
                com.phoneintegration.app.utils.CustomCrashReporter.init(this)
                android.util.Log.i("SyncFlowApp", "Custom crash reporter initialized successfully")
            } catch (e: Exception) {
                android.util.Log.e("SyncFlowApp", "Failed to initialize custom crash reporter", e)
                // Continue without crash reporting if it fails
            }

            // Initialize VPS security configuration
            try {
                VPSSecurityConfig.initialize(this)
                android.util.Log.i("SyncFlowApp", "VPS security config initialized successfully")
            } catch (e: Exception) {
                android.util.Log.e("SyncFlowApp", "Failed to initialize VPS security config", e)
            }

            android.util.Log.i("SyncFlowApp", "VPS mode enabled - using VPS backend exclusively")

            // Initialize VPS authentication manager
            try {
                VPSAuthManager.getInstance(this)
                android.util.Log.i("SyncFlowApp", "VPSAuthManager initialized successfully")
            } catch (e: Exception) {
                android.util.Log.e("SyncFlowApp", "Failed to initialize VPSAuthManager", e)
            }

            // Initialize VPS authentication and sync service
            try {
                val vpsAuth = VPSAuthManager.getInstance(this)
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        // Use UnifiedIdentityManager for stable device-fingerprint-based auth
                        // This prevents creating orphan anonymous users
                        if (!vpsAuth.isAuthenticated()) {
                            android.util.Log.i("SyncFlowApp", "Not authenticated - using unified identity manager...")
                            val unifiedIdentityManager = com.phoneintegration.app.auth.UnifiedIdentityManager.getInstance(this@SyncFlowApp)
                            val userId = unifiedIdentityManager.getUnifiedUserId()
                            if (userId != null) {
                                android.util.Log.i("SyncFlowApp", "Authenticated via device fingerprint: $userId")
                            } else {
                                android.util.Log.e("SyncFlowApp", "Authentication failed")
                                return@launch
                            }
                        }

                        // Now initialize sync service
                        val vpsSyncService = VPSSyncService.getInstance(this@SyncFlowApp)
                        vpsSyncService.initialize()
                        android.util.Log.i("SyncFlowApp", "VPSSyncService initialized successfully")
                    } catch (e: Exception) {
                        android.util.Log.e("SyncFlowApp", "Failed to initialize VPS services", e)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("SyncFlowApp", "Failed to initialize VPS authentication", e)
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
