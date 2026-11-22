package io.github.tedtomato.duplicatirestore.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AppRoot(vm: MainViewModel) {
    BackHandler(enabled = vm.stack.size > 1 || (vm.screen is Screen.Browser && vm.folder.isNotEmpty())) {
        if (vm.screen is Screen.Browser && vm.query.isEmpty() && vm.folderUp()) return@BackHandler
        if (vm.screen is Screen.Browser && vm.query.isNotEmpty()) { vm.setSearch(""); return@BackHandler }
        vm.back()
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // On tablets (e.g. an 8.8" Legion Tab in landscape) keep content at a readable width.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.fillMaxSize().widthIn(max = 840.dp)) {
                when (val s = vm.screen) {
                    Screen.Setup -> SetupScreen(vm)
                    Screen.Unlock -> UnlockScreen(vm)
                    Screen.Versions -> VersionsScreen(vm)
                    is Screen.Browser -> BrowserScreen(vm)
                    Screen.Restore -> RestoreScreen(vm)
                    Screen.Settings -> SettingsScreen(vm)
                }
            }
        }
    }
    vm.error?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.error = null },
            confirmButton = { TextButton(onClick = { vm.error = null }) { Text("OK") } },
            title = { Text("Problem") },
            text = { SelectionContainer { Text(msg) } },
        )
    }
    vm.info?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.info = null },
            confirmButton = { TextButton(onClick = { vm.info = null }) { Text("OK") } },
            text = { Text(msg) },
        )
    }
}

/** Full-width busy row with an optional cancel button. */
@Composable
fun BusyBar(text: String, onCancel: (() -> Unit)? = null) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (onCancel != null) TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
fun ScrollColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

fun formatBytes(n: Long): String = when {
    n < 0 -> "?"
    n < 1000 -> "$n B"
    n < 1_000_000 -> String.format(Locale.getDefault(), "%.1f KB", n / 1e3)
    n < 1_000_000_000 -> String.format(Locale.getDefault(), "%.1f MB", n / 1e6)
    else -> String.format(Locale.getDefault(), "%.2f GB", n / 1e9)
}

fun formatTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
