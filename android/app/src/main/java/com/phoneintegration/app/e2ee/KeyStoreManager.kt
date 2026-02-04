package com.phoneintegration.app.e2ee

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeyStoreManager(private val context: Context) {

    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply {
        load(null)
    }

    private val alias = "e2ee_db_key"
    private val sharedPrefs = context.getSharedPreferences("e2ee_prefs", Context.MODE_PRIVATE)

    private fun getSecretKey(): SecretKey {
        val existingKey = keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry
        return existingKey?.secretKey ?: generateSecretKey()
    }

    private fun generateSecretKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val parameterSpec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        keyGenerator.init(parameterSpec)
        return keyGenerator.generateKey()
    }

    fun getPassphrase(): CharArray {
        val encryptedPassphrase = sharedPrefs.getString("db_passphrase", null)
        return if (encryptedPassphrase != null) {
            val encryptedData = Base64.decode(encryptedPassphrase, Base64.DEFAULT)
            val iv = encryptedData.copyOfRange(0, 12)
            val ciphertext = encryptedData.copyOfRange(12, encryptedData.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), GCMParameterSpec(128, iv))
            cipher.doFinal(ciphertext).toString(Charsets.UTF_8).toCharArray()
        } else {
            val newPassphrase = generateRandomPassphrase()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
            val ciphertext = cipher.doFinal(newPassphrase.joinToString("").toByteArray(Charsets.UTF_8))
            val iv = cipher.iv
            val encryptedData = iv + ciphertext
            sharedPrefs.edit().putString("db_passphrase", Base64.encodeToString(encryptedData, Base64.DEFAULT)).apply()
            newPassphrase
        }
    }

    private fun generateRandomPassphrase(): CharArray {
        val random = SecureRandom()
        val passphrase = CharArray(32)
        for (i in passphrase.indices) {
            passphrase[i] = (random.nextInt(94) + 33).toChar()
        }
        return passphrase
    }
}

