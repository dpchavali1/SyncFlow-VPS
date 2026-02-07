package com.phoneintegration.app.auth

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.phoneintegration.app.vps.VPSAuthManager
import com.phoneintegration.app.vps.VPSClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * RecoveryCodeManager - VPS Backend Only
 *
 * Handles account recovery codes using VPS backend.
 * This provides a FREE alternative to SMS verification:
 * - Generate a unique recovery code on first launch
 * - User saves this code (screenshot, write down, etc.)
 * - If user reinstalls, they enter the code to recover their account
 */
class RecoveryCodeManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "RecoveryCodeManager"
        private const val PREFS_NAME = "syncflow_recovery"
        private const val PREFS_NAME_NO_BACKUP = "syncflow_recovery_local"
        private const val KEY_RECOVERY_CODE_CIPHERTEXT = "recovery_code_encrypted"
        private const val KEY_RECOVERY_CODE_IV = "recovery_code_iv"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_SETUP_COMPLETE = "setup_complete"
        private const val KEY_SKIPPED = "setup_skipped"
        private const val KEYSTORE_ALIAS = "syncflow_recovery_key"

        // Code format: SYNC-XXXX-XXXX-XXXX (16 chars + separators)
        private const val CODE_LENGTH = 12
        private val CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // No confusing chars

        @Volatile
        private var instance: RecoveryCodeManager? = null

        fun getInstance(context: Context): RecoveryCodeManager {
            return instance ?: synchronized(this) {
                instance ?: RecoveryCodeManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val vpsAuthManager = VPSAuthManager.getInstance(context)
    private val vpsClient = VPSClient.getInstance(context)
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val localPrefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME_NO_BACKUP, Context.MODE_PRIVATE)
    private val settingsPrefs: SharedPreferences = context.getSharedPreferences("syncflow_prefs", Context.MODE_PRIVATE)

    /**
     * Check if recovery backup is enabled
     */
    fun isBackupEnabled(): Boolean {
        return settingsPrefs.getBoolean("recovery_backup_enabled", true)
    }

    private fun getActivePrefs(): SharedPreferences {
        return if (isBackupEnabled()) prefs else localPrefs
    }

    /**
     * Migrate recovery data between backed-up and local storage
     */
    fun migrateStorageForBackupSetting(enableBackup: Boolean) {
        val sourcePrefs = if (enableBackup) localPrefs else prefs
        val targetPrefs = if (enableBackup) prefs else localPrefs

        val ciphertext = sourcePrefs.getString(KEY_RECOVERY_CODE_CIPHERTEXT, null)
        val iv = sourcePrefs.getString(KEY_RECOVERY_CODE_IV, null)
        val userId = sourcePrefs.getString(KEY_USER_ID, null)
        val setupComplete = sourcePrefs.getBoolean(KEY_SETUP_COMPLETE, false)
        val skipped = sourcePrefs.getBoolean(KEY_SKIPPED, false)

        val editor = targetPrefs.edit()
        if (ciphertext != null) editor.putString(KEY_RECOVERY_CODE_CIPHERTEXT, ciphertext)
        if (iv != null) editor.putString(KEY_RECOVERY_CODE_IV, iv)
        if (userId != null) editor.putString(KEY_USER_ID, userId)
        if (setupComplete) editor.putBoolean(KEY_SETUP_COMPLETE, setupComplete)
        if (skipped) editor.putBoolean(KEY_SKIPPED, skipped)
        editor.apply()

        sourcePrefs.edit().clear().apply()
        Log.d(TAG, "Migrated recovery data: backup=$enableBackup")
    }

    /**
     * Check if user has completed recovery setup
     */
    fun isSetupComplete(): Boolean {
        return prefs.getBoolean(KEY_SETUP_COMPLETE, false) ||
                prefs.getBoolean(KEY_SKIPPED, false) ||
                localPrefs.getBoolean(KEY_SETUP_COMPLETE, false) ||
                localPrefs.getBoolean(KEY_SKIPPED, false)
    }

    /**
     * Check if user skipped recovery setup
     */
    fun hasSkippedSetup(): Boolean {
        return prefs.getBoolean(KEY_SKIPPED, false) || localPrefs.getBoolean(KEY_SKIPPED, false)
    }

    /**
     * Get the stored recovery code
     */
    fun getStoredRecoveryCode(): String? {
        for (storage in listOf(prefs, localPrefs)) {
            val ciphertext = storage.getString(KEY_RECOVERY_CODE_CIPHERTEXT, null)
            val iv = storage.getString(KEY_RECOVERY_CODE_IV, null)

            if (ciphertext != null && iv != null) {
                try {
                    return decryptRecoveryCode(EncryptedData(ciphertext, iv))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decrypt recovery code", e)
                }
            }
        }
        return null
    }

    /**
     * Get the current recovery code, generating one if needed.
     */
    suspend fun getRecoveryCode(): String? {
        val stored = getStoredRecoveryCode()
        if (stored != null) return stored
        return setupRecoveryCode().getOrNull()
    }

    /**
     * Get the stored user ID
     */
    fun getStoredUserId(): String? {
        return prefs.getString(KEY_USER_ID, null)
            ?: localPrefs.getString(KEY_USER_ID, null)
    }

    /**
     * Generate a new recovery code
     */
    fun generateRecoveryCode(): String {
        val random = SecureRandom()
        val code = StringBuilder()

        repeat(CODE_LENGTH) {
            code.append(CODE_CHARS[random.nextInt(CODE_CHARS.length)])
        }

        return formatRecoveryCode(code.toString())
    }

    /**
     * Format a raw recovery code as SYNC-XXXX-XXXX-XXXX
     */
    fun formatRecoveryCode(rawCode: String): String {
        val clean = rawCode.uppercase().replace("-", "").replace(" ", "")
        val codeOnly = if (clean.startsWith("SYNC")) clean.substring(4) else clean

        if (codeOnly.length < 12) return rawCode

        return "SYNC-${codeOnly.substring(0, 4)}-${codeOnly.substring(4, 8)}-${codeOnly.substring(8, 12)}"
    }

    private fun hashCode(code: String): String {
        val normalizedCode = code.uppercase().replace("-", "").replace(" ", "")
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(normalizedCode.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private data class EncryptedData(val ciphertext: String, val iv: String)

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)

        val existingKey = keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existingKey != null) {
            return existingKey.secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        val keyGenParameterSpec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(false)
            .build()

        keyGenerator.init(keyGenParameterSpec)
        return keyGenerator.generateKey()
    }

    private fun encryptRecoveryCode(code: String): EncryptedData {
        val key = getOrCreateSecretKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)

        val iv = cipher.iv
        val encrypted = cipher.doFinal(code.toByteArray())

        return EncryptedData(
            ciphertext = Base64.encodeToString(encrypted, Base64.NO_WRAP),
            iv = Base64.encodeToString(iv, Base64.NO_WRAP)
        )
    }

    private fun decryptRecoveryCode(encryptedData: EncryptedData): String {
        val key = getOrCreateSecretKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")

        val iv = Base64.decode(encryptedData.iv, Base64.NO_WRAP)
        val encrypted = Base64.decode(encryptedData.ciphertext, Base64.NO_WRAP)

        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        val decrypted = cipher.doFinal(encrypted)

        return String(decrypted)
    }

    /**
     * Set up a new recovery code for the current user
     */
    suspend fun setupRecoveryCode(): Result<String> {
        return try {
            // Ensure user is authenticated with VPS
            var userId = vpsAuthManager.getCurrentUserId()
            if (userId == null) {
                Log.d(TAG, "No user, signing in anonymously via VPS")
                val result = vpsAuthManager.signInAnonymously()
                if (result.isSuccess) {
                    userId = result.getOrNull()
                } else {
                    Log.w(TAG, "VPS sign-in failed, generating local-only code")
                }
            }

            if (userId == null) {
                // Generate local-only code
                val code = generateRecoveryCode()
                val encrypted = encryptRecoveryCode(code)
                val activePrefs = getActivePrefs()
                activePrefs.edit()
                    .putString(KEY_RECOVERY_CODE_CIPHERTEXT, encrypted.ciphertext)
                    .putString(KEY_RECOVERY_CODE_IV, encrypted.iv)
                    .putBoolean(KEY_SETUP_COMPLETE, true)
                    .putBoolean(KEY_SKIPPED, false)
                    .apply()
                return Result.success(code)
            }

            Log.d(TAG, "Setting up recovery code for VPS user: $userId")

            val code = generateRecoveryCode()

            // Encrypt and store locally
            val encrypted = encryptRecoveryCode(code)
            val activePrefs = getActivePrefs()
            activePrefs.edit()
                .putString(KEY_RECOVERY_CODE_CIPHERTEXT, encrypted.ciphertext)
                .putString(KEY_RECOVERY_CODE_IV, encrypted.iv)
                .putString(KEY_USER_ID, userId)
                .putBoolean(KEY_SETUP_COMPLETE, true)
                .putBoolean(KEY_SKIPPED, false)
                .apply()

            Log.d(TAG, "Recovery code generated: ${code.take(9)}...")

            // Note: VPS server can optionally store recovery code hash for account recovery
            // This would require adding a /api/recovery endpoint to the VPS server

            Result.success(code)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up recovery code", e)
            Result.failure(e)
        }
    }

    /**
     * Recover account using a recovery code
     * Note: In VPS mode, recovery requires the code to be stored locally
     * For cross-device recovery, VPS server would need a recovery endpoint
     */
    suspend fun recoverWithCode(code: String): Result<String> {
        return try {
            val normalizedCode = code.uppercase().replace(" ", "").replace("-", "")

            Log.d(TAG, "Attempting recovery with code...")

            // Check if we have the code stored locally
            val storedCode = getStoredRecoveryCode()
            val storedCodeNormalized = storedCode?.uppercase()?.replace("-", "")?.replace(" ", "")
                ?.let { if (it.startsWith("SYNC")) it.substring(4) else it }

            if (storedCodeNormalized != null && storedCodeNormalized == normalizedCode) {
                // Code matches - restore from local storage
                val userId = getStoredUserId()
                if (userId != null) {
                    Log.d(TAG, "Recovery successful from local storage: $userId")
                    return Result.success(userId)
                }
            }

            // For VPS, we would need to call a recovery endpoint
            // This would be: POST /api/auth/recover { codeHash: hash }
            // For now, return error since VPS recovery endpoint doesn't exist yet
            Log.w(TAG, "VPS recovery endpoint not implemented - code not found locally")
            Result.failure(Exception("Invalid recovery code or code not found. Please check and try again."))
        } catch (e: Exception) {
            Log.e(TAG, "Error recovering with code", e)
            Result.failure(Exception(e.message ?: "Recovery failed"))
        }
    }

    /**
     * Skip recovery setup (user accepts data loss risk)
     */
    suspend fun skipSetup(): Result<String> {
        return try {
            var userId = vpsAuthManager.getCurrentUserId()
            if (userId == null) {
                val result = vpsAuthManager.signInAnonymously()
                if (result.isSuccess) {
                    userId = result.getOrNull()
                }
            }

            if (userId == null) {
                return Result.failure(Exception("Failed to authenticate with VPS"))
            }

            // Mark as skipped
            prefs.edit()
                .putString(KEY_USER_ID, userId)
                .putBoolean(KEY_SKIPPED, true)
                .putBoolean(KEY_SETUP_COMPLETE, false)
                .apply()

            Log.d(TAG, "Recovery setup skipped, using VPS auth: $userId")
            Result.success(userId)
        } catch (e: Exception) {
            Log.e(TAG, "Error skipping setup", e)
            Result.failure(e)
        }
    }

    /**
     * Get the effective user ID (recovered or current)
     */
    fun getEffectiveUserId(): String? {
        // First check if we have a recovered userId
        val storedUserId = prefs.getString(KEY_USER_ID, null)
        if (storedUserId != null) {
            return storedUserId
        }
        // Fall back to current VPS user
        return vpsAuthManager.getCurrentUserId()
    }

    /**
     * Check if there's a backed-up recovery code that can restore the account
     */
    suspend fun checkAndRestoreFromBackup(): Result<String?> {
        return try {
            // Check if we have a recovery code in backed-up storage
            val storedCode = getStoredRecoveryCode()
            val storedUserId = getStoredUserId()

            if (storedCode != null && storedUserId != null) {
                // We have a backed-up recovery code
                Log.d(TAG, "Found backed-up recovery code for user: $storedUserId")

                // Verify with VPS if the user still exists
                // For now, just return the stored user ID
                return Result.success(storedUserId)
            }

            Result.success(null)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for backup recovery", e)
            Result.failure(e)
        }
    }

    /**
     * Clear all recovery data (for testing or account deletion)
     */
    fun clearRecoveryData() {
        prefs.edit().clear().apply()
        localPrefs.edit().clear().apply()
        Log.d(TAG, "Recovery data cleared")
    }
}
