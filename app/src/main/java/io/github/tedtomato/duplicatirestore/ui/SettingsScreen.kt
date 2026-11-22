package io.github.tedtomato.duplicatirestore.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.tedtomato.duplicatirestore.BuildConfig
import io.github.tedtomato.duplicatirestore.data.AppLog
import io.github.tedtomato.duplicatirestore.update.UpdateState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val s = vm.app.settings
    var cacheText by remember { mutableStateOf(s.cacheLimitMb.toString()) }
    var autoUpdate by remember { mutableStateOf(s.autoUpdateCheck) }
    var partial by remember { mutableStateOf(s.partialDownloads) }
    var showLog by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    val update by vm.updateState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        bottomBar = { vm.busy?.let { BusyBar(it) } },
    ) { pad ->
        ScrollColumn(Modifier.padding(pad)) {
            Section("Account")
            vm.account?.let { Text("${it.username} @ ${it.url}", style = MaterialTheme.typography.bodySmall) }
            Row {
                OutlinedButton(onClick = { vm.push(Screen.Setup) }) { Text("Edit account") }
                TextButton(onClick = { confirmForget = true }) { Text("Forget saved secrets") }
                TextButton(onClick = { vm.lock() }) { Text("Lock") }
            }

            HorizontalDivider()
            Section("Cache")
            Text("Downloaded volumes are kept (still encrypted) so later restores can reuse them. In use: ${formatBytes(vm.cacheBytes)}",
                style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    cacheText, { cacheText = it.filter(Char::isDigit).take(6) },
                    label = { Text("Limit (MB)") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                TextButton(onClick = { cacheText.toIntOrNull()?.let { s.cacheLimitMb = it; vm.app.volumeCache.trim(); vm.refreshCacheSize() } }) { Text("Save") }
            }
            Row {
                OutlinedButton(onClick = { vm.clearCache() }) { Text("Clear cache") }
                TextButton(onClick = { vm.clearIndex() }) { Text("Rebuild index") }
            }

            HorizontalDivider()
            Section("Downloads")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Quick restore")
                    Text(
                        "Download only the needed parts of each volume, when the server allows it. Every block and " +
                            "file is still checked; the whole-volume checksum is skipped for volumes read this way.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(partial, { partial = it; s.partialDownloads = it })
            }

            HorizontalDivider()
            Section("Updates")
            Text("Installed: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Check for updates automatically", Modifier.weight(1f))
                Switch(autoUpdate, { autoUpdate = it; s.autoUpdateCheck = it })
            }
            Text(
                when (val u = update) {
                    UpdateState.Idle -> ""
                    UpdateState.Checking -> "Checking…"
                    UpdateState.UpToDate -> "Up to date"
                    is UpdateState.Available -> "Version ${u.info.versionName} is available"
                    is UpdateState.Downloading -> "Downloading ${u.percent}%"
                    UpdateState.Installing -> "Installing…"
                    is UpdateState.Failed -> u.message
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Row {
                OutlinedButton(onClick = { vm.checkUpdate() }) { Text("Check now") }
                if (update is UpdateState.Available) TextButton(onClick = {
                    vm.installUpdate { ctx.startActivity(vm.app.updater.installPermissionIntent()) }
                }) { Text("Install") }
            }

            HorizontalDivider()
            Section("Diagnostics")
            OutlinedButton(onClick = { showLog = true }) { Text("Show log") }
        }
    }

    if (showLog) {
        val clip = LocalClipboardManager.current
        val text = AppLog.text()
        AlertDialog(
            onDismissRequest = { showLog = false },
            confirmButton = { TextButton(onClick = { showLog = false }) { Text("Close") } },
            dismissButton = { TextButton(onClick = { clip.setText(AnnotatedString(text)) }) { Text("Copy") } },
            title = { Text("Log") },
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                    SelectionContainer { Text(text.ifEmpty { "(empty)" }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                }
            },
        )
    }
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget saved secrets?") },
            text = { Text("The saved password and passphrase are erased from this device. You enter them again next time.") },
            confirmButton = { TextButton(onClick = { confirmForget = false; vm.forgetSecrets() }) { Text("Erase") } },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}
