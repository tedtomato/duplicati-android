package io.github.tedtomato.duplicatirestore.ui

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.github.tedtomato.duplicatirestore.App
import io.github.tedtomato.duplicatirestore.data.Account
import io.github.tedtomato.duplicatirestore.data.AppLog
import io.github.tedtomato.duplicatirestore.data.SecretStore
import io.github.tedtomato.duplicatirestore.data.Secrets
import io.github.tedtomato.duplicatirestore.data.Session
import io.github.tedtomato.duplicatirestore.data.WrongPinException
import io.github.tedtomato.duplicatirestore.index.FileEntry
import io.github.tedtomato.duplicatirestore.index.FolderEntry
import io.github.tedtomato.duplicatirestore.index.RestoreItem
import io.github.tedtomato.duplicatirestore.index.VersionEntity
import io.github.tedtomato.duplicatirestore.restore.RestoreEngine
import io.github.tedtomato.duplicatirestore.restore.RestorePlan
import io.github.tedtomato.duplicatirestore.restore.RestoreWorker
import io.github.tedtomato.duplicatirestore.sync.TestResult
import io.github.tedtomato.duplicatirestore.update.UpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

sealed interface Screen {
    data object Setup : Screen
    data object Unlock : Screen
    data object Versions : Screen
    data class Browser(val version: String) : Screen
    data object Restore : Screen
    data object Settings : Screen
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val app = application as App
    private val dao = app.db.dao()
    private val work = WorkManager.getInstance(app)

    // ---- Navigation -----------------------------------------------------------------------
    val stack = mutableStateListOf<Screen>()
    val screen: Screen get() = stack.last()

