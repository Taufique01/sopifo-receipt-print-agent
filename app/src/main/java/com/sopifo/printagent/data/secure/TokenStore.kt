package com.sopifo.printagent.data.secure

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import com.sopifo.printagent.core.AppLog
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Holds the device JWT. */
interface SessionTokenStore {
    fun save(token: String): Boolean
    fun load(): String?
    fun clear()
}

/**
 * Stores the device JWT encrypted with an AES-256-GCM key that lives in the Android Keystore
 * and never leaves it. The ciphertext sits in private SharedPreferences, which are excluded
 * from backup, so a copied data directory is useless on another device.
 */
class KeystoreTokenStore(context: Context) : SessionTokenStore {
    private val prefs = context.getSharedPreferences("secure_session", Context.MODE_PRIVATE)
    @Volatile private var cached: String? = null

    @SuppressLint("ApplySharedPref")
    override fun save(token: String): Boolean = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val blob = cipher.iv + encrypted
        // Synchronous commit: registration must not report success before the session is on disk.
        val ok = prefs.edit().putString(KEY_JWT, Base64.encodeToString(blob, Base64.NO_WRAP)).commit()
        if (ok) cached = token
        ok
    } catch (t: Throwable) {
        AppLog.e(TAG, "Failed to store device token", t)
        false
    }

    override fun load(): String? {
        cached?.let { return it }
        val stored = prefs.getString(KEY_JWT, null) ?: return null
        return try {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            val iv = blob.copyOfRange(0, IV_LENGTH)
            val body = blob.copyOfRange(IV_LENGTH, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(body), Charsets.UTF_8).also { cached = it }
        } catch (t: Throwable) {
            // Key lost (e.g. keystore reset). The device must be registered again.
            AppLog.e(TAG, "Stored device token unreadable", t)
            null
        }
    }

    override fun clear() {
        cached = null
        prefs.edit(commit = true) { remove(KEY_JWT) }
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val TAG = "TokenStore"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "sopifo_device_jwt_key"
        const val KEY_JWT = "device_jwt"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
    }
}
