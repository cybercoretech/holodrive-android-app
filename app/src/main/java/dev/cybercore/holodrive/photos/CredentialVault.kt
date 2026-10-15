package dev.cybercore.holodrive.photos

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores an opt-in password encrypted with a device-bound key requiring biometric auth. */
class CredentialVault(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("secure_credentials", Context.MODE_PRIVATE)

    fun hasCredential(): Boolean = prefs.contains(PASSWORD)

    fun encryptionCipher(): Cipher = cipher(Cipher.ENCRYPT_MODE)

    fun decryptionCipher(): Cipher? {
        val encoded = prefs.getString(PASSWORD, null) ?: return null
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        if (bytes.size < 13) return null
        val size = bytes[0].toInt() and 0xff
        if (size != 12 || bytes.size <= size + 1) return null
        val iv = bytes.copyOfRange(1, 1 + size)
        return cipher(Cipher.DECRYPT_MODE, iv)
    }

    fun store(cipher: Cipher, password: String) {
        val clear = password.toByteArray(StandardCharsets.UTF_8)
        try {
            val encrypted = cipher.doFinal(clear)
            val iv = cipher.iv
            val payload = ByteBuffer.allocate(1 + iv.size + encrypted.size)
                .put(iv.size.toByte()).put(iv).put(encrypted).array()
            prefs.edit { putString(PASSWORD, Base64.encodeToString(payload, Base64.NO_WRAP)) }
        } finally {
            clear.fill(0)
        }
    }

    fun read(cipher: Cipher): String {
        val encoded = prefs.getString(PASSWORD, null) ?: error("No saved password")
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val size = bytes[0].toInt() and 0xff
        val clear = cipher.doFinal(bytes.copyOfRange(size + 1, bytes.size))
        return try { String(clear, StandardCharsets.UTF_8) } finally { clear.fill(0) }
    }

    fun clear() {
        prefs.edit { remove(PASSWORD) }
        try {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            store.deleteEntry(ALIAS)
        } catch (_: Exception) { /* The ciphertext is already gone from app storage. */ }
    }

    private fun cipher(mode: Int, iv: ByteArray? = null): Cipher {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = if (keyStore.containsAlias(ALIAS)) keyStore.getKey(ALIAS, null) as SecretKey else {
            check(mode == Cipher.ENCRYPT_MODE) { "Saved password key is missing" }
            createKey()
        }
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            if (mode == Cipher.ENCRYPT_MODE) init(mode, key) else init(mode, key, GCMParameterSpec(128, requireNotNull(iv)))
        }
    }

    private fun createKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private companion object {
        const val PASSWORD = "encrypted_password"
        const val ALIAS = "holodrive.photos.password"
    }
}
