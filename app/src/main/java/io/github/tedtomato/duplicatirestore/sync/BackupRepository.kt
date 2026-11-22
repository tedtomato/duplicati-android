package io.github.tedtomato.duplicatirestore.sync

import io.github.tedtomato.duplicatirestore.crypto.AesCrypt
import io.github.tedtomato.duplicatirestore.data.Account
import io.github.tedtomato.duplicatirestore.data.AppLog
import io.github.tedtomato.duplicatirestore.data.Secrets
import io.github.tedtomato.duplicatirestore.format.DIndexParser
import io.github.tedtomato.duplicatirestore.format.FileListParser
import io.github.tedtomato.duplicatirestore.format.Manifest
import io.github.tedtomato.duplicatirestore.format.Paths
import io.github.tedtomato.duplicatirestore.format.RemoteName
import io.github.tedtomato.duplicatirestore.index.AppDatabase
import io.github.tedtomato.duplicatirestore.index.BlockLoc
import io.github.tedtomato.duplicatirestore.index.BlocklistEntity
import io.github.tedtomato.duplicatirestore.index.FileEntry
import io.github.tedtomato.duplicatirestore.index.FolderEntry
import io.github.tedtomato.duplicatirestore.index.SeenIndex
import io.github.tedtomato.duplicatirestore.index.VersionEntity
import io.github.tedtomato.duplicatirestore.index.VolumeEntity
import io.github.tedtomato.duplicatirestore.webdav.RemoteFile
import io.github.tedtomato.duplicatirestore.webdav.WebDavClient
import androidx.room.withTransaction
import java.io.File
import java.util.zip.ZipFile

data class TestResult(val versions: Int, val dblocks: Int, val dindexes: Int, val newest: String?, val aesVersion: Int?, val blocksize: Int?)

