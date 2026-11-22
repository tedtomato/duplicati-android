package io.github.tedtomato.duplicatirestore.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.tedtomato.duplicatirestore.format.Paths
import io.github.tedtomato.duplicatirestore.update.UpdateState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VersionsScreen(vm: MainViewModel) {
    val versions by vm.versions.collectAsState()
    val update by vm.updateState.collectAsState()
    LaunchedEffect(Unit) { vm.syncIfNeeded() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup versions") },
                actions = {
                    IconButton(onClick = { vm.sync() }, enabled = vm.busy == null) { Icon(Icons.Default.Refresh, "Sync") }
                    IconButton(onClick = { vm.refreshCacheSize(); vm.push(Screen.Settings) }) { Icon(Icons.Default.Settings, "Settings") }
                },
            )
        },
        bottomBar = { vm.busy?.let { BusyBar(it) { vm.cancelBusy() } } },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            if (update is UpdateState.Available) item {
                UpdateBanner(vm, update as UpdateState.Available)
            }
            if (versions.isEmpty() && vm.busy == null) item {
                Text("No versions yet. Tap Sync to read the backup.", Modifier.padding(16.dp))
            }
            items(versions, key = { it.name }) { v ->
                ListItem(
                    headlineContent = { Text(formatTime(v.time)) },
                    supportingContent = {
                        Text(
                            if (v.loaded) "${v.fileCount} files · ${formatBytes(v.totalSize ?: 0)}"
                            else "File list not read yet · ${formatBytes(v.remoteSize)} list"
                        )
                    },
                    trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                    modifier = Modifier.clickable(enabled = vm.busy == null) { vm.openVersion(v) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun UpdateBanner(vm: MainViewModel, st: UpdateState.Available) {
    val ctx = LocalContext.current
    Card(
        Modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Version ${st.info.versionName} is available", Modifier.weight(1f))
            TextButton(onClick = {
                vm.installUpdate { ctx.startActivity(vm.app.updater.installPermissionIntent()) }
            }) { Text("Install") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(vm: MainViewModel) {
    val searching = vm.searchResults != null
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        vm.versions.collectAsState().value.firstOrNull { it.name == vm.browserVersion }?.let { formatTime(it.time) } ?: "Files",
                        maxLines = 1,
                    )
                },
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (vm.selectionCount > 0) TextButton(onClick = { vm.clearSelection() }) { Text("Clear") }
                },
            )
        },
        bottomBar = {
            Column {
                vm.busy?.let { BusyBar(it) }
                if (vm.selectionCount > 0) Surface(tonalElevation = 3.dp) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${vm.selectionCount} files · ${formatBytes(vm.selectionBytes)}", Modifier.weight(1f))
                        Button(onClick = { vm.prepareRestore() }, enabled = vm.busy == null) { Text("Restore") }
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                value = vm.query,
                onValueChange = vm::setSearch,
                placeholder = { Text("Search this version") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (vm.query.isNotEmpty()) IconButton(onClick = { vm.setSearch("") }) { Icon(Icons.Default.Clear, "Clear") } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            )
            if (!searching) Breadcrumbs(vm)
            LazyColumn(Modifier.fillMaxSize()) {
                if (searching) {
                    val res = vm.searchResults!!
                    if (res.size >= MainViewModel.SEARCH_LIMIT) item {
                        Text("Showing the first ${MainViewModel.SEARCH_LIMIT} matches", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    items(res, key = { "s" + it.id }) { f ->
                        ListItem(
                            leadingContent = { Checkbox(vm.isSelected(f), { vm.toggle(f) }, enabled = !vm.isLockedByParent(f.normPath)) },
                            headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text("${formatBytes(f.size)} · ${Paths.parent(f.normPath)}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.clickable { vm.toggle(f) },
                        )
                    }
                } else {
                    items(vm.subfolders, key = { "d" + it.normPath }) { d ->
                        ListItem(
                            leadingContent = { Checkbox(vm.isSelected(d), { vm.toggle(d) }, enabled = !vm.isLockedByParent(d.normPath)) },
                            headlineContent = { Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            } },
                            supportingContent = { Text("${d.fileCount} files · ${formatBytes(d.totalSize)}") },
                            trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                            modifier = Modifier.clickable { vm.openFolder(d.normPath) },
                        )
                    }
                    items(vm.files, key = { "f" + it.id }) { f ->
                        ListItem(
                            leadingContent = { Checkbox(vm.isSelected(f), { vm.toggle(f) }, enabled = !vm.isLockedByParent(f.normPath)) },
                            headlineContent = { Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null)
                                Spacer(Modifier.width(8.dp))
                                Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            } },
                            supportingContent = { Text(formatBytes(f.size)) },
                            modifier = Modifier.clickable { vm.toggle(f) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Breadcrumbs(vm: MainViewModel) {
    val segs = Paths.segments(vm.folder)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { vm.openFolder("") }) { Text("All") }
        segs.forEachIndexed { i, s ->
            Icon(Icons.Default.ChevronRight, null)
            TextButton(onClick = { vm.openFolder(segs.take(i + 1).joinToString("/")) }) { Text(s) }
        }
    }
}
