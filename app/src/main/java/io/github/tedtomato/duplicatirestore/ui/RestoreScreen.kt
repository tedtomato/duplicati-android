package io.github.tedtomato.duplicatirestore.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import io.github.tedtomato.duplicatirestore.data.DestinationMode
import io.github.tedtomato.duplicatirestore.index.RestoreItem
import io.github.tedtomato.duplicatirestore.restore.RestoreWorker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreScreen(vm: MainViewModel) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Restore") },
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        bottomBar = { vm.busy?.let { BusyBar(it) } },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            val id = vm.jobId
            if (id == null) PlanView(vm) else JobView(vm, id)
        }
    }
}

@Composable
private fun PlanView(vm: MainViewModel) {
    val ctx = LocalContext.current
    val settings = vm.app.settings
    var mode by remember { mutableStateOf(settings.destinationMode) }
    var tree by remember { mutableStateOf(settings.destinationTree) }
    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            settings.destinationTree = uri.toString(); tree = uri.toString()
            settings.destinationMode = DestinationMode.FOLDER; mode = DestinationMode.FOLDER
        }
    }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.startRestore() }

    val plan = vm.plan ?: return
    val ok = plan.files.size - plan.problems.size
    ScrollColumn {
        Card {
            Column(Modifier.padding(16.dp)) {
                Text("${plan.files.size} files, ${formatBytes(plan.totalBytes)}", style = MaterialTheme.typography.titleMedium)
                Text("Needs ${plan.volumes.size} volumes (${formatBytes(plan.downloadBytes)} to download)")
                if (plan.volumes.size > 0 && plan.downloadBytes < plan.volumes.values.sum()) {
                    Text("Some volumes are already in the cache.", style = MaterialTheme.typography.bodySmall)
                }
                if (plan.missingBlocklists > 0) {
                    Text("${plan.missingBlocklists} blocklists are read from data volumes first.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (vm.isOnMobileData()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text("You are on mobile data. This restore downloads ${formatBytes(plan.downloadBytes)}.", Modifier.padding(16.dp))
            }
        }
        if (plan.problems.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text("${plan.problems.size} files cannot be restored:")
                    plan.files.filter { it.id in plan.problems }.take(20).forEach {
                        Text("${it.name}: ${plan.problems[it.id]}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Text("Save to", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().selectable(mode == DestinationMode.FOLDER) { mode = DestinationMode.FOLDER; settings.destinationMode = mode },
            verticalAlignment = Alignment.CenterVertically) {
            RadioButton(mode == DestinationMode.FOLDER, null)
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text("A folder I choose")
                Text(tree?.let { Uri.decode(Uri.parse(it).lastPathSegment ?: it) } ?: "None chosen yet",
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = { pickTree.launch(null) }) { Text(if (tree == null) "Choose" else "Change") }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Row(Modifier.fillMaxWidth().selectable(mode == DestinationMode.DOWNLOADS) { mode = DestinationMode.DOWNLOADS; settings.destinationMode = mode },
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(mode == DestinationMode.DOWNLOADS, null)
                Text("Downloads/DuplicatiRestore", Modifier.padding(start = 8.dp))
            }
        }
        Text("Folders are kept as in the backup, starting at the deepest folder that holds everything selected. " +
            "Each file is rebuilt and checked against the backup's SHA-256 before it is saved. Restored files are not encrypted.",
            style = MaterialTheme.typography.bodySmall)

        val canStart = ok > 0 && vm.busy == null && (mode == DestinationMode.DOWNLOADS || tree != null)
        Button(
            onClick = {
                if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.startRestore()
            },
            enabled = canStart,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Start restore") }
    }
}

@Composable
private fun JobView(vm: MainViewModel, jobId: String) {
    val ctx = LocalContext.current
    val info by vm.workInfo.collectAsState(initial = null)
    val items by vm.restoreItems(jobId).collectAsState(initial = emptyList())
    val running = info?.state?.isFinished == false
    val okCount = items.count { it.status == "OK" }
    val failCount = items.count { it.status == "FAIL" }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp)) {
            if (running) {
                val p = info!!.progress
                val done = p.getLong(RestoreWorker.KEY_DONE, 0)
                val total = p.getLong(RestoreWorker.KEY_TOTAL, 0)
                Text(p.getString(RestoreWorker.KEY_TEXT) ?: "Waiting to start…")
                if (total > 0) LinearProgressIndicator(progress = { (done.toFloat() / total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().padding(top = 8.dp))
                else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedButton(onClick = { vm.cancelRestore() }, Modifier.padding(top = 8.dp)) { Text("Cancel") }
            } else {
                Text("$okCount OK · $failCount failed", style = MaterialTheme.typography.titleMedium)
                if (info?.state == WorkInfo.State.FAILED && failCount == 0) Text("The restore stopped. See Settings › Diagnostics.")
            }
        }
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxSize()) {
            items(items, key = { it.id }) { item -> ResultRow(item) { uri -> openFile(ctx, uri) } }
        }
    }
}

@Composable
private fun ResultRow(item: RestoreItem, onOpen: (Uri) -> Unit) {
    val (icon, tint) = when (item.status) {
        "OK" -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary
        "FAIL" -> Icons.Default.Error to MaterialTheme.colorScheme.error
        else -> Icons.Default.Schedule to MaterialTheme.colorScheme.onSurfaceVariant
    }
    ListItem(
        leadingContent = { Icon(icon, item.status, tint = tint) },
        headlineContent = { Text(item.displayPath.substringAfterLast('\\').substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(item.message ?: "${formatBytes(item.size)} · ${item.displayPath}", maxLines = 3, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            val uri = item.outputUri
            if (item.status == "OK" && uri != null) {
                Row {
                    TextButton(onClick = { onOpen(Uri.parse(uri)) }) { Text("Open") }
                }
            }
        },
    )
}

private fun openFile(ctx: android.content.Context, uri: Uri) {
    val type = ctx.contentResolver.getType(uri)?.takeIf { it != "application/octet-stream" }
        ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(
            (uri.lastPathSegment ?: "").substringAfterLast('.', "").lowercase()
        ) ?: "*/*"
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { ctx.startActivity(Intent.createChooser(view, "Open with")) }
}