    fun push(s: Screen) { stack.add(s) }
    fun back(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false
    private fun resetTo(vararg s: Screen) { stack.clear(); stack.addAll(s) }

    var busy by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
    var info by mutableStateOf<String?>(null)
    private var job: Job? = null

    init {
        val account = app.settings.account
        when {
            account == null -> resetTo(Screen.Setup)
            Session.secrets != null -> resetTo(Screen.Versions)
            app.secretStore.hasSaved -> resetTo(Screen.Unlock)
            else -> resetTo(Screen.Setup)
        }
        maybeAutoCheckUpdate()
    }

    private fun launchBusy(start: String, block: suspend () -> Unit) {
        if (job?.isActive == true) return
        busy = start
        job = viewModelScope.launch {
            try {
                block()
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                AppLog.e(start, e)
                error = e.message ?: e.javaClass.simpleName
            } finally {
                busy = null
            }
        }
    }

    fun cancelBusy() { job?.cancel(); busy = null }

    // ---- Account and secrets --------------------------------------------------------------
    val account: Account? get() = app.settings.account
    val hasSavedSecrets get() = app.secretStore.hasSaved
    val pinAttemptsLeft get() = app.secretStore.attemptsLeft

    var testResult by mutableStateOf<TestResult?>(null)
        private set

    fun testConnection(a: Account, s: Secrets) {
        testResult = null
        launchBusy("Testing the connection…") {
            testResult = withContext(Dispatchers.IO) { app.repository.test(a, s) }
        }
    }

    fun saveAccount(a: Account, s: Secrets, pin: String) {
        launchBusy("Saving…") {
            withContext(Dispatchers.Default) { app.secretStore.save(s, pin) }
            if (app.settings.indexedFingerprint != a.fingerprint) {
                withContext(Dispatchers.IO) { dao.clearAll() }
                app.settings.indexedFingerprint = a.fingerprint
                app.settings.lastSync = 0
            }
            app.settings.account = a
            Session.secrets = s
            syncedThisSession = false
            resetTo(Screen.Versions)
        }
    }

    fun unlock(pin: String, onWrong: (String) -> Unit) {
        launchBusy("Unlocking…") {
            try {
                Session.secrets = withContext(Dispatchers.Default) { app.secretStore.unlock(pin) }
                resetTo(Screen.Versions)
            } catch (e: WrongPinException) {
                onWrong(e.message!!)
                if (!app.secretStore.hasSaved) resetTo(Screen.Setup)
            }
        }
    }

    fun forgetSecrets() {
        app.secretStore.clear()
        Session.secrets = null
        resetTo(Screen.Setup)
    }

    fun lock() {
        Session.secrets = null
        resetTo(if (app.secretStore.hasSaved) Screen.Unlock else Screen.Setup)
    }

    fun changePin(newPin: String) {
        val s = Session.secrets ?: return
        launchBusy("Saving…") {
            withContext(Dispatchers.Default) { app.secretStore.save(s, newPin) }
            info = "PIN changed"
        }
    }

    // ---- Versions -------------------------------------------------------------------------
    val versions: StateFlow<List<VersionEntity>> = dao.versions().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private var syncedThisSession = false

    fun syncIfNeeded() { if (!syncedThisSession) sync() }

    fun sync() {
        val a = account ?: return
        val s = Session.secrets ?: return lock()
        launchBusy("Connecting…") {
            withContext(Dispatchers.IO) {
                app.repository.sync(a, s) { msg -> busy = msg }
            }
            syncedThisSession = true
            app.settings.lastSync = System.currentTimeMillis()
        }
    }

    fun openVersion(v: VersionEntity) {
        val a = account ?: return
        val s = Session.secrets ?: return lock()
        launchBusy("Opening version…") {
            withContext(Dispatchers.IO) { app.repository.loadVersion(a, s, v.name) { msg -> busy = msg } }
            browserVersion = v.name
            selectedFiles.clear(); selectedFolders.clear()
            query = ""
            openFolder("")
            push(Screen.Browser(v.name))
        }
    }

    // ---- Browser --------------------------------------------------------------------------
    var browserVersion by mutableStateOf("")
        private set
    var folder by mutableStateOf("")
        private set
    var subfolders by mutableStateOf<List<FolderEntry>>(emptyList())
        private set
    var files by mutableStateOf<List<FileEntry>>(emptyList())
        private set
    var query by mutableStateOf("")
        private set
    var searchResults by mutableStateOf<List<FileEntry>?>(null)
        private set
    val selectedFiles = mutableStateListOf<FileEntry>()
    val selectedFolders = mutableStateListOf<FolderEntry>()
    private var searchJob: Job? = null

    fun openFolder(path: String) {
        folder = path
        viewModelScope.launch {
            subfolders = dao.subfolders(browserVersion, path)
            files = dao.filesIn(browserVersion, path)
        }
    }

    fun folderUp(): Boolean {
        if (folder.isEmpty()) return false
        openFolder(folder.substringBeforeLast('/', ""))
        return true
    }

    fun setSearch(q: String) {
        query = q
        searchJob?.cancel()
        if (q.isBlank()) { searchResults = null; return }
        searchJob = viewModelScope.launch {
            delay(250)
            searchResults = dao.search(browserVersion, q.trim(), SEARCH_LIMIT)
        }
    }

    private fun underSelectedFolder(normPath: String) = selectedFolders.any { normPath.startsWith(it.normPath + "/") }

    fun isSelected(f: FileEntry) = selectedFiles.any { it.id == f.id } || underSelectedFolder(f.normPath)
    fun isSelected(d: FolderEntry) = selectedFolders.any { it.normPath == d.normPath } || underSelectedFolder(d.normPath)
    fun isLockedByParent(path: String) = underSelectedFolder(path)

    fun toggle(f: FileEntry) {
        if (underSelectedFolder(f.normPath)) return
        val i = selectedFiles.indexOfFirst { it.id == f.id }
        if (i >= 0) selectedFiles.removeAt(i) else selectedFiles.add(f)
    }

    fun toggle(d: FolderEntry) {
        if (underSelectedFolder(d.normPath)) return
        val i = selectedFolders.indexOfFirst { it.normPath == d.normPath }
        if (i >= 0) selectedFolders.removeAt(i) else {
            // The folder now covers anything selected inside it.
            selectedFolders.removeAll { it.normPath.startsWith(d.normPath + "/") }
            selectedFiles.removeAll { it.normPath.startsWith(d.normPath + "/") }
            selectedFolders.add(d)
        }
    }

    fun clearSelection() { selectedFiles.clear(); selectedFolders.clear() }

    val selectionCount get() = selectedFiles.size + selectedFolders.sumOf { it.fileCount }
    val selectionBytes get() = selectedFiles.sumOf { it.size } + selectedFolders.sumOf { it.totalSize }

    // ---- Restore --------------------------------------------------------------------------
    var plan by mutableStateOf<RestorePlan?>(null)
        private set
    var jobId by mutableStateOf<String?>(null)
        private set
    var jobVersion by mutableStateOf("")
        private set
    val workInfo: Flow<WorkInfo?>
        get() = jobId?.let { id -> work.getWorkInfoByIdFlow(UUID.fromString(id)) } ?: emptyFlow()

    fun restoreItems(id: String): Flow<List<RestoreItem>> = dao.restoreItems(id)

    fun isOnMobileData(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) || cm.isActiveNetworkMetered
    }

