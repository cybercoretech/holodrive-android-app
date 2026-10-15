package dev.cybercore.holodrive.photos

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-bound encryption for the revocable credential used by background work. */
class DeviceTokenVault(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("secure_credentials", Context.MODE_PRIVATE)

    fun hasToken(): Boolean = prefs.contains(TOKEN)

    fun store(token: String) {
        val clear = token.toByteArray(StandardCharsets.UTF_8)
        try {
            val cipher = cipher(Cipher.ENCRYPT_MODE)
            val encrypted = cipher.doFinal(clear)
            val payload = byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + encrypted
            prefs.edit { putString(TOKEN, Base64.encodeToString(payload, Base64.NO_WRAP)) }
        } finally { clear.fill(0) }
    }

    fun read(): String? {
        val encoded = prefs.getString(TOKEN, null) ?: return null
        return try {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            val ivSize = payload.firstOrNull()?.toInt()?.and(0xff) ?: return null
            if (ivSize != 12 || payload.size <= ivSize + 1) return null
            val clear = cipher(Cipher.DECRYPT_MODE, payload.copyOfRange(1, 1 + ivSize))
                .doFinal(payload.copyOfRange(1 + ivSize, payload.size))
            try { String(clear, StandardCharsets.UTF_8) } finally { clear.fill(0) }
        } catch (_: Exception) {
            clear()
            null
        }
    }

    fun clear() {
        prefs.edit { remove(TOKEN) }
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
    }

    private fun cipher(mode: Int, iv: ByteArray? = null): Cipher {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = if (store.containsAlias(ALIAS)) store.getKey(ALIAS, null) as SecretKey else {
            check(mode == Cipher.ENCRYPT_MODE) { "Saved device token key is missing" }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build())
            generator.generateKey()
        }
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            if (mode == Cipher.ENCRYPT_MODE) init(mode, key) else init(mode, key, GCMParameterSpec(128, requireNotNull(iv)))
        }
    }

    private companion object {
        const val TOKEN = "encrypted_device_token"
        const val ALIAS = "holodrive.photos.device-token"
    }
}
