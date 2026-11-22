package io.github.tedtomato.duplicatirestore.restore

import io.github.tedtomato.duplicatirestore.cache.VolumeCache
import io.github.tedtomato.duplicatirestore.crypto.AesCrypt
import io.github.tedtomato.duplicatirestore.data.AppLog
import io.github.tedtomato.duplicatirestore.format.B64
import io.github.tedtomato.duplicatirestore.index.BlocklistEntity
import io.github.tedtomato.duplicatirestore.index.FileEntry
import io.github.tedtomato.duplicatirestore.index.IndexDao
import io.github.tedtomato.duplicatirestore.webdav.WebDavClient
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** What a restore will need, shown before it starts. */
data class RestorePlan(
    val files: List<FileEntry>,
    val totalBytes: Long,
    /** dblock name -> size, for the volumes that will be read. */
    val volumes: Map<String, Long>,
    /** Bytes still to download (volumes not in the cache). */
    val downloadBytes: Long,
    /** Blocklists that are not in any dindex and will be read from dblocks first. */
    val missingBlocklists: Int,
    /** Files that cannot be restored, with the reason. */
    val problems: Map<Long, String>,
)

private class Target(val file: Int, val index: Int)

/**
 * Restores files by downloading whole dblock volumes.
 *
 * Checks, in order: every block's SHA-256 equals its name; every downloaded volume's SHA-256 equals
 * the `volumehash` from the dindex; every rebuilt file's size and SHA-256 equal the dlist entry.
 * Files are built in the cache and only handed to [deliver] once they pass.
 */
