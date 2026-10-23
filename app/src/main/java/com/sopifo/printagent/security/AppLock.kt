package com.sopifo.printagent.security

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** PIN hashing: salted PBKDF2, so the PIN itself is never stored. Plain Kotlin for unit tests. */
object PinHasher {
    private const val ITERATIONS = 20_000
    private const val KEY_BITS = 256

    fun isValid(pin: String): Boolean = pin.length in 4..8 && pin.all { it.isDigit() }

    /** "salt:hash", both Base64. */
    fun hash(pin: String, salt: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }): String {
        val key = derive(pin, salt)
        val b64 = Base64.getEncoder()
        return "${b64.encodeToString(salt)}:${b64.encodeToString(key)}"
    }

    fun matches(pin: String, stored: String): Boolean {
        val parts = stored.split(":")
        if (parts.size != 2) return false
        val b64 = Base64.getDecoder()
        val salt = runCatching { b64.decode(parts[0]) }.getOrNull() ?: return false
        val expected = runCatching { b64.decode(parts[1]) }.getOrNull() ?: return false
        return MessageDigest.isEqual(derive(pin, salt), expected)
    }

    private fun derive(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_BITS))
            .encoded
}

/**
 * Optional app lock for the pages with customer names (Orders, Shipping, print history): a PIN,
 * plus fingerprint / face unlock if the seller wants. Stored on this phone only; forgetting it
 * means re-pairing the phone. Printing sent from the dashboard never waits for it.
 */
class AppLock(context: Context, private val clock: () -> Long = System::currentTimeMillis) {
    private val prefs = context.getSharedPreferences("app_lock", Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getString(KEY_PIN, null) != null)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _biometric = MutableStateFlow(prefs.getBoolean(KEY_BIOMETRIC, false))
    /** Fingerprint / face unlock allowed as well as the PIN. */
    val biometric: StateFlow<Boolean> = _biometric.asStateFlow()

    /** Locked until the PIN or fingerprint is given; locks again when the app goes to the background. */
    private val _locked = MutableStateFlow(_enabled.value)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    /** Wrong PINs in a row; after [MAX_TRIES] the next try waits. */
    private var wrongTries = prefs.getInt(KEY_TRIES, 0)

    fun setPin(pin: String) {
        require(PinHasher.isValid(pin)) { "PIN must be 4 to 8 digits" }
        prefs.edit(commit = true) { putString(KEY_PIN, PinHasher.hash(pin)); putInt(KEY_TRIES, 0) }
        wrongTries = 0
        _enabled.value = true
        _locked.value = false
    }

    fun setBiometric(on: Boolean) {
        prefs.edit { putBoolean(KEY_BIOMETRIC, on) }
        _biometric.value = on
    }

    /** Turns the lock off; needs the current PIN. */
    fun disable(pin: String): Boolean {
        if (!check(pin)) return false
        clear()
        return true
    }

    /** Re-pairing (forgot PIN) or unpairing clears the lock. */
    fun clear() {
        prefs.edit(commit = true) { clear() }
        wrongTries = 0
        _enabled.value = false
        _biometric.value = false
        _locked.value = false
    }

    /** Seconds to wait before the next PIN try (0 = may try now). */
    fun waitSeconds(): Long {
        if (wrongTries < MAX_TRIES) return 0
        val until = prefs.getLong(KEY_WAIT_UNTIL, 0)
        return ((until - clock()) / 1000).coerceAtLeast(0)
    }

    /** Unlocks with the PIN; false if wrong or still waiting after too many wrong tries. */
    fun unlock(pin: String): Boolean {
        if (waitSeconds() > 0) return false
        if (!check(pin)) {
            wrongTries++
            prefs.edit {
                putInt(KEY_TRIES, wrongTries)
                if (wrongTries >= MAX_TRIES) putLong(KEY_WAIT_UNTIL, clock() + WAIT_MS)
            }
            return false
        }
        wrongTries = 0
        prefs.edit { putInt(KEY_TRIES, 0) }
        _locked.value = false
        return true
    }

    /** After fingerprint / face unlock succeeded. */
    fun unlockedByBiometric() {
        if (_biometric.value) _locked.value = false
    }

    /** The app went to the background. */
    fun lockNow() {
        if (_enabled.value) _locked.value = true
    }

    private fun check(pin: String): Boolean = prefs.getString(KEY_PIN, null)?.let { PinHasher.matches(pin, it) } ?: false

    companion object {
        const val MAX_TRIES = 5
        private const val WAIT_MS = 30_000L
        private const val KEY_PIN = "pin"
        private const val KEY_BIOMETRIC = "biometric"
        private const val KEY_TRIES = "wrong_tries"
        private const val KEY_WAIT_UNTIL = "wait_until"
    }
}
