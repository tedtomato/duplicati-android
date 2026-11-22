package io.github.tedtomato.duplicatirestore.data

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Secrets(val password: String, val passphrase: String)

/** The saved secrets can no longer be decrypted (e.g. the screen lock was removed). */
class SecretsLostException : Exception(
    "The saved password and passphrase can no longer be unlocked (was the screen lock changed or removed?). Enter them again."
)

/**
 * Stores the WebDAV password and the backup passphrase on the device, encrypted with an Android
 * Keystore key that only works after the user unlocks with their fingerprint, face or screen lock
 * (pattern, PIN or password). The key lives in the device's secure hardware where available and
 * cannot be exported; Android itself limits wrong attempts.
 *
 * Usage: get a cipher from [encryptCipher] / [decryptCipher], have the user authorize it with
 * BiometricPrompt, then call [save] / [load].
 */
class SecretStore(private val context: Context) {
    private val p = context.getSharedPreferences("vault2", Context.MODE_PRIVATE)

    init {
        // Versions before 0.3 used a 4-digit app PIN. Those secrets can't be moved without the PIN,
        // so they are erased and the user enters them once more.
        val legacy = context.getSharedPreferences("vault", Context.MODE_PRIVATE)
        if (legacy.contains("blob")) {
            legacy.edit { clear() }
            runCatching { keyStore().deleteEntry("duplicati_restore_secrets") }
            p.edit { putBoolean(LEGACY_NOTICE, true) }
        }
    }

    val hasSaved: Boolean get() = p.contains(BLOB)

    /** True once after an upgrade from the PIN version; reading it clears it. */
    fun takeLegacyNotice(): Boolean = p.getBoolean(LEGACY_NOTICE, false).also { if (it) p.edit { remove(LEGACY_NOTICE) } }

    /** Saving needs a secure lock screen (pattern, PIN or password). */
    val deviceIsSecure: Boolean
        get() = context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    fun encryptCipher(): Cipher = Cipher.getInstance(GCM).apply { init(Cipher.ENCRYPT_MODE, key(create = true)) }

    fun decryptCipher(): Cipher {
        val iv = Base64.decode(p.getString(IV, null) ?: throw IllegalStateException("No saved secrets"), Base64.NO_WRAP)
        val key = key(create = false) ?: run { clear(); throw SecretsLostException() }
        return try {
            Cipher.getInstance(GCM).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            clear(); throw SecretsLostException()
        }
    }

    /** [cipher] must come from [encryptCipher] and be authorized by the user. */
    fun save(cipher: Cipher, secrets: Secrets) {
        val json = JSONObject().put("password", secrets.password).put("passphrase", secrets.passphrase)
        val ct = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        p.edit {
            putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            putString(BLOB, Base64.encodeToString(ct, Base64.NO_WRAP))
        }
    }

    /** [cipher] must come from [decryptCipher] and be authorized by the user. */
    fun load(cipher: Cipher): Secrets {
        val ct = Base64.decode(p.getString(BLOB, null) ?: throw IllegalStateException("No saved secrets"), Base64.NO_WRAP)
        val o = JSONObject(String(cipher.doFinal(ct), Charsets.UTF_8))
        return Secrets(o.getString("password"), o.getString("passphrase"))
    }

    fun clear() {
        p.edit { remove(BLOB); remove(IV) }
        runCatching { keyStore().deleteEntry(ALIAS) }
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(create: Boolean): SecretKey? {
        (keyStore().getKey(ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            // Enrolling a new fingerprint needs the screen lock anyway, so it doesn't void the key.
            .setInvalidatedByBiometricEnrollment(false)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Every use needs a fresh fingerprint, face or screen-lock check.
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(AUTH_WINDOW_SECONDS)
                }
            }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(spec); generateKey()
        }
    }

    companion object {
        /** Android 8-10: how long the key stays usable after unlocking. */
        const val AUTH_WINDOW_SECONDS = 30
        private const val GCM = "AES/GCM/NoPadding"
        private const val ALIAS = "duplicati_restore_device_key"
        private const val BLOB = "blob"
        private const val IV = "iv"
        private const val LEGACY_NOTICE = "legacyNotice"
    }
}

/** Secrets for this app session, held in memory only. */
object Session {
    @Volatile var secrets: Secrets? = null
}
