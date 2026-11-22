package io.github.tedtomato.duplicatirestore.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.github.tedtomato.duplicatirestore.BuildConfig
import io.github.tedtomato.duplicatirestore.data.AppLog
import io.github.tedtomato.duplicatirestore.webdav.WebDavClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class UpdateInfo(val versionCode: Int, val versionName: String, val apkUrl: String, val sha256: String?, val notes: String)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val percent: Int) : UpdateState
    data object Installing : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Self-update from the GitHub releases of [BuildConfig.UPDATE_REPO]. Each release carries the APK
 * and an `update.json` with its versionCode and SHA-256 (made by the release workflow). The APK is
 * installed with PackageInstaller; Android itself refuses it unless it is signed with the same key.
 */
class Updater(private val context: Context) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    private val http get() = WebDavClient.client

    private fun getJson(url: String): JSONObject {
        val req = Request.Builder().url(url).header("Accept", "application/vnd.github+json").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code} from $url")
            return JSONObject(r.body!!.string())
        }
    }

    /** Blocking; call off the main thread. */
    fun check(): UpdateState {
        _state.value = UpdateState.Checking
        val result = try {
            val rel = getJson("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            val assets = rel.getJSONArray("assets")
            var meta: String? = null
            var apk: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val n = a.getString("name")
                val url = a.getString("browser_download_url")
                if (n == "update.json") meta = url
                if (n.endsWith(".apk")) apk = url
            }
            if (meta == null || apk == null) throw IllegalStateException("The latest release has no update.json or APK")
            val m = getJson(meta)
            val info = UpdateInfo(
                versionCode = m.getInt("versionCode"),
                versionName = m.getString("versionName"),
                apkUrl = apk,
                sha256 = m.optString("sha256").ifEmpty { null },
                notes = rel.optString("body"),
            )
            AppLog.i("Update check: latest ${info.versionName} (${info.versionCode}), installed ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            if (info.versionCode > BuildConfig.VERSION_CODE) UpdateState.Available(info) else UpdateState.UpToDate
        } catch (e: Exception) {
            AppLog.e("Update check failed", e)
            UpdateState.Failed("Update check failed: ${e.message}")
        }
        _state.value = result
        return result
    }

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent() = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Blocking; downloads, checks the SHA-256 and hands the APK to PackageInstaller. */
    fun downloadAndInstall(info: UpdateInfo) {
        val dir = File(context.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
        val apk = File(dir, "update.apk")
        try {
            _state.value = UpdateState.Downloading(0)
            val md = MessageDigest.getInstance("SHA-256")
            http.newCall(Request.Builder().url(info.apkUrl).build()).execute().use { r ->
                if (!r.isSuccessful) throw IllegalStateException("Download failed: HTTP ${r.code}")
                val total = r.body!!.contentLength()
                r.body!!.byteStream().use { input ->
                    apk.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n); md.update(buf, 0, n); done += n
                            if (total > 0) _state.value = UpdateState.Downloading((done * 100 / total).toInt())
                        }
                    }
                }
            }
            val got = md.digest().joinToString("") { "%02x".format(it) }
            if (info.sha256 != null && !got.equals(info.sha256, ignoreCase = true)) {
                throw IllegalStateException("The downloaded APK's SHA-256 does not match update.json")
            }
            _state.value = UpdateState.Installing
            install(apk)
        } catch (e: Exception) {
            AppLog.e("Update failed", e)
            _state.value = UpdateState.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            s.openWrite("app.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                s.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(
                context, id, Intent(context, InstallReceiver::class.java).setPackage(context.packageName), flags
            )
            s.commit(pi.intentSender)
        }
    }

    internal fun onInstallStatus(status: Int, message: String?) {
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> Unit
            PackageInstaller.STATUS_SUCCESS -> _state.value = UpdateState.Idle
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                _state.value = UpdateState.Failed(
                    "Android refused the update ($message). The installed app is probably a build signed " +
                        "with a different key; uninstall it once and install the release APK."
                )
            PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = UpdateState.Failed("Update cancelled")
            else -> _state.value = UpdateState.Failed("Update failed: ${message ?: "status $status"}")
        }
    }

    companion object {
        /** Set when Android needs the user to confirm the install; MainActivity launches it. */
        val pendingConfirmation = MutableStateFlow<Intent?>(null)
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        AppLog.i("Install status $status ${message ?: ""}")
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                Updater.pendingConfirmation.value = confirm
            }
        }
        (context.applicationContext as io.github.tedtomato.duplicatirestore.App).updater.onInstallStatus(status, message)
    }
}