/** Reads the remote backup into the local index. Never writes to the storage. */
class BackupRepository(
    private val db: AppDatabase,
    private val workDir: File,
) {
    private val dao = db.dao()

    fun client(account: Account, secrets: Secrets) = WebDavClient(account.url, account.username, secrets.password)

    /** Lists the folder and checks that the backup uses a supported setup. */
    fun listBackup(client: WebDavClient, prefix: String): Pair<List<RemoteFile>, List<RemoteName>> {
        val files = client.list()
        val names = files.mapNotNull { RemoteName.parse(it.name, prefix) }
        if (names.isEmpty()) {
            throw IllegalStateException("No Duplicati files with prefix \"$prefix\" in this folder (${files.size} other items).")
        }
        names.firstOrNull { !it.supported }?.let {
            throw IllegalStateException(
                "This backup uses ${it.compression}/${it.encryption ?: "no encryption"}. " +
                    "Only zip compression with Duplicati's AES encryption is supported."
            )
        }
        return files to names
    }

    /** "Test connection": list the folder and decrypt the newest dlist's manifest. */
    fun test(account: Account, secrets: Secrets): TestResult {
        val c = client(account, secrets)
        val (files, names) = listBackup(c, account.prefix)
        val dlists = names.filter { it.type == RemoteName.Type.DLIST }.sortedByDescending { it.time }
        val newest = dlists.firstOrNull()
        var aesVersion: Int? = null
        var blocksize: Int? = null
        if (newest != null) {
            val enc = File(workDir, "test.dlist.aes")
            val zip = File(workDir, "test.dlist.zip")
            try {
                c.download(newest.name, enc)
                val h = AesCrypt.decryptFile(enc, zip, secrets.passphrase)
                aesVersion = h.version
                AppLog.i("${newest.name}: AES Crypt version ${h.version}" + if (h.version == 3) ", ${h.iterations} PBKDF2 iterations" else "")
                ZipFile(zip).use { z ->
                    val m = Manifest.parse(z.getInputStream(z.getEntry("manifest")).readBytes())
                    blocksize = m.blocksize
                }
            } finally {
                enc.delete(); zip.delete()
            }
        }
        return TestResult(
            versions = dlists.size,
            dblocks = names.count { it.type == RemoteName.Type.DBLOCK },
            dindexes = names.count { it.type == RemoteName.Type.DINDEX },
            newest = newest?.name,
            aesVersion = aesVersion,
            blocksize = blocksize,
        )
    }

    /**
     * Refreshes the version list and reads every dindex not seen before.
     * [progress] gets (done, total) for the dindex files.
     */
    suspend fun sync(account: Account, secrets: Secrets, isCancelled: () -> Boolean = { false }, progress: (String) -> Unit) {
        val c = client(account, secrets)
        progress("Listing the backup folder…")
        val (files, names) = listBackup(c, account.prefix)
        val sizes = files.associate { it.name to it.size }

        val dlists = names.filter { it.type == RemoteName.Type.DLIST }
        val remoteVersions = dlists.map { it.name }.toSet()
        val gone = dao.versionNames().filter { it !in remoteVersions }
        if (gone.isNotEmpty()) {
            db.withTransaction { dao.deleteFilesOf(gone); dao.deleteFoldersOf(gone); dao.deleteVersions(gone) }
        }
        dao.insertVersions(dlists.map { VersionEntity(it.name, it.time!!, sizes[it.name] ?: -1) })

        val seen = dao.seenIndexNames().toSet()
        val todo = names.filter { it.type == RemoteName.Type.DINDEX && it.name !in seen }
        AppLog.i("Sync: ${dlists.size} versions, ${todo.size} new dindex files of ${names.count { it.type == RemoteName.Type.DINDEX }}")
        todo.forEachIndexed { i, n ->
            if (isCancelled()) throw InterruptedException("Cancelled")
            progress("Reading index ${i + 1} of ${todo.size}…")
            importDIndex(c, n.name, secrets.passphrase, sizes)
        }
        // Remote sizes of dblocks are also useful for the download estimate when a dindex lacks them.
        val dblocks = names.filter { it.type == RemoteName.Type.DBLOCK }
        val missing = dblocks.filter { dao.volume(it.name) == null }
        if (missing.isNotEmpty()) dao.upsertVolumes(missing.map { VolumeEntity(it.name, sizes[it.name] ?: -1, null) })
    }

    private suspend fun importDIndex(c: WebDavClient, name: String, passphrase: String, sizes: Map<String, Long>) {
        val enc = File(workDir, "$name.tmp")
        val zip = File(workDir, "$name.zip")
        try {
            c.download(name, enc)
            AesCrypt.decryptFile(enc, zip, passphrase)
            val content = DIndexParser.parse(zip)
            db.withTransaction {
                for (v in content.volumes) {
                    val size = sizes[v.dblockName]?.takeIf { it >= 0 } ?: v.volumeSize
                    dao.upsertVolumes(listOf(VolumeEntity(v.dblockName, size, v.volumeHash)))
                    v.blocks.chunked(500).forEach { chunk ->
                        dao.insertBlockLocs(chunk.map { BlockLoc(it.first, v.dblockName, it.second) })
                    }
                }
                dao.insertBlocklists(content.blocklists.map { BlocklistEntity(it.key, it.value) })
                dao.insertSeen(listOf(SeenIndex(name)))
            }
        } finally {
            enc.delete(); zip.delete()
        }
    }

    /** Downloads and parses a version's dlist into the index, if not done before. */
    suspend fun loadVersion(account: Account, secrets: Secrets, versionName: String, progress: (String) -> Unit) {
        val v = dao.version(versionName) ?: throw IllegalStateException("Unknown version $versionName")
        if (v.loaded) return
        val c = client(account, secrets)
        val enc = File(workDir, "$versionName.tmp")
        val zip = File(workDir, "$versionName.zip")
        try {
            progress("Downloading the file list…")
            c.download(versionName, enc)
            progress("Decrypting…")
            val h = AesCrypt.decryptFile(enc, zip, secrets.passphrase)
            AppLog.i("$versionName: AES Crypt version ${h.version}")
            progress("Reading the file list…")
            ZipFile(zip).use { z ->
                val m = Manifest.parse(z.getInputStream(z.getEntry("manifest") ?: throw IllegalStateException("dlist has no manifest")).readBytes())
                val entry = z.getEntry("filelist.json") ?: throw IllegalStateException("dlist has no filelist.json")
                val folders = HashMap<String, LongArray>() // normPath -> [count, size]
                var count = 0
                var total = 0L
                db.withTransaction {
                    dao.deleteFilesOf(listOf(versionName))
                    dao.deleteFoldersOf(listOf(versionName))
                    val batch = ArrayList<FileEntry>(1000)
                    z.getInputStream(entry).use { input ->
                        FileListParser.parse(input) { f ->
                            val norm = Paths.normalize(f.path)
                            val parent = Paths.parent(norm)
                            batch += FileEntry(
                                versionName = versionName, path = f.path, normPath = norm, parentPath = parent,
                                name = Paths.name(norm), size = f.size, hash = f.hash, time = f.time,
                                blockhash = f.blockhash, blocklists = f.blocklists?.joinToString(","),
                            )
                            count++; total += f.size
                            var p = parent
                            while (p.isNotEmpty()) {
                                val a = folders.getOrPut(p) { LongArray(2) }
                                a[0]++; a[1] += f.size
                                p = Paths.parent(p)
                            }
                        }
                    }
                    // FileListParser's callback isn't suspend; insert after parsing in chunks.
                    batch.chunked(1000).forEach { dao.insertFiles(it) }
                    dao.insertFolders(folders.map { (path, a) ->
                        FolderEntry(versionName, path, Paths.parent(path), Paths.name(path), a[0].toInt(), a[1])
                    })
                    dao.markLoaded(versionName, count, total, m.blocksize)
                }
                AppLog.i("$versionName: $count files, $total bytes, block size ${m.blocksize}")
            }
        } finally {
            enc.delete(); zip.delete()
        }
    }
}
