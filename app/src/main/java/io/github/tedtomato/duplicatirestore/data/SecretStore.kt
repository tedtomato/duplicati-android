package io.github.tedtomato.duplicatirestore.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import org.json.JSONObject
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class Secrets(val password: String, val passphrase: String)

class WrongPinException(val attemptsLeft: Int) : Exception(
    if (attemptsLeft > 0) "Wrong PIN. $attemptsLeft attempts left." else "Wrong PIN. The saved secrets were erased."
)

/**
 * Stores the WebDAV password and the backup passphrase on the device, encrypted, unlocked by a
 * 4-digit PIN.
 *
 * Two layers:
 *  1. Inner: AES-256-GCM with a key derived from the PIN (PBKDF2-HMAC-SHA256, random salt). A
 *     wrong PIN fails the GCM tag check.
 *  2. Outer: AES-256-GCM with a non-exportable Android Keystore key, so the stored blob is useless
 *     off this device and a PIN guess has to go through this app.
 *
 * A 4-digit PIN has only 10,000 values, so the app also erases the saved secrets after
 * [MAX_ATTEMPTS] wrong PINs in a row.
 */
class SecretStore(context: Context) {
    private val p = context.getSharedPreferences("vault", Context.MODE_PRIVATE)

    val hasSaved: Boolean get() = p.contains(BLOB)

    val attemptsLeft: Int get() = MAX_ATTEMPTS - p.getInt(FAILS, 0)

    fun save(secrets: Secrets, pin: String) {
        require(isValidPin(pin)) { "The PIN must be 4 digits" }
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val inner = Cipher.getInstance(GCM)
        inner.init(Cipher.ENCRYPT_MODE, pinKey(pin, salt))
        val json = JSONObject().put("password", secrets.password).put("passphrase", secrets.passphrase)
        val innerCt = inner.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val innerBlob = salt + inner.iv + innerCt // 16 + 12 + n

        val outer = Cipher.getInstance(GCM)
        outer.init(Cipher.ENCRYPT_MODE, keystoreKey(create = true))
        val outerBlob = outer.iv + outer.doFinal(innerBlob)
        p.edit {
            putString(BLOB, Base64.encodeToString(outerBlob, Base64.NO_WRAP))
            putInt(FAILS, 0)
        }
    }

    /** Throws [WrongPinException] on a wrong PIN; erases everything after too many. */
    fun unlock(pin: String): Secrets {
        val blob = Base64.decode(p.getString(BLOB, null) ?: throw IllegalStateException("No saved secrets"), Base64.NO_WRAP)
        val key = keystoreKey(create = false) ?: run {
            clear()
            throw IllegalStateException("The device key for the saved secrets is gone. Enter them again.")
        }
        val outer = Cipher.getInstance(GCM)
        outer.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, 12))
        val innerBlob = outer.doFinal(blob, 12, blob.size - 12)

        val salt = innerBlob.copyOfRange(0, 16)
        val inner = Cipher.getInstance(GCM)
        inner.init(Cipher.DECRYPT_MODE, pinKey(pin, salt), GCMParameterSpec(128, innerBlob, 16, 12))
        val plain = try {
            inner.doFinal(innerBlob, 28, innerBlob.size - 28)
        } catch (e: AEADBadTagException) {
            val fails = p.getInt(FAILS, 0) + 1
            if (fails >= MAX_ATTEMPTS) clear() else p.edit { putInt(FAILS, fails) }
            throw WrongPinException(MAX_ATTEMPTS - fails)
        }
        p.edit { putInt(FAILS, 0) }
        val o = JSONObject(String(plain, Charsets.UTF_8))
        return Secrets(o.getString("password"), o.getString("passphrase"))
    }

    fun clear() {
        p.edit { remove(BLOB); remove(FAILS) }
        runCatching { keyStore().deleteEntry(ALIAS) }
    }

    private fun pinKey(pin: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(raw, "AES")
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun keystoreKey(create: Boolean): SecretKey? {
        val ks = keyStore()
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    companion object {
        const val MAX_ATTEMPTS = 5
        private const val PBKDF2_ITERATIONS = 200_000
        private const val GCM = "AES/GCM/NoPadding"
        private const val ALIAS = "duplicati_restore_secrets"
        private const val BLOB = "blob"
        private const val FAILS = "fails"

        fun isValidPin(pin: String) = pin.length == 4 && pin.all { it in '0'..'9' }
    }
}

/** Secrets for this app session, held in memory only. */
object Session {
    @Volatile var secrets: Secrets? = null
}
