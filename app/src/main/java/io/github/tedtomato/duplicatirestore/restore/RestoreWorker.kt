package io.github.tedtomato.duplicatirestore.restore

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.tedtomato.duplicatirestore.App
import io.github.tedtomato.duplicatirestore.R
import io.github.tedtomato.duplicatirestore.data.AppLog
import io.github.tedtomato.duplicatirestore.data.DestinationMode
import io.github.tedtomato.duplicatirestore.data.Session
import io.github.tedtomato.duplicatirestore.format.Paths
import java.io.File

/** Runs a restore job in the foreground, with a progress notification. */
class RestoreWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val app = ctx.applicationContext as App
    private val notificationId = 42

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB) ?: return Result.failure()
        val versionName = inputData.getString(KEY_VERSION) ?: return Result.failure()
        val dao = app.db.dao()
        val items = dao.restoreItemsNow(jobId)

        val secrets = Session.secrets
        val account = app.settings.account
        if (secrets == null || account == null) {
            for (it in items.filter { it.status == "PENDING" }) dao.setRestoreResult(it.id, "FAIL", "The app was closed and locked; start the restore again", null)
            return Result.failure()
        }
        runCatching { setForeground(foregroundInfo("Preparing…", 0, 0)) }
            .onFailure { AppLog.e("Could not start as a foreground job", it) }

        val buildDir = File(app.cacheDir, "restore/$jobId")
        try {
            val version = dao.version(versionName) ?: throw IllegalStateException("Version not in the index")
            val blocksize = version.blocksize ?: throw IllegalStateException("Version not loaded")
            val files = items.map { it.fileId }.chunked(500).flatMap { dao.filesById(it) }
            val itemByFile = items.associateBy { it.fileId }
            val engine = RestoreEngine(dao, app.repository.client(account, secrets), app.volumeCache, secrets.passphrase)
            AppLog.i("Restore $jobId: ${files.size} files from $versionName")

            var lastNotify = 0L
            val outcomes = engine.run(files, blocksize, buildDir, object : RestoreEngine.Listener {
                override fun progress(text: String, done: Long, total: Long) {
                    setProgressAsync(workDataOf(KEY_TEXT to text, KEY_DONE to done, KEY_TOTAL to total))
                    val now = System.currentTimeMillis()
                    if (now - lastNotify > 1000) {
                        lastNotify = now
                        notify(text, done, total)
                    }
                }
                override fun isCancelled() = isStopped
            })

            val mode = app.settings.destinationMode
            val dest = Destination(
                app, mode, app.settings.destinationTree?.let(Uri::parse),
                Paths.commonFolder(files.map { it.normPath }),
            )
            var ok = 0
            for (o in outcomes) {
                val item = itemByFile[o.file.id] ?: continue
                if (!o.ok || o.built == null) {
                    dao.setRestoreResult(item.id, "FAIL", o.message, null)
                    continue
                }
                try {
                    val uri = dest.deliver(o.built, o.file.normPath)
                    dao.setRestoreResult(item.id, "OK", null, uri.toString())
                    ok++
                } catch (e: Exception) {
                    AppLog.e("Could not save ${o.file.path}", e)
                    dao.setRestoreResult(item.id, "FAIL", "Checked OK but could not be saved: ${e.message}", null)
                } finally {
                    o.built.delete()
                }
            }
            AppLog.i("Restore $jobId finished: $ok of ${outcomes.size} OK")
            notifyDone("$ok of ${outcomes.size} files restored and checked")
            return Result.success()
        } catch (e: Throwable) {
            val msg = if (e is InterruptedException || isStopped) "Cancelled" else (e.message ?: e.javaClass.simpleName)
            AppLog.e("Restore $jobId failed", e)
            for (it in dao.restoreItemsNow(jobId).filter { it.status == "PENDING" }) dao.setRestoreResult(it.id, "FAIL", msg, null)
            notifyDone("Restore failed: $msg")
            return Result.failure()
        } finally {
            buildDir.deleteRecursively()
            File(app.cacheDir, "work").listFiles()?.forEach { it.delete() }
        }
    }

    private fun channel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = applicationContext.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Restores", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun build(text: String, done: Long, total: Long, ongoing: Boolean) =
        NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (ongoing) "Restoring files" else "Restore finished")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .apply { if (ongoing) setProgress(if (total > 0) 1000 else 0, if (total > 0) (done * 1000 / total).toInt() else 0, total <= 0) }
            .build()

    private fun foregroundInfo(text: String, done: Long, total: Long): ForegroundInfo {
        channel()
        val n = build(text, done, total, true)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(notificationId, n)
    }

    private fun notify(text: String, done: Long, total: Long) {
        runCatching { setForegroundAsync(foregroundInfo(text, done, total)) }
    }

    private fun notifyDone(text: String) {
        channel()
        runCatching {
            applicationContext.getSystemService(NotificationManager::class.java)
                .notify(notificationId + 1, build(text, 0, 0, false))
        }
    }

    companion object {
        const val CHANNEL = "restore"
        const val KEY_JOB = "job"
        const val KEY_VERSION = "version"
        const val KEY_TEXT = "text"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
    }
}