class RestoreEngine(
    private val dao: IndexDao,
    private val client: WebDavClient,
    private val cache: VolumeCache,
    private val passphrase: String,
) {
    // ---- Planning -------------------------------------------------------------------------

    /** Block hashes of [f] in order, or null if a blocklist isn't known yet. */
    private suspend fun blockHashes(f: FileEntry): List<String>? {
        if (f.size == 0L) return emptyList()
        val lists = f.blocklists?.split(',')?.filter { it.isNotEmpty() }
            ?: return listOf(f.blockhash ?: f.hash)
        val out = ArrayList<String>()
        for (bl in lists) {
            val data = dao.blocklist(bl)?.data ?: return null
            out += B64.splitBlocklist(data)
        }
        return out
    }

    private suspend fun locate(hashes: Collection<String>): Map<String, List<String>> {
        val m = HashMap<String, MutableList<String>>()
        hashes.toList().chunked(500).forEach { chunk ->
            dao.locations(chunk).forEach { m.getOrPut(it.blockHash) { mutableListOf() } += it.dblockName }
        }
        return m
    }

    private suspend fun missingBlocklists(files: List<FileEntry>): Set<String> {
        val s = HashSet<String>()
        for (f in files) {
            for (bl in f.blocklists?.split(',').orEmpty()) {
                if (bl.isNotEmpty() && dao.blocklist(bl) == null) s += bl
            }
        }
        return s
    }

    suspend fun plan(files: List<FileEntry>, blocksize: Int): RestorePlan {
        val problems = HashMap<Long, String>()
        val missingLists = missingBlocklists(files)
        val needed = HashSet<String>()
        needed += missingLists
        for (f in files) {
            val hashes = blockHashes(f)
            if (hashes == null) continue // counted via missingLists; resolved during the restore
            val expected = ((f.size + blocksize - 1) / blocksize).toInt()
            if (hashes.size != expected) {
                problems[f.id] = "Has ${hashes.size} blocks, expected $expected"
                continue
            }
            needed += hashes
        }
        val locs = locate(needed)
        for (f in files) {
            if (f.id in problems) continue
            val hashes = blockHashes(f) ?: continue
            if (hashes.any { locs[it].isNullOrEmpty() }) problems[f.id] = "A block is not in any index file (try Sync)"
        }
        if (missingLists.any { locs[it].isNullOrEmpty() }) {
            for (f in files) if (f.blocklists?.split(',')?.any { it in missingLists && locs[it].isNullOrEmpty() } == true) {
                problems[f.id] = "A blocklist is not in any index file (try Sync)"
            }
        }
        val assignment = assign(locs)
        val volNames = assignment.values.toSet()
        val volumes = volNames.associateWith { dao.volume(it)?.size ?: -1L }
        val download = volumes.filterKeys { !cache.has(it) }.values.sumOf { maxOf(it, 0) }
        return RestorePlan(
            files = files,
            totalBytes = files.sumOf { it.size },
            volumes = volumes,
            downloadBytes = download,
            missingBlocklists = missingLists.size,
            problems = problems,
        )
    }

    /**
     * Chooses one dblock per block: prefer volumes already cached, then volumes that hold many
     * of the needed blocks, so fewer volumes are downloaded.
     */
    private fun assign(locs: Map<String, List<String>>): Map<String, String> {
        val counts = HashMap<String, Int>()
        locs.values.forEach { vs -> vs.forEach { counts[it] = (counts[it] ?: 0) + 1 } }
        fun score(v: String) = (if (cache.has(v)) 1_000_000 else 0) + (counts[v] ?: 0)
        return locs.filterValues { it.isNotEmpty() }.mapValues { (_, vs) -> vs.maxBy(::score) }
    }

    // ---- Running --------------------------------------------------------------------------

    interface Listener {
        fun progress(text: String, done: Long, total: Long)
        fun isCancelled(): Boolean
    }

    class FileOutcome(val file: FileEntry, val ok: Boolean, val message: String?, val built: File?)

    /**
     * Runs the restore. Built files are left in [buildDir] for [FileOutcome]s with ok = true; the
     * caller copies them out and deletes them.
     */
    suspend fun run(files: List<FileEntry>, blocksize: Int, buildDir: File, listener: Listener): List<FileOutcome> {
        buildDir.mkdirs()
        val failed = HashMap<Int, String>()

        // 1. Blocklists missing from every dindex are read from the dblocks that hold them.
        val missing = missingBlocklists(files)
        if (missing.isNotEmpty()) {
            AppLog.i("Reading ${missing.size} blocklists from dblocks")
            val locs = locate(missing)
            val byVol = assign(locs).entries.groupBy({ it.value }, { it.key })
            for ((vol, hashes) in byVol) {
                withVolume(vol, listener, 0, 1) { zip, names ->
                    val rows = hashes.mapNotNull { h ->
                        val e = names[h] ?: return@mapNotNull null
                        val data = zip.getInputStream(e).use { it.readBytes() }
                        if (B64.sha256(data) != h) null else BlocklistEntity(h, data)
                    }
                    dao.insertBlocklists(rows)
                }
            }
        }

        // 2. Work out every file's blocks.
        val blocksOf = arrayOfNulls<List<String>>(files.size)
        files.forEachIndexed { i, f ->
            val hashes = runCatching { blockHashes(f) }.getOrNull()
            val expected = ((f.size + blocksize - 1) / blocksize).toInt()
            when {
                hashes == null -> failed[i] = "Blocklist not found"
                hashes.size != expected -> failed[i] = "Has ${hashes.size} blocks, expected $expected"
                else -> blocksOf[i] = hashes
            }
        }
        val targets = HashMap<String, MutableList<Target>>()
        blocksOf.forEachIndexed { i, hs -> hs?.forEachIndexed { j, h -> targets.getOrPut(h) { mutableListOf() } += Target(i, j) } }
        val locs = locate(targets.keys)
        for ((h, ts) in targets) if (locs[h].isNullOrEmpty()) ts.forEach { failed[it.file] = "A block is not in any index file" }
        val byVol = assign(locs).entries.groupBy({ it.value }, { it.key })

        // 3. Pre-size the output files.
        val parts = files.mapIndexed { i, _ -> File(buildDir, "$i.part") }
        files.forEachIndexed { i, f ->
            if (i !in failed) RandomAccessFile(parts[i], "rw").use { it.setLength(f.size) }
        }

        // 4. Each volume once: verify, decrypt, place blocks.
        val ordered = byVol.keys.sortedByDescending { cache.has(it) }
        ordered.forEachIndexed { vi, vol ->
            val hashes = byVol.getValue(vol).filter { h -> targets.getValue(h).any { it.file !in failed } }
            if (hashes.isEmpty()) return@forEachIndexed
            val ok = runCatching {
                withVolume(vol, listener, vi, ordered.size) { zip, names ->
                    for (h in hashes) {
                        val ts = targets.getValue(h)
                        val e = names[h]
                        if (e == null) { ts.forEach { failed[it.file] = "Block missing from $vol" }; continue }
                        val data = zip.getInputStream(e).use { it.readBytes() }
                        if (B64.sha256(data) != h) { ts.forEach { failed[it.file] = "Block hash mismatch in $vol" }; continue }
                        for (t in ts) {
                            if (t.file in failed) continue
                            val f = files[t.file]
                            val offset = t.index.toLong() * blocksize
                            val expectedLen = minOf(blocksize.toLong(), f.size - offset)
                            if (data.size.toLong() != expectedLen) { failed[t.file] = "Block ${t.index} has the wrong size"; continue }
                            RandomAccessFile(parts[t.file], "rw").use { raf -> raf.seek(offset); raf.write(data) }
                        }
                    }
                }
            }
            ok.exceptionOrNull()?.let { err ->
                if (err is InterruptedException) throw err
                AppLog.e("Volume $vol failed", err)
                hashes.forEach { h -> targets.getValue(h).forEach { failed[it.file] = err.message ?: err.javaClass.simpleName } }
            }
        }

        // 5. Check every file.
        listener.progress("Checking files…", 1, 1)
        return files.mapIndexed { i, f ->
            val reason = failed[i] ?: run {
                val p = parts[i]
                when {
                    p.length() != f.size -> "Size ${p.length()} instead of ${f.size}"
                    sha256File(p) != f.hash -> "File SHA-256 does not match the backup"
                    else -> null
                }
            }
            if (reason != null) {
                parts[i].delete()
                AppLog.i("FAIL ${f.path}: $reason")
                FileOutcome(f, false, reason, null)
            } else {
                FileOutcome(f, true, null, parts[i])
            }
        }
    }

    /**
     * Gets an encrypted volume (cache or download), checks its SHA-256 against `volumehash`,
     * decrypts it to the work folder, runs [body] on the zip, then deletes the decrypted copy.
     */
    private suspend fun withVolume(
        vol: String,
        listener: Listener,
        index: Int,
        count: Int,
        body: suspend (ZipFile, Map<String, ZipEntry>) -> Unit,
    ) {
        val info = dao.volume(vol)
        val expectedHash = info?.volumeHash
        var enc = cache.get(vol)
        if (enc != null && expectedHash != null && sha256File(enc) != expectedHash) {
            AppLog.i("Cached $vol is damaged; downloading again")
            cache.remove(vol); enc = null
        }
        if (enc == null) {
            val tmp = cache.tempFor(vol)
            val digest = MessageDigest.getInstance("SHA-256")
            val size = info?.size ?: -1
            client.download(vol, tmp, digest, listener::isCancelled) { n ->
                listener.progress("Volume ${index + 1} of $count: downloading ${mb(n)} of ${mb(size)}", n, size)
            }
            val got = B64.encode(digest.digest())
            if (expectedHash != null && got != expectedHash) {
                tmp.delete()
                throw IllegalStateException("Volume $vol: SHA-256 does not match the index (download damaged?)")
            }
            if (expectedHash == null) AppLog.i("Volume $vol has no volumehash in the index; skipped that check")
            enc = cache.commit(tmp, vol)
        }
        listener.progress("Volume ${index + 1} of $count: decrypting", 0, 1)
        val dec = File(cache.workDir, "$vol.zip")
        try {
            val h = AesCrypt.decryptFile(enc, dec, passphrase)
            if (index == 0) AppLog.i("$vol: AES Crypt version ${h.version}")
            ZipFile(dec).use { zip ->
                val names = HashMap<String, ZipEntry>()
                for (e in zip.entries()) if (!e.isDirectory) names[B64.plain(e.name)] = e
                listener.progress("Volume ${index + 1} of $count: extracting", 0, 1)
                body(zip, names)
            }
        } finally {
            dec.delete()
            cache.trim(keep = null)
        }
    }

    companion object {
        fun sha256File(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { s ->
                val buf = ByteArray(1 shl 16)
                while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            return B64.encode(md.digest())
        }

        fun mb(n: Long): String = if (n < 0) "?" else String.format(java.util.Locale.ROOT, "%.1f MB", n / 1_000_000.0)
    }
}
