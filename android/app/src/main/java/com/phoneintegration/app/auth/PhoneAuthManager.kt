package com.phoneintegration.app.auth

import android.app.Activity
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * PhoneAuthManager - VPS Backend Only (Stub Implementation)
 *
 * Phone authentication is NOT available in VPS mode.
 * This class is kept for API compatibility but all methods return stub values.
 *
 * In VPS mode, authentication is handled by:
 * - VPSAuthManager for JWT-based authentication
 * - RecoveryCodeManager for account recovery
 */
class PhoneAuthManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "PhoneAuthManager"

        @Volatile
        private var instance: PhoneAuthManager? = null

        fun getInstance(context: Context): PhoneAuthManager {
            return instance ?: synchronized(this) {
                instance ?: PhoneAuthManager(context.applicationContext).also { instance = it }
            }
        }
    }

    // Verification state - always idle in VPS mode
    private val _verificationState = MutableStateFlow<PhoneVerificationState>(PhoneVerificationState.Idle)
    val verificationState: StateFlow<PhoneVerificationState> = _verificationState.asStateFlow()

    init {
        Log.i(TAG, "PhoneAuthManager initialized (VPS mode - phone auth not available)")
    }

    /**
     * Check if user has verified their phone number.
     * Note: Phone verification is not available in VPS mode.
     */
    fun isPhoneVerified(): Boolean {
        return false // Not available in VPS mode
    }

    /**
     * Get the verified phone number.
     * Note: Phone verification is not available in VPS mode.
     */
    fun getVerifiedPhoneNumber(): String? {
        return null // Not available in VPS mode
    }

    /**
     * Get the phone hash for lookups.
     * Note: Phone verification is not available in VPS mode.
     */
    fun getPhoneHash(): String? {
        return null // Not available in VPS mode
    }

    /**
     * Start phone verification process.
     * Note: Phone verification is not available in VPS mode.
     */
    fun startVerification(phoneNumber: String, activity: Activity) {
        Log.w(TAG, "Phone verification is not available in VPS mode")
        _verificationState.value = PhoneVerificationState.Error(
            "Phone verification is not available. Please use recovery codes for account recovery."
        )
    }

    /**
     * Resend OTP code.
     * Note: Phone verification is not available in VPS mode.
     */
    fun resendCode(phoneNumber: String, activity: Activity) {
        Log.w(TAG, "Phone verification is not available in VPS mode")
        _verificationState.value = PhoneVerificationState.Error(
            "Phone verification is not available. Please use recovery codes for account recovery."
        )
    }

    /**
     * Verify OTP code entered by user.
     * Note: Phone verification is not available in VPS mode.
     */
    fun verifyCode(code: String, phoneNumber: String) {
        Log.w(TAG, "Phone verification is not available in VPS mode")
        _verificationState.value = PhoneVerificationState.Error(
            "Phone verification is not available. Please use recovery codes for account recovery."
        )
    }

    /**
     * Reset verification state.
     */
    fun resetState() {
        _verificationState.value = PhoneVerificationState.Idle
    }

    /**
     * Clear stored phone data.
     */
    fun clearPhoneData() {
        _verificationState.value = PhoneVerificationState.Idle
        Log.d(TAG, "Phone data cleared (no-op in VPS mode)")
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
