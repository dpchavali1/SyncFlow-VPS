package com.phoneintegration.app.e2ee

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.telephony.TelephonyManager
import android.util.Base64
import android.util.Log
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkJsonProtoKeysetFormat
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.hybrid.HybridKeyTemplates
import com.phoneintegration.app.vps.VPSClient
import kotlinx.coroutines.runBlocking
import java.nio.charset.StandardCharsets
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * E2EE Manager using Google Tink for hybrid encryption.
 * This provides forward secrecy and authenticated encryption.
 *
 * Private keys are encrypted using Android Keystore before storage.
 */
class SignalProtocolManager(private val context: Context) {

    companion object {
        private const val TAG = "E2EEManager"
        private const val PREFS_NAME = "e2ee_prefs"
        private const val KEY_PRIVATE_KEYSET = "private_keyset"
        private const val KEY_PRIVATE_KEYSET_IV = "private_keyset_iv"
        private const val KEY_PUBLIC_KEYSET = "public_keyset"
        private const val KEY_INITIALIZED = "initialized"
        private const val KEY_USES_KEYSTORE = "uses_keystore"
        private const val KEY_ECDH_PRIVATE = "ecdh_private"
        private const val KEY_ECDH_PRIVATE_IV = "ecdh_private_iv"
        private const val KEY_ECDH_PUBLIC = "ecdh_public_x963"
        private const val KEY_ECDH_INITIALIZED = "ecdh_initialized"
        private const val E2EE_V2_CONTEXT = "SyncFlow-E2EE-v2"

        // Android Keystore constants
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEYSTORE_ALIAS = "syncflow_e2ee_master_key"
        private const val GCM_TAG_LENGTH = 128
    }

    private val vpsClient = VPSClient.getInstance(context)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var privateKeysetHandle: KeysetHandle? = null
    private var encryptionPrimitive: HybridDecrypt? = null
    private var ecdhKeyPair: KeyPair? = null

    init {
        // Initialize Tink
        HybridConfig.register()
    }

