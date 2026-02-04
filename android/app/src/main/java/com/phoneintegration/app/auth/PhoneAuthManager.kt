package com.phoneintegration.app.auth

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.database.FirebaseDatabase
import com.phoneintegration.app.security.SecurityEvent
import com.phoneintegration.app.security.SecurityEventType
import com.phoneintegration.app.security.SecurityMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * PhoneAuthManager handles Firebase Phone Authentication for stable user identity.
 *
 * This replaces anonymous auth as the primary authentication method to ensure:
 * 1. Stable user identity across app reinstalls
 * 2. Data persistence when re-pairing devices
 * 3. Reliable user identification for device limits
 */
class PhoneAuthManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "PhoneAuthManager"
        private const val PREFS_NAME = "syncflow_phone_auth"
        private const val KEY_PHONE_NUMBER = "verified_phone_number"
        private const val KEY_PHONE_HASH = "phone_hash"
        private const val KEY_IS_VERIFIED = "is_phone_verified"
        private const val KEY_VERIFICATION_TIME = "verification_time"
        private const val OTP_TIMEOUT_SECONDS = 60L

        @Volatile
        private var instance: PhoneAuthManager? = null

        fun getInstance(context: Context): PhoneAuthManager {
            return instance ?: synchronized(this) {
                instance ?: PhoneAuthManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance()
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val securityMonitor: SecurityMonitor? = try {
        SecurityMonitor.getInstance(context)
    } catch (e: Exception) {
        Log.w(TAG, "Failed to initialize SecurityMonitor", e)
        null
    }

    // Verification state
    private val _verificationState = MutableStateFlow<PhoneVerificationState>(PhoneVerificationState.Idle)
    val verificationState: StateFlow<PhoneVerificationState> = _verificationState.asStateFlow()

    // Stored verification ID for OTP verification
    private var storedVerificationId: String? = null
    private var resendToken: PhoneAuthProvider.ForceResendingToken? = null

    /**
     * Check if user has verified their phone number
     */
    fun isPhoneVerified(): Boolean {
        return prefs.getBoolean(KEY_IS_VERIFIED, false) &&
               prefs.getString(KEY_PHONE_NUMBER, null) != null
    }

    /**
     * Get the verified phone number
     */
    fun getVerifiedPhoneNumber(): String? {
        return if (isPhoneVerified()) {
            prefs.getString(KEY_PHONE_NUMBER, null)
        } else {
            null
        }
    }

    /**
     * Get the phone hash for Firebase lookups (privacy-preserving)
     */
    fun getPhoneHash(): String? {
        return prefs.getString(KEY_PHONE_HASH, null)
    }

    /**
     * Start phone verification process
     */
    fun startVerification(phoneNumber: String, activity: Activity) {
        if (!isValidPhoneNumber(phoneNumber)) {
            _verificationState.value = PhoneVerificationState.Error("Invalid phone number format")
            return
        }

        _verificationState.value = PhoneVerificationState.SendingCode

        val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                // Auto-verification on same device (instant verification)
                Log.i(TAG, "Phone auto-verified")
                signInWithCredential(credential, phoneNumber)
            }

            override fun onVerificationFailed(e: FirebaseException) {
                Log.e(TAG, "Verification failed", e)
                _verificationState.value = PhoneVerificationState.Error(
                    getReadableErrorMessage(e)
                )

                securityMonitor?.logEvent(SecurityEvent(
                    type = SecurityEventType.AUTH_FAILED,
                    message = "Phone verification failed: ${e.message}",
                    metadata = mapOf("error" to e.message.toString())
                ))
            }

            override fun onCodeSent(
                verificationId: String,
                token: PhoneAuthProvider.ForceResendingToken
            ) {
                Log.i(TAG, "OTP code sent")
                storedVerificationId = verificationId
                resendToken = token
                _verificationState.value = PhoneVerificationState.CodeSent(phoneNumber)
            }

            override fun onCodeAutoRetrievalTimeOut(verificationId: String) {
                Log.d(TAG, "Auto-retrieval timeout")
                storedVerificationId = verificationId
            }
        }

        val options = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(formatPhoneNumber(phoneNumber))
            .setTimeout(OTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .setActivity(activity)
            .setCallbacks(callbacks)
            .build()

        PhoneAuthProvider.verifyPhoneNumber(options)
    }

    /**
     * Resend OTP code
     */
    fun resendCode(phoneNumber: String, activity: Activity) {
        val token = resendToken
        if (token == null) {
            startVerification(phoneNumber, activity)
            return
        }

        _verificationState.value = PhoneVerificationState.SendingCode

        val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                signInWithCredential(credential, phoneNumber)
            }

            override fun onVerificationFailed(e: FirebaseException) {
                Log.e(TAG, "Resend verification failed", e)
                _verificationState.value = PhoneVerificationState.Error(
                    getReadableErrorMessage(e)
                )
            }

            override fun onCodeSent(
                verificationId: String,
                newToken: PhoneAuthProvider.ForceResendingToken
            ) {
                Log.i(TAG, "OTP code resent")
                storedVerificationId = verificationId
                resendToken = newToken
                _verificationState.value = PhoneVerificationState.CodeSent(phoneNumber)
            }
        }

        val options = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(formatPhoneNumber(phoneNumber))
            .setTimeout(OTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .setActivity(activity)
            .setCallbacks(callbacks)
            .setForceResendingToken(token)
            .build()

        PhoneAuthProvider.verifyPhoneNumber(options)
    }

    /**
     * Verify OTP code entered by user
     */
    fun verifyCode(code: String, phoneNumber: String) {
        val verificationId = storedVerificationId
        if (verificationId == null) {
            _verificationState.value = PhoneVerificationState.Error(
                "Verification session expired. Please request a new code."
            )
            return
        }

        if (code.length != 6 || !code.all { it.isDigit() }) {
            _verificationState.value = PhoneVerificationState.Error(
                "Please enter a valid 6-digit code"
            )
            return
        }

        _verificationState.value = PhoneVerificationState.Verifying

        try {
            val credential = PhoneAuthProvider.getCredential(verificationId, code)
            signInWithCredential(credential, phoneNumber)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create credential", e)
            _verificationState.value = PhoneVerificationState.Error(
                "Invalid verification code"
            )
        }
    }

    /**
     * Sign in with phone credential
     */
    private fun signInWithCredential(credential: PhoneAuthCredential, phoneNumber: String) {
        _verificationState.value = PhoneVerificationState.Verifying

        scope.launch {
            try {
                val result = auth.signInWithCredential(credential).await()
                val user = result.user

                if (user != null) {
                    // Store verified phone info
                    val formattedPhone = formatPhoneNumber(phoneNumber)
                    val phoneHash = hashPhoneNumber(formattedPhone)

                    prefs.edit().apply {
                        putString(KEY_PHONE_NUMBER, formattedPhone)
                        putString(KEY_PHONE_HASH, phoneHash)
                        putBoolean(KEY_IS_VERIFIED, true)
                        putLong(KEY_VERIFICATION_TIME, System.currentTimeMillis())
                        apply()
                    }

                    // Update user profile in Firebase
                    updateUserProfile(user.uid, formattedPhone, phoneHash)

                    Log.i(TAG, "Phone verification successful: ${user.uid}")

                    securityMonitor?.logEvent(SecurityEvent(
                        type = SecurityEventType.AUTH_SUCCESS,
                        message = "Phone verification successful",
                        metadata = mapOf("userId" to user.uid, "authMethod" to "phone")
                    ))

                    _verificationState.value = PhoneVerificationState.Verified(
                        userId = user.uid,
                        phoneNumber = formattedPhone
                    )
                } else {
                    _verificationState.value = PhoneVerificationState.Error(
                        "Verification failed. Please try again."
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Sign in failed", e)
                _verificationState.value = PhoneVerificationState.Error(
                    getReadableErrorMessage(e)
                )

                securityMonitor?.logEvent(SecurityEvent(
                    type = SecurityEventType.AUTH_FAILED,
                    message = "Phone sign in failed: ${e.message}",
                    metadata = mapOf("error" to e.message.toString())
                ))
            }
        }
    }

    /**
     * Update user profile in Firebase with phone info
     */
    private suspend fun updateUserProfile(userId: String, phoneNumber: String, phoneHash: String) {
        try {
            val userRef = database.reference.child("users").child(userId)

            // Update profile
            userRef.child("profile").updateChildren(mapOf(
                "phone" to phoneNumber,
                "phoneHash" to phoneHash,
                "phoneVerifiedAt" to System.currentTimeMillis(),
                "platform" to "android"
            )).await()

            // Create phone lookup entry for re-pairing
            database.reference.child("phone_users").child(phoneHash).updateChildren(mapOf(
                "uid" to userId,
                "lastLogin" to System.currentTimeMillis()
            )).await()

            // Initialize subscription if not exists
            val subscriptionRef = userRef.child("subscription")
            val subscriptionSnapshot = subscriptionRef.get().await()
            if (!subscriptionSnapshot.exists()) {
                subscriptionRef.setValue(mapOf(
                    "plan" to "free",
                    "deviceLimit" to 3,
                    "createdAt" to System.currentTimeMillis()
                )).await()
            }

            Log.i(TAG, "User profile updated in Firebase")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update user profile", e)
            // Non-fatal: user is still authenticated
        }
    }

    /**
     * Link anonymous account to phone number (for existing users)
     */
    suspend fun linkAnonymousToPhone(credential: PhoneAuthCredential, phoneNumber: String): Result<String> {
        return try {
            val currentUser = auth.currentUser
            if (currentUser == null || !currentUser.isAnonymous) {
                return Result.failure(Exception("No anonymous user to link"))
            }

            val result = currentUser.linkWithCredential(credential).await()
            val user = result.user ?: return Result.failure(Exception("Link failed"))

            val formattedPhone = formatPhoneNumber(phoneNumber)
            val phoneHash = hashPhoneNumber(formattedPhone)

            prefs.edit().apply {
                putString(KEY_PHONE_NUMBER, formattedPhone)
                putString(KEY_PHONE_HASH, phoneHash)
                putBoolean(KEY_IS_VERIFIED, true)
                putLong(KEY_VERIFICATION_TIME, System.currentTimeMillis())
                apply()
            }

            updateUserProfile(user.uid, formattedPhone, phoneHash)

            Log.i(TAG, "Anonymous account linked to phone: ${user.uid}")
            Result.success(user.uid)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to link anonymous account", e)
            Result.failure(e)
        }
    }

    /**
     * Reset verification state (for retry)
     */
    fun resetState() {
        _verificationState.value = PhoneVerificationState.Idle
        storedVerificationId = null
    }

    /**
     * Clear stored phone data (for logout/unpair)
     */
    fun clearPhoneData() {
        prefs.edit().clear().apply()
        storedVerificationId = null
        resendToken = null
        _verificationState.value = PhoneVerificationState.Idle
    }

    // Helper functions

    private fun isValidPhoneNumber(phone: String): Boolean {
        val cleaned = phone.replace(Regex("[^0-9+]"), "")
        return cleaned.length >= 10 && cleaned.length <= 15
    }

    private fun formatPhoneNumber(phone: String): String {
        var cleaned = phone.replace(Regex("[^0-9+]"), "")
        // Ensure E.164 format
        if (!cleaned.startsWith("+")) {
            // Assume US number if no country code
            if (cleaned.length == 10) {
                cleaned = "+1$cleaned"
            } else if (cleaned.length == 11 && cleaned.startsWith("1")) {
                cleaned = "+$cleaned"
            } else {
                cleaned = "+$cleaned"
            }
        }
        return cleaned
    }

    private fun hashPhoneNumber(phone: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(phone.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    private fun getReadableErrorMessage(e: Exception): String {
        val message = e.message?.lowercase() ?: return "Verification failed. Please try again."

        return when {
            message.contains("invalid phone") -> "Invalid phone number. Please check and try again."
            message.contains("quota") -> "Too many attempts. Please try again later."
            message.contains("blocked") -> "This phone number has been blocked. Contact support."
            message.contains("network") -> "Network error. Please check your connection."
            message.contains("invalid verification") || message.contains("invalid code") ->
                "Invalid verification code. Please check and try again."
            message.contains("expired") -> "Code expired. Please request a new one."
            message.contains("too many") -> "Too many failed attempts. Please try again later."
            else -> "Verification failed. Please try again."
        }
    }
}

/**
 * Phone verification state
 */
sealed class PhoneVerificationState {
    object Idle : PhoneVerificationState()
    object SendingCode : PhoneVerificationState()
    data class CodeSent(val phoneNumber: String) : PhoneVerificationState()
    object Verifying : PhoneVerificationState()
    data class Verified(val userId: String, val phoneNumber: String) : PhoneVerificationState()
    data class Error(val message: String) : PhoneVerificationState()
}
