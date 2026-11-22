package io.github.tedtomato.duplicatirestore.ui

import android.os.Build
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.crypto.Cipher
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AuthCancelledException(message: String) : Exception(message)

/** Asks the user for their fingerprint, face or screen lock with Android's BiometricPrompt. */
class DeviceAuth(private val activity: FragmentActivity) {

    /**
     * Gets a cipher from [makeCipher], has the user authorize it, then runs [use] with it.
     * Android 11+ ties the check to this one cipher; older versions unlock the key for a few
     * seconds, so the cipher is made after the check.
     */
    suspend fun <T> withCipher(title: String, subtitle: String, makeCipher: () -> Cipher, use: (Cipher) -> T): T {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val c = makeCipher()
            use(prompt(title, subtitle, c) ?: c)
        } else {
            prompt(title, subtitle, null)
            use(makeCipher())
        }
    }

    private suspend fun prompt(title: String, subtitle: String, cipher: Cipher?): Cipher? =
        suspendCancellableCoroutine { cont ->
            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (cont.isActive) cont.resume(result.cryptoObject?.cipher)
                    }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (cont.isActive) cont.resumeWithException(AuthCancelledException(errString.toString()))
                    }
                    // onAuthenticationFailed: a finger not recognised; the prompt stays open.
                })
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) BIOMETRIC_STRONG or DEVICE_CREDENTIAL
                    else BIOMETRIC_WEAK or DEVICE_CREDENTIAL
                )
                .build()
            if (cipher != null) prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher)) else prompt.authenticate(info)
            cont.invokeOnCancellation { runCatching { prompt.cancelAuthentication() } }
        }
}

val LocalDeviceAuth = staticCompositionLocalOf<DeviceAuth> { error("No DeviceAuth provided") }
