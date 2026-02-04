/**
 * VPS Authentication Manager - Replacement for Firebase-based AuthManager
 *
 * This manager handles authentication via the VPS server using JWT tokens
 * instead of Firebase Auth. It provides similar functionality to AuthManager
 * but uses the VPS backend for all authentication operations.
 */

package com.phoneintegration.app.vps

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit

/**
 * Authentication states for VPS auth
 */
sealed class VPSAuthState {
    object Unauthenticated : VPSAuthState()
    data class Authenticated(val user: VPSUser) : VPSAuthState()
    data class Error(val message: String) : VPSAuthState()
}

/**
 * VPS Authentication Manager with session management.
 * Provides centralized authentication state management similar to AuthManager.
 */
class VPSAuthManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "VPSAuthManager"
        private const val SESSION_TIMEOUT_MINUTES = 30L // Auto logout after 30 minutes of inactivity
        private const val TOKEN_REFRESH_BUFFER_MINUTES = 5L // Refresh token 5 minutes before expiry

        @Volatile
        private var instance: VPSAuthManager? = null

        fun getInstance(context: Context): VPSAuthManager {
            return instance ?: synchronized(this) {
                instance ?: VPSAuthManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val vpsClient = VPSClient.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Authentication state
    private val _authState = MutableStateFlow<VPSAuthState>(VPSAuthState.Unauthenticated)
    val authState: StateFlow<VPSAuthState> = _authState.asStateFlow()

    // Session tracking
    private var lastActivityTime = System.currentTimeMillis()
    private var sessionTimeoutJob: Job? = null
    private var tokenRefreshJob: Job? = null

    private var authListenerStarted = false

    init {
        // Check if we have stored credentials
        if (vpsClient.isAuthenticated) {
            // Initialize authentication state
            scope.launch {
                try {
                    val user = vpsClient.getCurrentUser()
                    _authState.value = VPSAuthState.Authenticated(user)
                    startSessionMonitoring()
                    startTokenRefreshMonitoring()
                    authListenerStarted = true
                    Log.i(TAG, "VPSAuthManager initialized with existing credentials")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to restore auth state: ${e.message}")
                    _authState.value = VPSAuthState.Unauthenticated
                }
            }
        } else {
            Log.i(TAG, "VPSAuthManager initialized (no existing credentials)")
        }
    }

    /**
     * Initialize authentication if tokens are stored
     */
    suspend fun initialize(): Boolean {
        return try {
            if (vpsClient.initialize()) {
                val user = vpsClient.getCurrentUser()
                _authState.value = VPSAuthState.Authenticated(user)
                startSessionMonitoring()
                startTokenRefreshMonitoring()
                authListenerStarted = true
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize: ${e.message}")
            false
        }
    }

    /**
     * Start session timeout monitoring
     */
    private fun startSessionMonitoring() {
        sessionTimeoutJob?.cancel()
        sessionTimeoutJob = scope.launch {
            while (isActive) {
                delay(TimeUnit.MINUTES.toMillis(1)) // Check every minute

                val currentTime = System.currentTimeMillis()
                val timeSinceLastActivity = currentTime - lastActivityTime

                val authState = _authState.value
                if (authState is VPSAuthState.Authenticated) {
                    if (timeSinceLastActivity > TimeUnit.MINUTES.toMillis(SESSION_TIMEOUT_MINUTES)) {
                        Log.w(TAG, "Session timeout - logging out due to inactivity")
                        logout()
                        break
                    }
                }
            }
        }
    }

    /**
     * Start token refresh monitoring
     */
    private fun startTokenRefreshMonitoring() {
        tokenRefreshJob?.cancel()
        tokenRefreshJob = scope.launch {
            while (isActive && _authState.value is VPSAuthState.Authenticated) {
                try {
                    // Refresh token periodically (every 30 minutes)
                    delay(TimeUnit.MINUTES.toMillis(30))

                    Log.d(TAG, "Refreshing VPS access token")
                    vpsClient.refreshAccessToken()

                } catch (e: Exception) {
                    Log.e(TAG, "Error refreshing token: ${e.message}")
                    // If refresh fails, try to re-authenticate
                    if (e.message?.contains("401") == true || e.message?.contains("403") == true) {
                        _authState.value = VPSAuthState.Unauthenticated
                        break
                    }
                    delay(TimeUnit.MINUTES.toMillis(1))
                }
            }
        }
    }

    /**
     * Stop token refresh monitoring
     */
    private fun stopTokenRefreshMonitoring() {
        tokenRefreshJob?.cancel()
        tokenRefreshJob = null
    }

    /**
     * Update last activity time (call this on user interactions)
     */
    fun updateActivity() {
        lastActivityTime = System.currentTimeMillis()
    }

    /**
     * Sign in anonymously (creates new VPS account)
     */
    suspend fun signInAnonymously(): Result<String> {
        return try {
            val user = vpsClient.authenticateAnonymous()
            _authState.value = VPSAuthState.Authenticated(user)
            updateActivity()
            startSessionMonitoring()
            startTokenRefreshMonitoring()
            authListenerStarted = true

            Log.i(TAG, "Anonymous sign in successful: ${user.userId}")
            Result.success(user.userId)
        } catch (e: Exception) {
            Log.e(TAG, "Anonymous sign in failed", e)
            _authState.value = VPSAuthState.Error(e.message ?: "Unknown error")
            Result.failure(e)
        }
    }

    /**
     * Get current user ID
     */
    fun getCurrentUserId(): String? {
        val state = _authState.value
        return if (state is VPSAuthState.Authenticated) {
            updateActivity()
            state.user.userId
        } else {
            null
        }
    }

    /**
     * Get current device ID
     */
    fun getCurrentDeviceId(): String? {
        val state = _authState.value
        return if (state is VPSAuthState.Authenticated) {
            state.user.deviceId
        } else {
            null
        }
    }

    /**
     * Check if session is valid
     */
    private fun isSessionValid(): Boolean {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastActivity = currentTime - lastActivityTime
        return timeSinceLastActivity <= TimeUnit.MINUTES.toMillis(SESSION_TIMEOUT_MINUTES)
    }

    /**
     * Force refresh authentication token
     */
    suspend fun refreshToken(): Result<Unit> {
        return try {
            vpsClient.refreshAccessToken()
            updateActivity()
            Log.d(TAG, "Token refreshed successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Token refresh failed", e)
            Result.failure(e)
        }
    }

    /**
     * Logout with cleanup
     */
    fun logout() {
        try {
            vpsClient.logout()
            stopTokenRefreshMonitoring()
            sessionTimeoutJob?.cancel()
            _authState.value = VPSAuthState.Unauthenticated
            Log.i(TAG, "User logged out successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error during logout", e)
        }
    }

    /**
     * Check if user is authenticated and session is valid
     */
    fun isAuthenticated(): Boolean {
        return vpsClient.isAuthenticated && isSessionValid()
    }

    /**
     * Get current VPS user
     */
    fun getCurrentUser(): VPSUser? {
        val state = _authState.value
        return if (state is VPSAuthState.Authenticated && isSessionValid()) {
            updateActivity()
            state.user
        } else {
            null
        }
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        sessionTimeoutJob?.cancel()
        tokenRefreshJob?.cancel()
        scope.cancel()
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // PAIRING OPERATIONS
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Initiate pairing (generates pairing token for desktop/web)
     */
    suspend fun initiatePairing(deviceName: String, deviceType: String = "android"): Result<VPSPairingRequest> {
        return try {
            val request = vpsClient.initiatePairing(deviceName, deviceType)
            Log.i(TAG, "Pairing initiated: ${request.pairingToken}")
            Result.success(request)
        } catch (e: Exception) {
            Log.e(TAG, "Pairing initiation failed", e)
            Result.failure(e)
        }
    }

    /**
     * Check pairing status
     */
    suspend fun checkPairingStatus(token: String): Result<VPSPairingStatus> {
        return try {
            val status = vpsClient.checkPairingStatus(token)
            Result.success(status)
        } catch (e: Exception) {
            Log.e(TAG, "Pairing status check failed", e)
            Result.failure(e)
        }
    }

    /**
     * Complete pairing (called by Android after scanning QR code)
     */
    suspend fun completePairing(token: String): Result<Unit> {
        return try {
            vpsClient.completePairing(token)
            Log.i(TAG, "Pairing completed for token: $token")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Pairing completion failed", e)
            Result.failure(e)
        }
    }

    /**
     * Redeem pairing (called by desktop/web after Android approves)
     */
    suspend fun redeemPairing(token: String, deviceName: String?, deviceType: String?): Result<VPSUser> {
        return try {
            val user = vpsClient.redeemPairing(token, deviceName, deviceType)
            _authState.value = VPSAuthState.Authenticated(user)
            startSessionMonitoring()
            startTokenRefreshMonitoring()
            Log.i(TAG, "Pairing redeemed: ${user.userId}")
            Result.success(user)
        } catch (e: Exception) {
            Log.e(TAG, "Pairing redemption failed", e)
            Result.failure(e)
        }
    }
}
