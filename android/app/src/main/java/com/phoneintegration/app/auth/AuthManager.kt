package com.phoneintegration.app.auth

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.phoneintegration.app.security.SecurityEvent
import com.phoneintegration.app.security.SecurityEventType
import com.phoneintegration.app.security.SecurityMonitor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

/**
 * Enhanced Authentication Manager with secure session management.
 * Provides centralized authentication state management and security features.
 *
 * Now integrates with PhoneAuthManager for stable user identity.
 * Phone-verified users get persistent identity across reinstalls.
 */
class AuthManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "AuthManager"
        private const val SESSION_TIMEOUT_MINUTES = 30L // Auto logout after 30 minutes of inactivity
        private const val TOKEN_REFRESH_BUFFER_MINUTES = 5L // Refresh token 5 minutes before expiry

        @Volatile
        private var instance: AuthManager? = null

        fun getInstance(context: Context): AuthManager {
            return instance ?: synchronized(this) {
                instance ?: AuthManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val auth = FirebaseAuth.getInstance()
    private val phoneAuthManager: PhoneAuthManager by lazy { PhoneAuthManager.getInstance(context) }
    private val recoveryCodeManager: RecoveryCodeManager by lazy { RecoveryCodeManager.getInstance(context) }
    private val securityMonitor: SecurityMonitor? = try {
        SecurityMonitor.getInstance(context)
    } catch (e: Exception) {
        android.util.Log.w(TAG, "Failed to initialize SecurityMonitor, continuing without it", e)
        null
    }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Authentication state
    private val _authState = MutableStateFlow<AuthState>(AuthState.Unauthenticated)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    // Session tracking
    private var lastActivityTime = System.currentTimeMillis()
    private var sessionTimeoutJob: Job? = null
    private var tokenRefreshJob: Job? = null

    // Security settings
    private val _securitySettings = MutableStateFlow(SecuritySettings())
    val securitySettings: StateFlow<SecuritySettings> = _securitySettings.asStateFlow()

    private var authListenerStarted = false

    init {
        // BANDWIDTH OPTIMIZATION: Only start Firebase Auth listeners if user has paired devices
        // This prevents continuous Firebase Auth network traffic on fresh install
        if (hasPairedDevicesLocally()) {
            setupAuthStateListener()
            startSessionMonitoring()
            authListenerStarted = true
        } else {
            Log.i(TAG, "AuthManager initialized (listeners deferred - no paired devices yet)")
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
     * Called from DesktopSyncService.triggerDeferredInitialization()
     */
    fun startAuthListenersAfterPairing() {
        if (!authListenerStarted) {
            Log.i(TAG, "Starting AuthManager listeners after pairing")
            setupAuthStateListener()
            startSessionMonitoring()
            authListenerStarted = true
        }
    }

    /**
     * Setup Firebase Auth state listener
     */
    private fun setupAuthStateListener() {
        auth.addAuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            scope.launch {
                if (user != null) {
                    _authState.value = AuthState.Authenticated(user)
                    startTokenRefreshMonitoring(user)

                    // Log successful authentication
                    securityMonitor?.logEvent(SecurityEvent(
                        type = SecurityEventType.AUTH_SUCCESS,
                        message = "User authenticated successfully",
                        metadata = mapOf("userId" to user.uid)
                    ))

                    Log.i(TAG, "User authenticated: ${user.uid}")
                } else {
                    _authState.value = AuthState.Unauthenticated
                    stopTokenRefreshMonitoring()

                    // Log unauthentication
                    securityMonitor?.logEvent(SecurityEvent(
                        type = SecurityEventType.SESSION_FORCED_LOGOUT,
                        message = "User session ended",
                        metadata = mapOf("reason" to "auth_state_changed")
                    ))

                    Log.i(TAG, "User unauthenticated")
                }
            }
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
                if (authState is AuthState.Authenticated) {
                    // Never auto-logout anonymous users; this keeps device pairing stable.
                    if (authState.user.isAnonymous) {
                        continue
                    }

                    if (timeSinceLastActivity > TimeUnit.MINUTES.toMillis(SESSION_TIMEOUT_MINUTES)) {
                        // Log session timeout
                        securityMonitor?.logEvent(SecurityEvent(
                            type = SecurityEventType.SESSION_TIMEOUT,
                            message = "Session timed out due to inactivity",
                            metadata = mapOf(
                                "timeoutMinutes" to SESSION_TIMEOUT_MINUTES.toString(),
                                "inactiveMinutes" to (timeSinceLastActivity / (1000 * 60)).toString()
                            )
                        ))

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
    private fun startTokenRefreshMonitoring(user: FirebaseUser) {
        tokenRefreshJob?.cancel()
        tokenRefreshJob = scope.launch {
            while (isActive && _authState.value is AuthState.Authenticated) {
                try {
                    user.getIdToken(false).await().let { tokenResult ->
                        val expirationTime = tokenResult.expirationTimestamp
                        val currentTime = System.currentTimeMillis()
                        val timeUntilExpiry = expirationTime - currentTime

                        // Refresh token if expiring soon
                        if (timeUntilExpiry < TimeUnit.MINUTES.toMillis(TOKEN_REFRESH_BUFFER_MINUTES)) {
                            Log.d(TAG, "Refreshing authentication token")
                            user.getIdToken(true).await()
                        }

                        // Wait before next check (don't spam)
                        delay(TimeUnit.MINUTES.toMillis(5))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error monitoring token refresh", e)
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
     * Sign in anonymously with enhanced security
     */
    suspend fun signInAnonymously(): Result<String> {
        return try {
            val result = auth.signInAnonymously().await()
            val userId = result.user?.uid ?: throw Exception("No user ID returned")

            updateActivity()

            // Log successful authentication
            securityMonitor?.logEvent(SecurityEvent(
                type = SecurityEventType.AUTH_SUCCESS,
                message = "Anonymous authentication successful",
                metadata = mapOf("userId" to userId, "authMethod" to "anonymous")
            ))

            Log.i(TAG, "Anonymous sign in successful: $userId")
            Result.success(userId)

        } catch (e: Exception) {
            // Log authentication failure
            securityMonitor?.logEvent(SecurityEvent(
                type = SecurityEventType.AUTH_FAILED,
                message = "Anonymous authentication failed: ${e.message}",
                metadata = mapOf("error" to e.message.toString(), "authMethod" to "anonymous")
            ))

            Log.e(TAG, "Anonymous sign in failed", e)
            Result.failure(e)
        }
    }

    /**
     * Get current user ID with security checks
     *
     * IMPORTANT: If user recovered their account with a recovery code,
     * we return the RECOVERED user ID, not the Firebase Auth current user ID.
     * This ensures data continuity across reinstalls.
     */
    fun getCurrentUserId(): String? {
        val user = auth.currentUser
        if (user == null) {
            return null
        }

        // CRITICAL FIX: Check for recovered user ID first!
        // If user entered a recovery code, we must use the OLD user ID,
        // not the NEW anonymous user ID created during recovery.
        val recoveredUserId = recoveryCodeManager.getEffectiveUserId()
        if (recoveredUserId != null && recoveredUserId != user.uid) {
            // User recovered their account - use the recovered ID
            Log.d(TAG, "Using recovered user ID: $recoveredUserId (Firebase auth: ${user.uid})")
            updateActivity()
            return recoveredUserId
        }

        // Anonymous sessions should remain stable across background usage.
        if (user.isAnonymous) {
            updateActivity()
            return user.uid
        }

        return if (isSessionValid()) {
            updateActivity()
            user.uid
        } else {
            null
        }
    }

    /**
     * Validate that Firebase Auth state is consistent.
     *
     * CRITICAL: Firebase Auth can get into a corrupted state where:
     * - currentUser.uid returns one value (e.g., "SsBZRzinAchM2iEX9rmWY5isshR2")
     * - But the actual ID token contains a different user_id (e.g., "lT8TI15UIYS4zQZKaxRvQDoz7n72")
     *
     * This causes Cloud Functions (which use the token's user_id) to operate on
     * different data than the Android client (which uses currentUser.uid).
     *
     * IMPORTANT: We DO NOT sign out when corruption is detected, because:
     * - Anonymous users can't be recovered after sign-out
     * - Signing out would lose access to all user data
     * - Instead, we return the TOKEN's user_id which is what Cloud Functions use
     *
     * @return The token's user_id (which Cloud Functions use), ensuring consistency
     */
    suspend fun validateAndFixAuthState(): String? {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            Log.d(TAG, "validateAuthState: No current user")
            return null
        }

        try {
            // Force refresh the token to get the latest state
            val tokenResult = currentUser.getIdToken(true).await()
            val idToken = tokenResult.token

            if (idToken == null) {
                Log.w(TAG, "validateAuthState: ID token is null, using currentUser.uid")
                return currentUser.uid
            }

            // Decode JWT payload to extract actual user_id
            val parts = idToken.split(".")
            if (parts.size < 2) {
                Log.w(TAG, "validateAuthState: Invalid JWT format, using currentUser.uid")
                return currentUser.uid
            }

            val payload = String(android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE))
            val userIdMatch = Regex("\"user_id\":\"([^\"]+)\"").find(payload)
            val tokenUserId = userIdMatch?.groupValues?.get(1)

            if (tokenUserId == null) {
                Log.w(TAG, "validateAuthState: Could not extract user_id from token, using currentUser.uid")
                return currentUser.uid
            }

            // Check for mismatch: currentUser.uid != token's user_id
            if (tokenUserId != currentUser.uid) {
                Log.w(TAG, "AUTH STATE MISMATCH DETECTED!")
                Log.w(TAG, "  currentUser.uid = ${currentUser.uid}")
                Log.w(TAG, "  token.user_id   = $tokenUserId")
                Log.w(TAG, "  Using token.user_id to match Cloud Functions behavior")

                // Log security event for monitoring
                securityMonitor?.logEvent(SecurityEvent(
                    type = SecurityEventType.AUTH_SUCCESS,
                    message = "Auth state mismatch - using token user_id for consistency",
                    metadata = mapOf(
                        "currentUserUid" to currentUser.uid,
                        "tokenUserId" to tokenUserId
                    )
                ))

                // IMPORTANT: Return the TOKEN's user_id, NOT currentUser.uid
                // This ensures Android uses the same user ID as Cloud Functions
                // DO NOT sign out - that would create a new user and lose all data
                return tokenUserId
            }

            // Auth state is consistent
            Log.d(TAG, "validateAuthState: Auth state is valid (${currentUser.uid})")
            return currentUser.uid

        } catch (e: Exception) {
            Log.e(TAG, "validateAuthState: Error validating auth state", e)
            // Return current user ID as fallback - better than nothing
            return currentUser.uid
        }
    }

    /**
     * Get current user ID with validation (suspend version).
     * This should be used for critical operations that interact with Cloud Functions.
     *
     * Unlike getCurrentUserId(), this method validates that the auth state is consistent
     * before returning the user ID.
     */
    suspend fun getValidatedUserId(): String? {
        return validateAndFixAuthState()
    }

    /**
     * Check if current session is valid
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
            val user = auth.currentUser ?: return Result.failure(Exception("No authenticated user"))
            user.getIdToken(true).await()
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
            auth.signOut()
            stopTokenRefreshMonitoring()
            sessionTimeoutJob?.cancel()
            _authState.value = AuthState.Unauthenticated
            Log.i(TAG, "User logged out successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error during logout", e)
        }
    }

    /**
     * Check if user is authenticated and session is valid
     */
    fun isAuthenticated(): Boolean {
        return auth.currentUser != null && isSessionValid()
    }

    /**
     * Get current Firebase user with security validation
     */
    fun getCurrentUser(): FirebaseUser? {
        return if (isAuthenticated()) {
            updateActivity()
            auth.currentUser
        } else {
            null
        }
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
        sessionTimeoutJob?.cancel()
        tokenRefreshJob?.cancel()
        scope.cancel()
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // PHONE AUTHENTICATION INTEGRATION
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Check if user has verified their phone number.
     * Phone verification provides a stable identity across reinstalls.
     */
    fun isPhoneVerified(): Boolean {
        return phoneAuthManager.isPhoneVerified()
    }

    /**
     * Get the verified phone number (if available)
     */
    fun getVerifiedPhoneNumber(): String? {
        return phoneAuthManager.getVerifiedPhoneNumber()
    }

    /**
     * Get the phone hash for Firebase lookups (privacy-preserving)
     */
    fun getPhoneHash(): String? {
        return phoneAuthManager.getPhoneHash()
    }

    /**
     * Check if user needs phone verification.
     * Returns true if:
     * - User is not authenticated, OR
     * - User is authenticated anonymously but hasn't verified phone
     */
    fun needsPhoneVerification(): Boolean {
        val user = auth.currentUser ?: return true
        // If authenticated with phone (not anonymous), no verification needed
        if (!user.isAnonymous && user.phoneNumber != null) {
            return false
        }
        // If anonymous, check if they've verified phone through our system
        return !phoneAuthManager.isPhoneVerified()
    }

    /**
     * Get the authentication method used
     */
    fun getAuthMethod(): AuthMethod {
        val user = auth.currentUser ?: return AuthMethod.NONE
        return when {
            user.phoneNumber != null -> AuthMethod.PHONE
            user.isAnonymous -> AuthMethod.ANONYMOUS
            else -> AuthMethod.OTHER
        }
    }

    /**
     * Ensure user is authenticated (anonymously if needed, phone-verified preferred)
     * Call this before any Firebase operations that require auth.
     */
    suspend fun ensureAuthenticated(): Result<String> {
        // If already authenticated, return current user
        auth.currentUser?.let { return Result.success(it.uid) }

        // Otherwise, sign in anonymously as fallback
        return signInAnonymously()
    }
}

/**
 * Authentication method enum
 */
enum class AuthMethod {
    NONE,
    ANONYMOUS,
    PHONE,
    OTHER
}

/**
 * Authentication state sealed class
 */
sealed class AuthState {
    object Unauthenticated : AuthState()
    data class Authenticated(val user: FirebaseUser) : AuthState()
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
