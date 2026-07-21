package app.glauncher.helper

import android.util.Base64
import app.glauncher.data.Prefs
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class PinManager(private val prefs: Prefs) {

    fun isPinConfigured(): Boolean = prefs.appLockPinHash.isNotEmpty()

    fun setPin(pin: String) {
        val salt = ByteArray(SALT_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = deriveHash(pin, salt, ITERATIONS)
        prefs.appLockPinSalt = Base64.encodeToString(salt, Base64.NO_WRAP)
        prefs.appLockPinHash = Base64.encodeToString(hash, Base64.NO_WRAP)
        prefs.appLockPinIterations = ITERATIONS
        prefs.appLockFailedAttempts = 0
        prefs.appLockLockedUntil = 0L
    }

    fun verifyPin(pin: String): Boolean {
        if (!isPinConfigured()) return false
        val salt = Base64.decode(prefs.appLockPinSalt, Base64.NO_WRAP)
        val expected = Base64.decode(prefs.appLockPinHash, Base64.NO_WRAP)
        val actual = deriveHash(pin, salt, prefs.appLockPinIterations)
        return MessageDigest.isEqual(actual, expected)
    }

    fun clearPin() {
        prefs.appLockPinHash = ""
        prefs.appLockPinSalt = ""
        prefs.appLockPinIterations = ITERATIONS
        prefs.appLockFailedAttempts = 0
        prefs.appLockLockedUntil = 0L
    }

    private fun deriveHash(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    companion object {
        const val ITERATIONS = 120_000
        private const val SALT_LENGTH_BYTES = 16
    }
}