    /**
     * Get or create a master key in Android Keystore for encrypting the Tink private key
     */
    private fun getOrCreateMasterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        // Check if key already exists
        if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            return keyStore.getKey(KEYSTORE_ALIAS, null) as SecretKey
        }

        // Generate new key in Keystore
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )

        val keySpec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setUnlockedDeviceRequired(false)
                    setIsStrongBoxBacked(false) // Fallback for devices without StrongBox
                }
            }
            .build()

        keyGenerator.init(keySpec)
        return keyGenerator.generateKey()
    }

    /**
     * Encrypt data using Android Keystore
     */
    private fun encryptWithKeystore(plaintext: String): Pair<String, String> {
        val masterKey = getOrCreateMasterKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey)

        val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        val iv = cipher.iv

        return Pair(
            Base64.encodeToString(ciphertext, Base64.NO_WRAP),
            Base64.encodeToString(iv, Base64.NO_WRAP)
        )
    }

    /**
     * Decrypt data using Android Keystore
     */
    private fun decryptWithKeystore(encryptedData: String, ivBase64: String): String {
        val masterKey = getOrCreateMasterKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")

        val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, masterKey, spec)

        val ciphertext = Base64.decode(encryptedData, Base64.NO_WRAP)
        val plaintext = cipher.doFinal(ciphertext)

        return String(plaintext, StandardCharsets.UTF_8)
    }

    fun initializeKeys() {
        if (isInitialized()) {
            loadExistingKeys()
            ensureEcdhKeys()
            return
        }

        try {
            // Generate new key pair using ECIES with HKDF and AES-GCM
            val keysetHandle = KeysetHandle.generateNew(
                HybridKeyTemplates.ECIES_P256_HKDF_HMAC_SHA256_AES128_GCM
            )

            // Get public keyset for sharing
            val publicKeysetHandle = keysetHandle.publicKeysetHandle

            // Serialize keys for storage
            val privateKeysetJson = TinkJsonProtoKeysetFormat.serializeKeyset(
                keysetHandle,
                InsecureSecretKeyAccess.get()
            )
            val publicKeysetJson = TinkJsonProtoKeysetFormat.serializeKeysetWithoutSecret(
                publicKeysetHandle
            )

            // Encrypt private key using Android Keystore before storing
            val (encryptedPrivateKey, iv) = encryptWithKeystore(privateKeysetJson)

            prefs.edit()
                .putString(KEY_PRIVATE_KEYSET, encryptedPrivateKey)
                .putString(KEY_PRIVATE_KEYSET_IV, iv)
                .putString(KEY_PUBLIC_KEYSET, publicKeysetJson)
                .putBoolean(KEY_INITIALIZED, true)
                .putBoolean(KEY_USES_KEYSTORE, true)
                .apply()

            privateKeysetHandle = keysetHandle
            encryptionPrimitive = keysetHandle.getPrimitive(HybridDecrypt::class.java)

            // Publish public key to VPS
            publishPublicKeyToVps(publicKeysetJson)

            registerUser()
            ensureEcdhKeys()

            Log.d(TAG, "E2EE keys initialized successfully (protected by Android Keystore)")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing E2EE keys", e)
        }
    }

    private fun loadExistingKeys() {
        try {
            val encryptedPrivateKeyset = prefs.getString(KEY_PRIVATE_KEYSET, null) ?: return
            val usesKeystore = prefs.getBoolean(KEY_USES_KEYSTORE, false)

            val privateKeysetJson = if (usesKeystore) {
                // Decrypt using Android Keystore
                val iv = prefs.getString(KEY_PRIVATE_KEYSET_IV, null) ?: return
                decryptWithKeystore(encryptedPrivateKeyset, iv)
            } else {
                // Legacy: migrate to Keystore-protected storage
                migrateToKeystoreProtection(encryptedPrivateKeyset)
                encryptedPrivateKeyset
            }

            privateKeysetHandle = TinkJsonProtoKeysetFormat.parseKeyset(
                privateKeysetJson,
                InsecureSecretKeyAccess.get()
            )
            encryptionPrimitive = privateKeysetHandle?.getPrimitive(HybridDecrypt::class.java)

            Log.d(TAG, "E2EE keys loaded from storage (Keystore protected: $usesKeystore)")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading E2EE keys", e)
        }
    }

    private fun ensureEcdhKeys() {
        if (prefs.getBoolean(KEY_ECDH_INITIALIZED, false)) {
            loadEcdhKeys()
            publishDevicePublicKey()
            return
        }

        try {
            val keyPair = generateEcdhKeyPair()
            val privateKeyBytes = keyPair.private.encoded
            val (encryptedPrivateKey, iv) = encryptWithKeystore(
                Base64.encodeToString(privateKeyBytes, Base64.NO_WRAP)
            )
            val publicKeyX963 = encodeX963PublicKey(keyPair.public as ECPublicKey)

            prefs.edit()
                .putString(KEY_ECDH_PRIVATE, encryptedPrivateKey)
                .putString(KEY_ECDH_PRIVATE_IV, iv)
                .putString(KEY_ECDH_PUBLIC, Base64.encodeToString(publicKeyX963, Base64.NO_WRAP))
                .putBoolean(KEY_ECDH_INITIALIZED, true)
                .apply()

            ecdhKeyPair = keyPair
            publishDevicePublicKey()
            Log.d(TAG, "E2EE v2 device keys initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing E2EE v2 keys", e)
        }
    }

    fun ensureDeviceKeysPublished() {
        ensureEcdhKeys()
    }

    private fun loadEcdhKeys() {
        try {
            val encryptedPrivate = prefs.getString(KEY_ECDH_PRIVATE, null) ?: return
            val iv = prefs.getString(KEY_ECDH_PRIVATE_IV, null) ?: return
            val privateKeyBase64 = decryptWithKeystore(encryptedPrivate, iv)
            val privateKeyBytes = Base64.decode(privateKeyBase64, Base64.NO_WRAP)
            val publicKeyBase64 = prefs.getString(KEY_ECDH_PUBLIC, null) ?: return
            val publicKeyBytes = Base64.decode(publicKeyBase64, Base64.NO_WRAP)

            val keyFactory = KeyFactory.getInstance("EC")
            val privateKeySpec = PKCS8EncodedKeySpec(privateKeyBytes)
            val privateKey = keyFactory.generatePrivate(privateKeySpec)
            val publicKey = decodeX963PublicKey(publicKeyBytes)

            ecdhKeyPair = KeyPair(publicKey, privateKey)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading E2EE v2 keys", e)
        }
    }

    private fun generateEcdhKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        return generator.generateKeyPair()
    }

    private fun encodeX963PublicKey(publicKey: ECPublicKey): ByteArray {
        val affineX = publicKey.w.affineX.toByteArray().stripLeadingZeros(32)
        val affineY = publicKey.w.affineY.toByteArray().stripLeadingZeros(32)
        return byteArrayOf(0x04) + affineX + affineY
    }

    private fun decodeX963PublicKey(bytes: ByteArray): ECPublicKey {
        if (bytes.size != 65 || bytes[0] != 0x04.toByte()) {
            throw IllegalArgumentException("Invalid x963 public key")
        }
        val x = java.math.BigInteger(1, bytes.copyOfRange(1, 33))
        val y = java.math.BigInteger(1, bytes.copyOfRange(33, 65))
        val params = ecParameterSpec()
        val point = ECPoint(x, y)
        val spec = ECPublicKeySpec(point, params)
        return KeyFactory.getInstance("EC").generatePublic(spec) as ECPublicKey
    }

    private fun ecParameterSpec(): ECParameterSpec {
        val params = AlgorithmParameters.getInstance("EC")
        params.init(ECGenParameterSpec("secp256r1"))
        return params.getParameterSpec(ECParameterSpec::class.java)
    }

    private fun ByteArray.stripLeadingZeros(targetSize: Int): ByteArray {
        var offset = 0
        while (offset < size - 1 && this[offset] == 0.toByte()) {
            offset++
        }
        val trimmed = copyOfRange(offset, size)
        if (trimmed.size >= targetSize) {
            return trimmed.copyOfRange(trimmed.size - targetSize, trimmed.size)
        }
        val padded = ByteArray(targetSize)
        System.arraycopy(trimmed, 0, padded, targetSize - trimmed.size, trimmed.size)
        return padded
    }

    /**
     * Migrate existing unprotected keys to Keystore-protected storage
     */
    private fun migrateToKeystoreProtection(plaintextKeyset: String) {
        try {
            val (encryptedPrivateKey, iv) = encryptWithKeystore(plaintextKeyset)

            prefs.edit()
                .putString(KEY_PRIVATE_KEYSET, encryptedPrivateKey)
                .putString(KEY_PRIVATE_KEYSET_IV, iv)
                .putBoolean(KEY_USES_KEYSTORE, true)
                .apply()

            Log.d(TAG, "Migrated E2EE keys to Keystore protection")
        } catch (e: Exception) {
            Log.e(TAG, "Error migrating to Keystore protection", e)
        }
    }

    fun isInitialized(): Boolean {
        return prefs.getBoolean(KEY_INITIALIZED, false)
    }

    @SuppressLint("MissingPermission")
    private fun registerUser() {
        val uid = vpsClient.userId ?: return
        try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val phoneNumber = telephonyManager.line1Number
            if (!phoneNumber.isNullOrEmpty()) {
                runBlocking {
                    try {
                        vpsClient.registerPhoneNumber(phoneNumber)
                        Log.d(TAG, "Phone number registered via VPS")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error registering phone number via VPS", e)
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not get phone number - permission not granted")
        }
    }

    private fun publishPublicKeyToVps(publicKeysetJson: String) {
        val uid = vpsClient.userId ?: return

        val keyData = mapOf(
            "publicKey" to publicKeysetJson,
            "algorithm" to "ECIES_P256_HKDF_HMAC_SHA256_AES128_GCM",
            "version" to 1,
            "timestamp" to System.currentTimeMillis()
        )

        runBlocking {
            try {
                vpsClient.publishE2eePublicKey(keyData)
                Log.d(TAG, "Public key published to VPS")
            } catch (e: Exception) {
                Log.e(TAG, "Error publishing public key to VPS", e)
            }
        }
    }

    private fun publishDevicePublicKey() {
        val uid = vpsClient.userId ?: return
        val deviceId = getDeviceId() ?: return
        val publicKeyX963 = prefs.getString(KEY_ECDH_PUBLIC, null) ?: return

        val keyData = mapOf(
            "publicKeyX963" to publicKeyX963,
            "format" to "x963",
            "keyVersion" to 2,
            "platform" to "android",
            "timestamp" to System.currentTimeMillis()
        )

        runBlocking {
            try {
                vpsClient.publishDeviceE2eeKey(deviceId, keyData)
                Log.d(TAG, "Device public key published to VPS")
            } catch (e: Exception) {
                Log.e(TAG, "Error publishing device public key to VPS", e)
            }
        }
    }

    suspend fun getPublicKey(uid: String): String? {
        return try {
            val keyData = vpsClient.getE2eePublicKey(uid)
            keyData?.get("publicKey") as? String
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching public key for $uid", e)
            null
        }
    }

    suspend fun getDevicePublicKeys(uid: String): Map<String, String> {
        return try {
            val deviceKeys = vpsClient.getDeviceE2eeKeys(uid)
            val keys = mutableMapOf<String, String>()
            deviceKeys.forEach { (deviceId, keyData) ->
                val publicKey = keyData["publicKeyX963"] as? String
                if (!publicKey.isNullOrBlank()) {
                    keys[deviceId] = publicKey
                }
            }
            keys
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching device keys for $uid", e)
            emptyMap()
        }
    }

    /**
     * Get the sync group public key (Android device's ECDH public key in X963 format).
     * This is the shared public key that all devices in the sync group use for encryption.
     *
     * In the new shared keypair architecture, all devices use the same keypair
     * (generated by Android), eliminating the need for per-device keyMap and backfill.
     *
     * @return Public key in X963 format (Base64 encoded) or null if not initialized
     */
    fun getSyncGroupPublicKeyX963(): String? {
        return prefs.getString(KEY_ECDH_PUBLIC, null)
    }

    /**
     * Get the sync group private key (for key sync to other devices).
     * This exports the Android device's ECDH private key so it can be shared
     * with newly paired macOS/web devices.
     *
     * @return Private key in PKCS8 format (Base64 encoded) or null if not initialized
     */
    fun getSyncGroupPrivateKeyPKCS8(): String? {
        return try {
            val encryptedPrivate = prefs.getString(KEY_ECDH_PRIVATE, null) ?: return null
            val iv = prefs.getString(KEY_ECDH_PRIVATE_IV, null) ?: return null
            val privateKeyBase64 = decryptWithKeystore(encryptedPrivate, iv)
            privateKeyBase64
        } catch (e: Exception) {
            Log.e(TAG, "Error exporting sync group private key", e)
            null
        }
    }

    suspend fun encryptMessage(recipientUid: String, message: String): String? {
        return try {
            val publicKeyJson = getPublicKey(recipientUid)
                ?: throw Exception("Recipient public key not found")

            val publicKeysetHandle = TinkJsonProtoKeysetFormat.parseKeysetWithoutSecret(publicKeyJson)
            val encryptor = publicKeysetHandle.getPrimitive(HybridEncrypt::class.java)

            // Context info for authenticated encryption (prevents key confusion attacks)
            val contextInfo = "SyncFlow-E2EE-v1".toByteArray(StandardCharsets.UTF_8)

            val ciphertext = encryptor.encrypt(
                message.toByteArray(StandardCharsets.UTF_8),
                contextInfo
            )

            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Error encrypting message", e)
            null
        }
    }

    fun decryptMessage(encryptedMessage: String): String? {
        return try {
            val decryptor = encryptionPrimitive
                ?: throw Exception("Decryption primitive not initialized")

            val ciphertext = Base64.decode(encryptedMessage, Base64.NO_WRAP)
            val contextInfo = "SyncFlow-E2EE-v1".toByteArray(StandardCharsets.UTF_8)

            val plaintext = decryptor.decrypt(ciphertext, contextInfo)
            String(plaintext, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Error decrypting message", e)
            null
        }
    }

    fun encryptDataKeyForDevice(publicKeyX963Base64: String, dataKey: ByteArray): String? {
        return try {
            val recipientPublicKey = decodeX963PublicKey(Base64.decode(publicKeyX963Base64, Base64.NO_WRAP))
            val ephemeralKeyPair = generateEcdhKeyPair()
            val sharedSecret = deriveSharedSecret(ephemeralKeyPair.private as ECPrivateKey, recipientPublicKey)
            val aesKey = hkdfSha256(sharedSecret, E2EE_V2_CONTEXT.toByteArray(StandardCharsets.UTF_8), 32)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val nonce = ByteArray(12).apply { java.security.SecureRandom().nextBytes(this) }
            val keySpec = SecretKeySpec(aesKey, "AES")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_LENGTH, nonce))
            val ciphertext = cipher.doFinal(dataKey)

            val ephemeralPublic = encodeX963PublicKey(ephemeralKeyPair.public as ECPublicKey)
            val envelope = ByteArray(ephemeralPublic.size + nonce.size + ciphertext.size)
            System.arraycopy(ephemeralPublic, 0, envelope, 0, ephemeralPublic.size)
            System.arraycopy(nonce, 0, envelope, ephemeralPublic.size, nonce.size)
            System.arraycopy(ciphertext, 0, envelope, ephemeralPublic.size + nonce.size, ciphertext.size)

            "v2:" + Base64.encodeToString(envelope, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Error encrypting data key", e)
            null
        }
    }

    fun decryptDataKeyFromEnvelope(envelope: String): ByteArray? {
        return try {
            val payload = envelope.removePrefix("v2:")
            val bytes = Base64.decode(payload, Base64.NO_WRAP)
            if (bytes.size < 65 + 12 + 16) return null

            val ephemeralPublic = bytes.copyOfRange(0, 65)
            val nonce = bytes.copyOfRange(65, 77)
            val ciphertext = bytes.copyOfRange(77, bytes.size)

            val keyPair = ecdhKeyPair ?: return null
            val ephemeralKey = decodeX963PublicKey(ephemeralPublic)
            val sharedSecret = deriveSharedSecret(keyPair.private as ECPrivateKey, ephemeralKey)
            val aesKey = hkdfSha256(sharedSecret, E2EE_V2_CONTEXT.toByteArray(StandardCharsets.UTF_8), 32)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(aesKey, "AES"),
                GCMParameterSpec(GCM_TAG_LENGTH, nonce)
            )
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            Log.e(TAG, "Error decrypting data key", e)
            null
        }
    }

    fun encryptMessageBody(dataKey: ByteArray, message: String): Pair<ByteArray, ByteArray>? {
        return try {
            val nonce = ByteArray(12).apply { java.security.SecureRandom().nextBytes(this) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(dataKey, "AES"),
                GCMParameterSpec(GCM_TAG_LENGTH, nonce)
            )
            val ciphertext = cipher.doFinal(message.toByteArray(StandardCharsets.UTF_8))
            Pair(ciphertext, nonce)
        } catch (e: Exception) {
            Log.e(TAG, "Error encrypting message body", e)
            null
        }
    }

    fun decryptMessageBody(dataKey: ByteArray, ciphertext: ByteArray, nonce: ByteArray): String? {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(dataKey, "AES"),
                GCMParameterSpec(GCM_TAG_LENGTH, nonce)
            )
            val plaintext = cipher.doFinal(ciphertext)
            String(plaintext, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Error decrypting message body", e)
            null
        }
    }

    private fun deriveSharedSecret(privateKey: ECPrivateKey, publicKey: ECPublicKey): ByteArray {
        val keyAgreement = javax.crypto.KeyAgreement.getInstance("ECDH")
        keyAgreement.init(privateKey)
        keyAgreement.doPhase(publicKey, true)
        return keyAgreement.generateSecret()
    }

    private fun hkdfSha256(ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val salt = ByteArray(32) { 0 }
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)

        var t = ByteArray(0)
        val okm = ByteArray(length)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()

            val remaining = length - offset
            val toCopy = minOf(remaining, t.size)
            System.arraycopy(t, 0, okm, offset, toCopy)
            offset += toCopy
            counter++
        }
        return okm
    }

    fun getDeviceId(): String? {
        return try {
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Encrypt binary data (for MMS attachments)
     * Returns encrypted bytes or null if encryption fails
     */
    suspend fun encryptBytes(recipientUid: String, data: ByteArray): ByteArray? {
        return try {
            val publicKeyJson = getPublicKey(recipientUid)
                ?: throw Exception("Recipient public key not found")

            val publicKeysetHandle = TinkJsonProtoKeysetFormat.parseKeysetWithoutSecret(publicKeyJson)
            val encryptor = publicKeysetHandle.getPrimitive(HybridEncrypt::class.java)

            // Context info for authenticated encryption
            val contextInfo = "SyncFlow-E2EE-v1".toByteArray(StandardCharsets.UTF_8)

            encryptor.encrypt(data, contextInfo)
        } catch (e: Exception) {
            Log.e(TAG, "Error encrypting bytes", e)
            null
        }
    }

    /**
     * Decrypt binary data (for MMS attachments)
     * Returns decrypted bytes or null if decryption fails
     */
    fun decryptBytes(encryptedData: ByteArray): ByteArray? {
        return try {
            val decryptor = encryptionPrimitive
                ?: throw Exception("Decryption primitive not initialized")

            val contextInfo = "SyncFlow-E2EE-v1".toByteArray(StandardCharsets.UTF_8)

            decryptor.decrypt(encryptedData, contextInfo)
        } catch (e: Exception) {
            Log.e(TAG, "Error decrypting bytes", e)
            null
        }
    }

    /**
     * Get my public key for sharing with others
     */
    fun getMyPublicKey(): String? {
        return prefs.getString(KEY_PUBLIC_KEYSET, null)
    }

    /**
     * Clear all E2EE keys (for logout/reset)
     */
    fun clearKeys() {
        // Clear from SharedPreferences
        prefs.edit().clear().apply()
        privateKeysetHandle = null
        encryptionPrimitive = null

        // Clear from Android Keystore
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
                keyStore.deleteEntry(KEYSTORE_ALIAS)
                Log.d(TAG, "Deleted master key from Android Keystore")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing Keystore key", e)
        }

        // Clear from VPS
        if (vpsClient.userId != null) {
            runBlocking {
                try {
                    vpsClient.clearE2eeKeys()
                    Log.d(TAG, "E2EE keys cleared from VPS")
                } catch (e: Exception) {
                    Log.e(TAG, "Error clearing E2EE keys from VPS", e)
                }
            }
        }

        Log.d(TAG, "All E2EE keys cleared")
    }
}

/**
 * Helper object to access secret keys.
 * Note: The Tink private key is still serialized via this accessor,
 * but it's then encrypted using Android Keystore before storage.
 */
object InsecureSecretKeyAccess {
    fun get(): com.google.crypto.tink.SecretKeyAccess {
        return com.google.crypto.tink.InsecureSecretKeyAccess.get()
    }
}