    fun prepareRestore() {
        val a = account ?: return
        val s = Session.secrets ?: return lock()
        val version = browserVersion
        plan = null
        jobId = null
        push(Screen.Restore)
        launchBusy("Planning the restore…") {
            val p = withContext(Dispatchers.IO) {
                val chosen = LinkedHashMap<Long, FileEntry>()
                selectedFiles.forEach { chosen[it.id] = it }
                for (d in selectedFolders) dao.filesUnder(version, d.normPath + "/").forEach { chosen[it.id] = it }
                val v = dao.version(version) ?: throw IllegalStateException("Version not in the index")
                val engine = RestoreEngine(dao, app.repository.client(a, s), app.volumeCache, s.passphrase)
                engine.plan(chosen.values.toList(), v.blocksize ?: throw IllegalStateException("Version not loaded"))
            }
            plan = p
        }
    }

    fun startRestore() {
        val p = plan ?: return
        val id = UUID.randomUUID().toString()
        launchBusy("Starting…") {
            withContext(Dispatchers.IO) {
                dao.deleteOtherJobs(id)
                dao.insertRestoreItems(p.files.map { RestoreItem(jobId = id, fileId = it.id, displayPath = it.path, size = it.size) })
            }
            val req = OneTimeWorkRequestBuilder<RestoreWorker>()
                .setId(UUID.fromString(id))
                .setInputData(workDataOf(RestoreWorker.KEY_JOB to id, RestoreWorker.KEY_VERSION to browserVersion))
                .build()
            work.enqueueUniqueWork("restore", ExistingWorkPolicy.KEEP, req)
            jobVersion = browserVersion
            jobId = id
        }
    }

    fun cancelRestore() { jobId?.let { work.cancelWorkById(UUID.fromString(it)) } }

    // ---- Settings -------------------------------------------------------------------------
    var cacheBytes by mutableStateOf(0L)
        private set

    fun refreshCacheSize() { viewModelScope.launch(Dispatchers.IO) { cacheBytes = app.volumeCache.sizeBytes() } }

    fun clearCache() {
        viewModelScope.launch(Dispatchers.IO) { app.volumeCache.clear(); cacheBytes = app.volumeCache.sizeBytes() }
    }

    fun clearIndex() {
        launchBusy("Clearing the index…") {
            withContext(Dispatchers.IO) { dao.clearAll() }
            syncedThisSession = false
            info = "Index cleared. It is rebuilt on the next sync."
        }
    }

    // ---- Updates --------------------------------------------------------------------------
    val updateState: StateFlow<UpdateState> get() = app.updater.state

    private fun maybeAutoCheckUpdate() {
        val s = app.settings
        if (!s.autoUpdateCheck || System.currentTimeMillis() - s.lastUpdateCheck < 6 * 3600_000L) return
        checkUpdate()
    }

    fun checkUpdate() {
        viewModelScope.launch(Dispatchers.IO) {
            app.updater.check()
            app.settings.lastUpdateCheck = System.currentTimeMillis()
        }
    }

    fun installUpdate(onNeedPermission: () -> Unit) {
        val st = app.updater.state.value as? UpdateState.Available ?: return
        if (!app.updater.canInstall()) { onNeedPermission(); return }
        viewModelScope.launch(Dispatchers.IO) { app.updater.downloadAndInstall(st.info) }
    }

    companion object {
        const val SEARCH_LIMIT = 500
        fun validPin(p: String) = SecretStore.isValidPin(p)
    }
}
