package com.phoneintegration.app.auth

import android.content.Context
import android.util.Log
import com.phoneintegration.app.security.SecurityEvent
import com.phoneintegration.app.security.SecurityEventType
import com.phoneintegration.app.security.SecurityMonitor
import com.phoneintegration.app.vps.VPSAuthManager
import com.phoneintegration.app.vps.VPSAuthState
import com.phoneintegration.app.vps.VPSUser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit

/**
 * Authentication Manager - VPS Backend Only
 *
 * This is a compatibility wrapper around VPSAuthManager that maintains
 * the same interface as the original Firebase-based AuthManager.
 * All actual authentication is handled by VPSAuthManager.
 *
 * Note: Phone authentication features are not available in VPS mode.
 * Use VPSAuthManager directly for full VPS authentication features.
 */
class AuthManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "AuthManager"
        private const val SESSION_TIMEOUT_MINUTES = 30L

        @Volatile
        private var instance: AuthManager? = null

        fun getInstance(context: Context): AuthManager {
            return instance ?: synchronized(this) {
                instance ?: AuthManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val vpsAuthManager: VPSAuthManager by lazy { VPSAuthManager.getInstance(context) }
    private val securityMonitor: SecurityMonitor? = try {
        SecurityMonitor.getInstance(context)
    } catch (e: Exception) {
        Log.w(TAG, "Failed to initialize SecurityMonitor, continuing without it", e)
        null
    }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Authentication state - maps VPS auth state to legacy AuthState
    private val _authState = MutableStateFlow<AuthState>(AuthState.Unauthenticated)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    // Security settings
    private val _securitySettings = MutableStateFlow(SecuritySettings())
    val securitySettings: StateFlow<SecuritySettings> = _securitySettings.asStateFlow()

    init {
        Log.i(TAG, "AuthManager initialized (VPS backend only)")

        // Observe VPS auth state and map to legacy AuthState
        scope.launch {
            vpsAuthManager.authState.collect { vpsState ->
                _authState.value = when (vpsState) {
                    is VPSAuthState.Authenticated -> AuthState.Authenticated(vpsState.user)
                    is VPSAuthState.Unauthenticated -> AuthState.Unauthenticated
                    is VPSAuthState.Error -> AuthState.Unauthenticated
                }
            }
        }
    }

    /**
     * Check if user has paired devices (local cache check - no network)
     */
    private fun hasPairedDevicesLocally(): Boolean {
        val prefs = context.getSharedPreferences("desktop_sync_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("has_paired_devices", false)
    }

    /**
     * Start auth listeners after first device is paired.
     * In VPS mode, this initializes the VPS sync service.
     */
    fun startAuthListenersAfterPairing() {
        Log.i(TAG, "Starting VPS services after pairing")
        scope.launch {
            try {
                vpsAuthManager.initialize()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize VPS auth after pairing", e)
            }
        }
    }

    /**
     * Update last activity time (call this on user interactions)
     */
    fun updateActivity() {
        vpsAuthManager.updateActivity()
    }

    /**
     * Sign in anonymously - creates new VPS account
     */
    suspend fun signInAnonymously(): Result<String> {
        return try {
            val result = vpsAuthManager.signInAnonymously()

            if (result.isSuccess) {
                val userId = result.getOrThrow()

                // Log successful authentication
                securityMonitor?.logEvent(SecurityEvent(
                    type = SecurityEventType.AUTH_SUCCESS,
                    message = "VPS authentication successful",
                    metadata = mapOf("userId" to userId, "authMethod" to "vps_anonymous")
                ))

                Log.i(TAG, "VPS anonymous sign in successful: $userId")
            } else {
                // Log authentication failure
                securityMonitor?.logEvent(SecurityEvent(
                    type = SecurityEventType.AUTH_FAILED,
                    message = "VPS authentication failed: ${result.exceptionOrNull()?.message}",
                    metadata = mapOf("error" to result.exceptionOrNull()?.message.toString(), "authMethod" to "vps_anonymous")
                ))

                Log.e(TAG, "VPS anonymous sign in failed", result.exceptionOrNull())
            }

            result

        } catch (e: Exception) {
            securityMonitor?.logEvent(SecurityEvent(
                type = SecurityEventType.AUTH_FAILED,
                message = "VPS authentication failed: ${e.message}",
                metadata = mapOf("error" to e.message.toString(), "authMethod" to "vps_anonymous")
            ))

            Log.e(TAG, "VPS anonymous sign in failed", e)
            Result.failure(e)
        }
    }

    /**
     * Get current user ID
     */
    fun getCurrentUserId(): String? {
        return vpsAuthManager.getCurrentUserId()
    }

    /**
     * Get current device ID
     */
    fun getCurrentDeviceId(): String? {
        return vpsAuthManager.getCurrentDeviceId()
    }

    /**
     * Validate auth state - for VPS this just returns the current user ID
     */
    suspend fun validateAndFixAuthState(): String? {
        return vpsAuthManager.getCurrentUserId()
    }

    /**
     * Get validated user ID - for VPS this is same as getCurrentUserId
     */
    suspend fun getValidatedUserId(): String? {
        return vpsAuthManager.getCurrentUserId()
    }

    /**
     * Force refresh authentication token
     */
    suspend fun refreshToken(): Result<Unit> {
        return vpsAuthManager.refreshToken()
    }

    /**
     * Logout with cleanup
     */
    fun logout() {
        try {
            vpsAuthManager.logout()
            _authState.value = AuthState.Unauthenticated
            Log.i(TAG, "User logged out successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error during logout", e)
        }
    }

    /**
     * Check if user is authenticated
     */
    fun isAuthenticated(): Boolean {
        return vpsAuthManager.isAuthenticated()
    }

    /**
     * Get current VPS user
     */
    fun getCurrentUser(): VPSUser? {
        return vpsAuthManager.getCurrentUser()
    }

    /**
     * Update security settings
     */
    fun updateSecuritySettings(settings: SecuritySettings) {
        _securitySettings.value = settings
        Log.d(TAG, "Security settings updated: sessionTimeout=${settings.sessionTimeoutMinutes}min")
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        vpsAuthManager.cleanup()
        scope.cancel()
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // PHONE AUTHENTICATION - Not available in VPS mode
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Check if user has verified their phone number.
     * Note: Phone verification is not available in VPS mode.
     */
    fun isPhoneVerified(): Boolean {
        return false // Not available in VPS mode
    }

    /**
     * Get the verified phone number (if available)
     * Note: Phone verification is not available in VPS mode.
     */
    fun getVerifiedPhoneNumber(): String? {
        return null // Not available in VPS mode
    }

    /**
     * Get the phone hash for lookups
     * Note: Phone verification is not available in VPS mode.
     */
    fun getPhoneHash(): String? {
        return null // Not available in VPS mode
    }

    /**
     * Check if user needs phone verification.
     * Note: Phone verification is not available in VPS mode.
     */
    fun needsPhoneVerification(): Boolean {
        return false // Not needed in VPS mode
    }

    /**
     * Get the authentication method used
     */
    fun getAuthMethod(): AuthMethod {
        return if (vpsAuthManager.isAuthenticated()) {
            AuthMethod.VPS
        } else {
            AuthMethod.NONE
        }
    }

    /**
     * Ensure user is authenticated
     */
    suspend fun ensureAuthenticated(): Result<String> {
        // If already authenticated, return current user
        vpsAuthManager.getCurrentUserId()?.let { return Result.success(it) }

        // Otherwise, use UnifiedIdentityManager which uses Firebase UID auth
        // This ensures consistent user ID across app reinstalls
        return try {
            val userId = UnifiedIdentityManager.getInstance(context).getUnifiedUserId()
            if (userId != null) {
                Result.success(userId)
            } else {
                Result.failure(Exception("Failed to authenticate"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "ensureAuthenticated failed", e)
            Result.failure(e)
        }
    }
}

/**
 * Authentication method enum
 */
enum class AuthMethod {
    NONE,
    VPS,
    ANONYMOUS,
    PHONE,
    OTHER
}

/**
 * Authentication state sealed class
 * Now uses VPSUser instead of FirebaseUser
 */
sealed class AuthState {
    object Unauthenticated : AuthState()
    data class Authenticated(val user: VPSUser) : AuthState()
}

/**
 * Security settings data class
 */
data class SecuritySettings(
    val sessionTimeoutMinutes: Int = 30,
    val requireBiometricForSensitiveOperations: Boolean = false,
    val enableSessionTimeout: Boolean = true,
    val enableTokenAutoRefresh: Boolean = true
)
