package io.github.tedtomato.duplicatirestore

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.tedtomato.duplicatirestore.data.AppLog
import io.github.tedtomato.duplicatirestore.ui.AppRoot
import io.github.tedtomato.duplicatirestore.ui.AppTheme
import io.github.tedtomato.duplicatirestore.ui.DeviceAuth
import io.github.tedtomato.duplicatirestore.ui.LocalDeviceAuth
import io.github.tedtomato.duplicatirestore.ui.MainViewModel
import io.github.tedtomato.duplicatirestore.update.Updater
import kotlinx.coroutines.launch

// FragmentActivity, because BiometricPrompt needs one.
class MainActivity : FragmentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val auth = DeviceAuth(this)
        setContent { CompositionLocalProvider(LocalDeviceAuth provides auth) { AppTheme { AppRoot(vm) } } }

        // Android asks the user to confirm an update install; show that screen while we're visible.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Updater.pendingConfirmation.collect { intent ->
                    if (intent != null) {
                        Updater.pendingConfirmation.value = null
                        runCatching { startActivity(intent) }.onFailure { AppLog.e("Could not show the install prompt", it) }
                    }
                }
            }
        }
    }
}
