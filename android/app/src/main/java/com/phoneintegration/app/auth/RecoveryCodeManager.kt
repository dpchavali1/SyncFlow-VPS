package com.phoneintegration.app.auth

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * RecoveryCodeManager handles account recovery codes.
 *
 * This provides a FREE alternative to SMS verification:
 * - Generate a unique recovery code on first launch
 * - User saves this code (screenshot, write down, etc.)
 * - If user reinstalls, they enter the code to recover their account
 * - No SMS costs, no phone number required
 */
class RecoveryCodeManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "RecoveryCodeManager"
        private const val PREFS_NAME = "syncflow_recovery"  // Backed up (included in backup_rules.xml)
        private const val PREFS_NAME_NO_BACKUP = "syncflow_recovery_local"  // NOT backed up
        private const val KEY_RECOVERY_CODE = "recovery_code"  // Legacy plaintext key
        private const val KEY_RECOVERY_CODE_CIPHERTEXT = "recovery_code_encrypted"
        private const val KEY_RECOVERY_CODE_IV = "recovery_code_iv"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_SETUP_COMPLETE = "setup_complete"
        private const val KEY_SKIPPED = "setup_skipped"
        private const val KEYSTORE_ALIAS = "syncflow_recovery_key"

        // Code format: SYNC-XXXX-XXXX-XXXX (16 chars + separators)
        private const val CODE_LENGTH = 12
        private val CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // No confusing chars (0/O, 1/I/L)

        @Volatile
        private var instance: RecoveryCodeManager? = null

        fun getInstance(context: Context): RecoveryCodeManager {
            return instance ?: synchronized(this) {
                instance ?: RecoveryCodeManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance()
    // Main prefs (backed up to Google Drive)
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    // Local-only prefs (NOT backed up - for when user disables backup)
    private val localPrefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME_NO_BACKUP, Context.MODE_PRIVATE)
    // Settings prefs to check backup preference
    private val settingsPrefs: SharedPreferences = context.getSharedPreferences("syncflow_prefs", Context.MODE_PRIVATE)

    /**
     * Check if recovery backup to Google Drive is enabled
     */
    fun isBackupEnabled(): Boolean {
        return settingsPrefs.getBoolean("recovery_backup_enabled", true)
    }

    /**
     * Get the active prefs based on backup setting
     * When backup is enabled, use prefs (backed up)
     * When backup is disabled, use localPrefs (not backed up)
     */
    private fun getActivePrefs(): SharedPreferences {
        return if (isBackupEnabled()) prefs else localPrefs
    }

    /**
     * Migrate recovery data between backed-up and local storage
     * Called when user toggles the backup setting
     */
    fun migrateStorageForBackupSetting(enableBackup: Boolean) {
        val sourcePrefs = if (enableBackup) localPrefs else prefs
        val targetPrefs = if (enableBackup) prefs else localPrefs

        // Copy all recovery data
        val ciphertext = sourcePrefs.getString(KEY_RECOVERY_CODE_CIPHERTEXT, null)
        val iv = sourcePrefs.getString(KEY_RECOVERY_CODE_IV, null)
        val userId = sourcePrefs.getString(KEY_USER_ID, null)
        val setupComplete = sourcePrefs.getBoolean(KEY_SETUP_COMPLETE, false)
        val skipped = sourcePrefs.getBoolean(KEY_SKIPPED, false)
        val legacyCode = sourcePrefs.getString(KEY_RECOVERY_CODE, null)

        // Write to target
        val editor = targetPrefs.edit()
        if (ciphertext != null) editor.putString(KEY_RECOVERY_CODE_CIPHERTEXT, ciphertext)
        if (iv != null) editor.putString(KEY_RECOVERY_CODE_IV, iv)
        if (userId != null) editor.putString(KEY_USER_ID, userId)
        if (setupComplete) editor.putBoolean(KEY_SETUP_COMPLETE, setupComplete)
        if (skipped) editor.putBoolean(KEY_SKIPPED, skipped)
        if (legacyCode != null) editor.putString(KEY_RECOVERY_CODE, legacyCode)
        editor.apply()

        // Clear source
        sourcePrefs.edit().clear().apply()

        Log.d(TAG, "Migrated recovery data: backup=${enableBackup}")
    }

    /**
     * Check if user has completed recovery setup (either set up code or skipped)
     * Checks both backed-up and local storage for compatibility
     */
    fun isSetupComplete(): Boolean {
        return prefs.getBoolean(KEY_SETUP_COMPLETE, false) ||
                prefs.getBoolean(KEY_SKIPPED, false) ||
                localPrefs.getBoolean(KEY_SETUP_COMPLETE, false) ||
                localPrefs.getBoolean(KEY_SKIPPED, false)
    }

    /**
     * Check if user skipped recovery setup
     * Checks both backed-up and local storage for compatibility
     */
    fun hasSkippedSetup(): Boolean {
        return prefs.getBoolean(KEY_SKIPPED, false) || localPrefs.getBoolean(KEY_SKIPPED, false)
    }

    /**
     * Get the stored recovery code (if any)
     * Handles both encrypted and legacy plaintext codes
     * Checks both backed-up and local storage for compatibility
     * Automatically migrates plaintext codes to encrypted format
     */
    fun getStoredRecoveryCode(): String? {
        // Try both storage locations (backed-up and local)
        for (storage in listOf(prefs, localPrefs)) {
            // Try encrypted storage first
            val ciphertext = storage.getString(KEY_RECOVERY_CODE_CIPHERTEXT, null)
            val iv = storage.getString(KEY_RECOVERY_CODE_IV, null)

            if (ciphertext != null && iv != null) {
                try {
                    return decryptRecoveryCode(EncryptedData(ciphertext, iv))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decrypt recovery code from ${if (storage == prefs) "backed-up" else "local"} storage", e)
                }
            }

            // Fallback: check for legacy plaintext (migrate if found)
            val plaintext = storage.getString(KEY_RECOVERY_CODE, null)
            if (plaintext != null) {
                Log.d(TAG, "Found legacy plaintext recovery code, migrating to encrypted storage")
                try {
                    // Migrate to encrypted storage in active prefs
                    val encrypted = encryptRecoveryCode(plaintext)
                    val activePrefs = getActivePrefs()
                    activePrefs.edit()
                        .putString(KEY_RECOVERY_CODE_CIPHERTEXT, encrypted.ciphertext)
                        .putString(KEY_RECOVERY_CODE_IV, encrypted.iv)
                        .remove(KEY_RECOVERY_CODE)  // Remove plaintext
                        .apply()
                    // Also remove from source if different
                    if (storage != activePrefs) {
                        storage.edit().remove(KEY_RECOVERY_CODE).apply()
                    }
                    Log.d(TAG, "Successfully migrated recovery code to encrypted storage")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to migrate recovery code to encrypted storage", e)
                }
                return plaintext
            }
        }
        return null
    }

    /**
     * Get the stored user ID
     * Checks both backed-up and local storage for compatibility
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
        // Remove any existing formatting and normalize
        val clean = rawCode.uppercase().replace("-", "").replace(" ", "")

        // Remove SYNC prefix if present
        val codeOnly = if (clean.startsWith("SYNC")) clean.substring(4) else clean

        // Ensure we have exactly 12 characters
        if (codeOnly.length < 12) return rawCode // Can't format, return as-is

        // Format as SYNC-XXXX-XXXX-XXXX
        return "SYNC-${codeOnly.substring(0, 4)}-${codeOnly.substring(4, 8)}-${codeOnly.substring(8, 12)}"
    }

    /**
     * Hash a recovery code for secure storage in Firebase
     */
    private fun hashCode(code: String): String {
        val normalizedCode = code.uppercase().replace("-", "").replace(" ", "")
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(normalizedCode.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Data class to hold encrypted recovery code data
     */
    private data class EncryptedData(val ciphertext: String, val iv: String)

    /**
     * Get or create encryption key from Android Keystore
     */
    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)

        // Try to get existing key
        val existingKey = keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existingKey != null) {
            return existingKey.secretKey
        }

        // Generate new key
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

    /**
     * Encrypt recovery code using Android Keystore
     */
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

    /**
     * Decrypt recovery code using Android Keystore
     */
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
     * Returns the generated code IMMEDIATELY - Firebase sync happens in background
     */
    suspend fun setupRecoveryCode(): Result<String> {
        return try {
            // Ensure user is signed in (anonymous is fine) - with timeout
            var user = auth.currentUser
            if (user == null) {
                Log.d(TAG, "No user, signing in anonymously")
                try {
                    val result = withTimeout(5000) { auth.signInAnonymously().await() }
                    user = result.user
                } catch (e: Exception) {
                    Log.e(TAG, "Anonymous sign-in failed/timeout, continuing anyway", e)
                }
            }

            val userId = user?.uid ?: run {
                // If we still don't have a user, generate a local-only code
                Log.w(TAG, "No Firebase user, generating local-only code")
                val code = generateRecoveryCode()

                // Encrypt before storing (use active prefs based on backup setting)
                val encrypted = encryptRecoveryCode(code)
                val activePrefs = getActivePrefs()
                activePrefs.edit()
                    .putString(KEY_RECOVERY_CODE_CIPHERTEXT, encrypted.ciphertext)
                    .putString(KEY_RECOVERY_CODE_IV, encrypted.iv)
                    .putBoolean(KEY_SETUP_COMPLETE, true)
                    .putBoolean(KEY_SKIPPED, false)
                    .remove(KEY_RECOVERY_CODE)  // Remove legacy plaintext key
                    .apply()
                return Result.success(code)
            }

            Log.d(TAG, "Setting up recovery code for user: $userId (backup=${isBackupEnabled()})")

            // Generate new code INSTANTLY
            val code = generateRecoveryCode()
            val codeHash = hashCode(code)

            // Encrypt before storing locally FIRST (instant) - user sees code immediately
            // Use active prefs based on backup setting
            val encrypted = encryptRecoveryCode(code)
            val activePrefs = getActivePrefs()
            activePrefs.edit()
                .putString(KEY_RECOVERY_CODE_CIPHERTEXT, encrypted.ciphertext)
                .putString(KEY_RECOVERY_CODE_IV, encrypted.iv)
                .putString(KEY_USER_ID, userId)
                .putBoolean(KEY_SETUP_COMPLETE, true)
                .putBoolean(KEY_SKIPPED, false)
                .remove(KEY_RECOVERY_CODE)  // Remove legacy plaintext key
                .apply()

            Log.d(TAG, "Recovery code generated and encrypted: ${code.take(9)}... - syncing to Firebase in background")

            // Sync to Firebase in BACKGROUND (fire and forget - don't block UI)
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // Store mapping in Firebase: recovery_codes/{hash} -> userId
                    val recoveryRef = database.reference.child("recovery_codes").child(codeHash)
                    recoveryRef.setValue(mapOf(
                        "userId" to userId,
                        "createdAt" to System.currentTimeMillis(),
                        "platform" to "android"
                    ))

                    // Also store the code under the user's data for easy access later
                    val userRecoveryRef = database.reference.child("users").child(userId).child("recovery_info")
                    userRecoveryRef.setValue(mapOf(
                        "code" to code,
                        "createdAt" to System.currentTimeMillis(),
                        "codeHash" to codeHash
                    ))

                    Log.d(TAG, "Recovery code synced to Firebase successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Background Firebase sync failed (code still works locally)", e)
                }
            }

            Result.success(code)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up recovery code", e)
            Result.failure(e)
        }
    }

    /**
     * Recover account using a recovery code
     * Returns the user ID if successful
     * Uses Cloud Function for faster recovery (avoids Firebase offline mode issues)
     */
    suspend fun recoverWithCode(code: String): Result<String> {
        return try {
            val normalizedCode = code.uppercase().replace(" ", "").replace("-", "")
            val codeHash = hashCode(normalizedCode)

            Log.d(TAG, "Attempting recovery with code hash: ${codeHash.take(8)}...")

            // Use Cloud Function for fast recovery
            val functions = com.google.firebase.functions.FirebaseFunctions.getInstance()
            val result = functions
                .getHttpsCallable("recoverAccount")
                .call(mapOf("codeHash" to codeHash))
                .await()

            val data = result.data as? Map<*, *>
            val success = data?.get("success") as? Boolean ?: false
            val userId = data?.get("userId") as? String
            val customToken = data?.get("customToken") as? String

            if (!success || userId == null) {
                Log.w(TAG, "Recovery failed: success=$success, userId=$userId")
                return Result.failure(Exception("Invalid recovery code. Please check and try again."))
            }

            Log.d(TAG, "Found userId via Cloud Function: $userId")

            // Save the temporary user ID (if any) before signing out
            val tempUserId = auth.currentUser?.uid

            // CRITICAL FIX: Sign in with custom token AS the recovered user
            // This makes Firebase Auth use the OLD user ID instead of creating a NEW one
            if (customToken != null) {
                try {
                    Log.d(TAG, "Signing in with custom token for recovered user: $userId")
                    auth.signInWithCustomToken(customToken).await()
                    Log.i(TAG, "✅ Signed in as recovered user: ${auth.currentUser?.uid}")

                    // Clean up temporary user data (if any)
                    if (tempUserId != null && tempUserId != userId) {
                        Log.d(TAG, "Cleaning up temporary user: $tempUserId")
                        try {
                            // First, unregister this device from the temp user
                            val deviceId = android.provider.Settings.Secure.getString(
                                context.contentResolver,
                                android.provider.Settings.Secure.ANDROID_ID
                            )

                            val tempDeviceRef = database.reference
                                .child("users")
                                .child(tempUserId)
                                .child("devices")
                                .child(deviceId)

                            try {
                                tempDeviceRef.removeValue().await()
                                Log.d(TAG, "Unregistered device from temp user")
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to unregister device: ${e.message}")
                            }

                            // Now cleanup the temp user via Cloud Function
                            val functions = com.google.firebase.functions.FirebaseFunctions.getInstance()
                            functions.getHttpsCallable("cleanupTemporaryUser")
                                .call(mapOf("tempUserId" to tempUserId))
                                .await()
                            Log.i(TAG, "✅ Temporary user data deleted: $tempUserId")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to cleanup temp user (non-critical): ${e.message}")
                            // Non-critical - continue anyway
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to sign in with custom token: ${e.message}", e)
                    // Fall back to anonymous sign-in if custom token fails
                    if (auth.currentUser == null) {
                        auth.signInAnonymously().await()
                    }
                }
            } else {
                // Backward compatibility: if Cloud Function doesn't return customToken (old version)
                Log.w(TAG, "No custom token returned, falling back to anonymous sign-in")
                if (auth.currentUser == null) {
                    auth.signInAnonymously().await()
                }
            }

            // Store the recovered userId - the app will use this for data access
            // Format the code properly before storing (SYNC-XXXX-XXXX-XXXX)
            val formattedCode = formatRecoveryCode(normalizedCode)
            prefs.edit()
                .putString(KEY_RECOVERY_CODE, formattedCode)
                .putString(KEY_USER_ID, userId)
                .putBoolean(KEY_SETUP_COMPLETE, true)
                .putBoolean(KEY_SKIPPED, false)
                .apply()

            Log.d(TAG, "Recovery successful for user: $userId (Firebase Auth: ${auth.currentUser?.uid})")
            Result.success(userId)
        } catch (e: Exception) {
            Log.e(TAG, "Error recovering with code", e)
            val message = when {
                e.message?.contains("not-found") == true -> "Invalid recovery code. Please check and try again."
                e.message?.contains("UNAVAILABLE") == true -> "Network error. Please check your connection."
                else -> e.message ?: "Recovery failed"
            }
            Result.failure(Exception(message))
        }
    }

    /**
     * Skip recovery setup (user accepts data loss risk)
     */
    suspend fun skipSetup(): Result<String> {
        return try {
            // Sign in anonymously
            var user = auth.currentUser
            if (user == null) {
                val result = auth.signInAnonymously().await()
                user = result.user
            }

            val userId = user?.uid ?: return Result.failure(Exception("Failed to sign in"))

            // Mark as skipped
            prefs.edit()
                .putString(KEY_USER_ID, userId)
                .putBoolean(KEY_SKIPPED, true)
                .putBoolean(KEY_SETUP_COMPLETE, false)
                .apply()

            Log.d(TAG, "Recovery setup skipped, using anonymous auth: $userId")
            Result.success(userId)
        } catch (e: Exception) {
            Log.e(TAG, "Error skipping setup", e)
            Result.failure(e)
        }
    }

    /**
     * Get the effective user ID (recovered or current)
     * This should be used instead of FirebaseAuth.currentUser.uid
     */
    fun getEffectiveUserId(): String? {
        // First check if we have a recovered userId
        val storedUserId = prefs.getString(KEY_USER_ID, null)
        if (storedUserId != null) {
            return storedUserId
        }
        // Fall back to current Firebase user
        return auth.currentUser?.uid
    }

    /**
     * Fetch recovery code from Firebase (if local copy is lost)
     */
    suspend fun fetchRecoveryCodeFromFirebase(): String? {
        return try {
            val userId = getEffectiveUserId() ?: return null
            val userRecoveryRef = database.reference.child("users").child(userId).child("recovery_info")
            val snapshot = userRecoveryRef.get().await()

            if (snapshot.exists()) {
                val code = snapshot.child("code").getValue(String::class.java)
                // Update local cache if found
                if (code != null) {
                    prefs.edit().putString(KEY_RECOVERY_CODE, code).apply()
                }
                code
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching recovery code from Firebase", e)
            null
        }
    }

    /**
     * Get recovery code (from local cache or Firebase)
     * Always returns formatted code (SYNC-XXXX-XXXX-XXXX)
     */
    suspend fun getRecoveryCode(): String? {
        // First try local cache
        val localCode = getStoredRecoveryCode()
        if (localCode != null) {
            // Ensure proper formatting when returning
            return formatRecoveryCode(localCode)
        }
        // Try fetching from Firebase
        val firebaseCode = fetchRecoveryCodeFromFirebase()
        return firebaseCode?.let { formatRecoveryCode(it) }
    }

    /**
     * Check for backed-up recovery code and auto-restore account if found.
     * This should be called early in app initialization (before showing UI).
     *
     * Returns:
     * - Success(userId) if recovery code was found and account restored
     * - Success(null) if no backup found (fresh install, proceed normally)
     * - Failure(exception) if recovery failed (show error to user)
     */
    suspend fun checkAndRestoreFromBackup(): Result<String?> {
        return try {
            Log.d(TAG, "Checking for backed-up recovery code...")

            // Check if we already have a valid session
            val existingUserId = getStoredUserId()
            val currentUser = auth.currentUser

            if (existingUserId != null && currentUser != null) {
                Log.d(TAG, "Already have valid session: $existingUserId")
                return Result.success(existingUserId)
            }

            // Check if Android Auto Backup restored a recovery code
            val backedUpCode = getStoredRecoveryCode()

            if (backedUpCode == null) {
                Log.d(TAG, "No backed-up recovery code found (fresh install)")
                return Result.success(null)
            }

            // We have a backed-up code but no valid session - restore account
            Log.d(TAG, "Found backed-up recovery code, attempting auto-restore...")

            val recoveryResult = recoverWithCode(backedUpCode)

            if (recoveryResult.isSuccess) {
                val userId = recoveryResult.getOrNull()
                Log.i(TAG, "Auto-restore successful! Recovered user: $userId")
                return Result.success(userId)
            } else {
                // Recovery failed - clear corrupted backup data
                Log.e(TAG, "Auto-restore failed: ${recoveryResult.exceptionOrNull()?.message}")
                clearRecoveryData()
                return Result.failure(recoveryResult.exceptionOrNull() ?: Exception("Recovery failed"))
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error during auto-restore check", e)
            Result.failure(e)
        }
    }

    /**
     * Clear all recovery data (for testing/logout)
     */
    fun clearRecoveryData() {
        prefs.edit().clear().apply()
    }
}
