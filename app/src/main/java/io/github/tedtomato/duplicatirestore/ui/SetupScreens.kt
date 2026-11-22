package io.github.tedtomato.duplicatirestore.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.tedtomato.duplicatirestore.data.Account
import io.github.tedtomato.duplicatirestore.data.Secrets
import io.github.tedtomato.duplicatirestore.data.Session

@Composable
private fun SecretField(label: String, value: String, onChange: (String) -> Unit, digits: Boolean = false) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = if (digits) KeyboardType.NumberPassword else KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { show = !show }) {
                Icon(if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = if (show) "Hide" else "Show")
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(vm: MainViewModel) {
    val existing = vm.account
    var url by rememberSaveable { mutableStateOf(existing?.url ?: "https://") }
    var user by rememberSaveable { mutableStateOf(existing?.username ?: "") }
    var prefix by rememberSaveable { mutableStateOf(existing?.prefix ?: "duplicati") }
    var password by remember { mutableStateOf(Session.secrets?.password ?: "") }
    var passphrase by remember { mutableStateOf(Session.secrets?.passphrase ?: "") }
    var pin by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }

    val acc = Account(url.trim(), user.trim(), prefix.trim().ifEmpty { "duplicati" })
    val sec = Secrets(password, passphrase)
    val filled = url.startsWith("https://") && url.length > 8 && user.isNotBlank() && password.isNotEmpty() && passphrase.isNotEmpty()
    val pinOk = MainViewModel.validPin(pin) && pin == pin2

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "Set up backup" else "Backup account") },
                navigationIcon = {
                    if (vm.stack.size > 1) IconButton(onClick = { vm.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
        bottomBar = { vm.busy?.let { BusyBar(it) } },
    ) { pad ->
        ScrollColumn(Modifier.padding(pad).imePadding()) {
            Text("WebDAV (Hetzner Storage Box)", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(url, { url = it }, label = { Text("WebDAV folder URL") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("e.g. https://uXXXXXX.your-storagebox.de/Backups/MyPC/") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            SecretField("WebDAV password", password, { password = it })
            SecretField("Backup passphrase", passphrase, { passphrase = it })
            OutlinedTextField(prefix, { prefix = it }, label = { Text("File prefix") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            OutlinedButton(onClick = { vm.testConnection(acc, sec) }, enabled = filled && vm.busy == null) {
                Text("Test connection")
            }
            vm.testResult?.let { r ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Connection OK", style = MaterialTheme.typography.titleSmall)
                        Text("${r.versions} versions, ${r.dblocks} data volumes, ${r.dindexes} index volumes")
                        r.newest?.let { Text("Newest file list decrypted (AES Crypt v${r.aesVersion}, block size ${r.blocksize?.let { b -> formatBytes(b.toLong()) }})") }
                    }
                }
            }

            Text("Unlock PIN", style = MaterialTheme.typography.titleMedium)
            Text(
                "The password and passphrase are stored encrypted on this device and unlocked with a 4-digit PIN. " +
                    "After 5 wrong PINs they are erased and must be entered again.",
                style = MaterialTheme.typography.bodySmall,
            )
            SecretField("4-digit PIN", pin, { if (it.length <= 4 && it.all(Char::isDigit)) pin = it }, digits = true)
            SecretField("Repeat PIN", pin2, { if (it.length <= 4 && it.all(Char::isDigit)) pin2 = it }, digits = true)

            Button(onClick = { vm.saveAccount(acc, sec, pin) }, enabled = filled && pinOk && vm.busy == null, modifier = Modifier.fillMaxWidth()) {
                Text("Save")
            }

            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Good to know", style = MaterialTheme.typography.titleSmall)
                    Text("• Use a read-only Storage Box sub-account limited to the backup folder. Its host is uXXXXXX-subN.your-storagebox.de and its root is the shared folder, so the URL path is shorter.", style = MaterialTheme.typography.bodySmall)
                    Text("• This app only reads the backup. It never writes, renames or deletes anything on the storage.", style = MaterialTheme.typography.bodySmall)
                    Text("• Restored files are saved unencrypted on this device.", style = MaterialTheme.typography.bodySmall)
                    Text("• Supported: Duplicati backups with zip compression and AES encryption, over WebDAV.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun UnlockScreen(vm: MainViewModel) {
    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmForget by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Scaffold(bottomBar = { vm.busy?.let { BusyBar(it) } }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().imePadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.primary)
            Text("Enter your PIN", style = MaterialTheme.typography.headlineSmall)
            vm.account?.let { Text(it.url, style = MaterialTheme.typography.bodySmall) }
            OutlinedTextField(
                value = pin,
                onValueChange = {
                    if (it.length <= 4 && it.all(Char::isDigit)) {
                        pin = it
                        if (it.length == 4) {
                            vm.unlock(it) { msg -> message = msg; pin = "" }
                        }
                    }
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.widthIn(max = 200.dp).focusRequester(focus),
                enabled = vm.busy == null,
            )
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row {
                TextButton(onClick = { confirmForget = true }) { Text("Forgot PIN") }
            }
        }
    }
    if (confirmForget) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget saved secrets?") },
            text = { Text("The saved password and passphrase are erased. You then enter them again and choose a new PIN. The index and settings are kept.") },
            confirmButton = { TextButton(onClick = { confirmForget = false; vm.forgetSecrets() }) { Text("Erase") } },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
        )
    }
}
