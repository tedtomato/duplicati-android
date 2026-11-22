package io.github.tedtomato.duplicatirestore.restore

import io.github.tedtomato.duplicatirestore.cache.VolumeCache
import io.github.tedtomato.duplicatirestore.crypto.AesCrypt
import io.github.tedtomato.duplicatirestore.crypto.AesRangeReader
import io.github.tedtomato.duplicatirestore.crypto.RangeSource
import io.github.tedtomato.duplicatirestore.crypto.WrongPassphraseException
import io.github.tedtomato.duplicatirestore.data.AppLog
import io.github.tedtomato.duplicatirestore.format.B64
import io.github.tedtomato.duplicatirestore.format.RemoteZip
import io.github.tedtomato.duplicatirestore.index.BlocklistEntity
import io.github.tedtomato.duplicatirestore.index.FileEntry
import io.github.tedtomato.duplicatirestore.index.IndexDao
import io.github.tedtomato.duplicatirestore.webdav.RangeNotSupportedException
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
    /** Estimated bytes to download (whole volumes not in the cache, plus partial reads). */
    val downloadBytes: Long,
    /** Volumes that will be read partially ("quick restore"). */
    val partialVolumes: Int,
    /** Blocklists that are not in any dindex and will be read from dblocks first. */
    val missingBlocklists: Int,
    /** Files that cannot be restored, with the reason. */
    val problems: Map<Long, String>,
)

private class Target(val file: Int, val index: Int)

/**
 * Restores files from dblock volumes.
 *
 * Each volume is either downloaded whole (and kept in the cache) or, when only a small part of it
 * is needed and the server supports HTTP Range, read partially: just the AES header, the zip's
 * central directory and the needed entries ("quick restore").
 *
 * Checks: every block's SHA-256 equals its name; every rebuilt file's size and SHA-256 equal the
 * dlist entry. For whole volumes, also the volume's SHA-256 (`volumehash` from the dindex) and the
 * AES Crypt HMAC. A partial read can't check those two, but the block and file checks still apply.
 * Files are built in the cache and only handed out once they pass.
 */
class RestoreEngine(
    private val dao: IndexDao,
    private val client: WebDavClient,
    private val cache: VolumeCache,
    private val passphrase: String,
    private val partialEnabled: Boolean = true,
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

    /** Whether to read [blockCount] blocks of [vol] partially rather than download it whole. */
    private fun usePartial(vol: String, volSize: Long, blockCount: Int, blocksize: Int): Boolean =
        partialEnabled && rangeSupported != false && !cache.has(vol) && volSize > 0 &&
            blockCount.toLong() * blocksize < volSize * PARTIAL_MAX_FRACTION

    suspend fun plan(files: List<FileEntry>, blocksize: Int): RestorePlan {
        val problems = HashMap<Long, String>()
        val missingLists = missingBlocklists(files)
        val needed = HashSet<String>()
        needed += missingLists
        for (f in files) {
            val hashes = blockHashes(f) ?: continue // resolved during the restore
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
        for (f in files) if (f.blocklists?.split(',')?.any { it in missingLists && locs[it].isNullOrEmpty() } == true) {
            problems[f.id] = "A blocklist is not in any index file (try Sync)"
        }
        val assignment = assign(locs)
        val blocksPerVol = assignment.values.groupingBy { it }.eachCount()
        val volumes = blocksPerVol.keys.associateWith { dao.volume(it)?.size ?: -1L }
        var download = 0L
        var partial = 0
        for ((vol, n) in blocksPerVol) {
            val size = volumes.getValue(vol)
            when {
                cache.has(vol) -> Unit
                usePartial(vol, size, n, blocksize) -> { partial++; download += n.toLong() * blocksize + PARTIAL_OVERHEAD }
                else -> download += maxOf(size, 0)
            }
        }
        return RestorePlan(
            files = files,
            totalBytes = files.sumOf { it.size },
            volumes = volumes,
            downloadBytes = download,
            partialVolumes = partial,
            missingBlocklists = missingLists.size,
            problems = problems,
        )
    }

    /**
     * Chooses one dblock per block: prefer volumes already cached, then volumes that hold many
     * of the needed blocks, so fewer volumes are read.
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

    /** Volumes read partially during [run]. */
    var partialReads = 0
        private set

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
            val byVol = assign(locate(missing)).entries.groupBy({ it.value }, { it.key })
            for ((vol, hashes) in byVol) {
                val rows = ArrayList<BlocklistEntity>()
                readBlocks(vol, hashes.toSet(), blocksize, listener, 0, 1) { h, data ->
                    if (B64.sha256(data) == h) rows += BlocklistEntity(h, data)
                }
                dao.insertBlocklists(rows)
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

        // 4. Each volume once: read the needed blocks, check them, place them.
        val ordered = byVol.keys.sortedByDescending { cache.has(it) }
        ordered.forEachIndexed { vi, vol ->
            val hashes = byVol.getValue(vol).filter { h -> targets.getValue(h).any { it.file !in failed } }.toSet()
            if (hashes.isEmpty()) return@forEachIndexed
            val result = runCatching {
                readBlocks(vol, hashes, blocksize, listener, vi, ordered.size) { h, data ->
                    val ts = targets.getValue(h)
                    if (B64.sha256(data) != h) {
                        ts.forEach { failed[it.file] = "Block hash mismatch in $vol" }
                        return@readBlocks
                    }
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
            result.onSuccess { notFound ->
                notFound.forEach { h -> targets.getValue(h).forEach { failed[it.file] = "Block missing from $vol" } }
            }
            result.exceptionOrNull()?.let { err ->
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
     * Reads the blocks [hashes] from volume [vol], calling [onBlock] for each one found, and
     * returns the hashes that were not in the volume. Uses a partial read when worthwhile, and
     * falls back to downloading the whole volume if that fails.
     */
    private suspend fun readBlocks(
        vol: String,
        hashes: Set<String>,
        blocksize: Int,
        listener: Listener,
        index: Int,
        count: Int,
        onBlock: (String, ByteArray) -> Unit,
    ): Set<String> {
        val info = dao.volume(vol)
        val size = info?.size ?: -1
        if (usePartial(vol, size, hashes.size, blocksize)) {
            try {
                val r = readPartial(vol, size, hashes, listener, index, count, onBlock)
                partialReads++
                return r
            } catch (e: RangeNotSupportedException) {
                rangeSupported = false
                AppLog.i("The server ignores HTTP Range; downloading whole volumes from now on")
            } catch (e: InterruptedException) {
                throw e
            } catch (e: WrongPassphraseException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("Partial read of $vol failed; downloading it whole", e)
            }
        }
        return readWhole(vol, hashes, listener, index, count, onBlock)
    }

    private fun readPartial(
        vol: String,
        size: Long,
        hashes: Set<String>,
        listener: Listener,
        index: Int,
        count: Int,
        onBlock: (String, ByteArray) -> Unit,
    ): Set<String> {
        var fetched = 0L
        val label = "Volume ${index + 1} of $count: reading ${hashes.size} blocks"
        val src = object : RangeSource {
            override val size = size
            override fun read(offset: Long, length: Int): ByteArray {
                if (listener.isCancelled()) throw InterruptedException("Cancelled")
                val b = client.readRange(vol, offset, length)
                fetched += b.size
                listener.progress("$label (${mb(fetched)})", 0, 0)
                return b
            }
        }
        listener.progress(label, 0, 0)
        val aes = AesRangeReader(src, passphrase)
        if (rangeSupported == null) { rangeSupported = true; AppLog.i("The server supports HTTP Range: quick restore is available") }
        val zip = RemoteZip(aes.plainLength) { o, l -> aes.read(o, l) }
        val byName = zip.entries.associateBy { B64.plain(it.name) }
        val wanted = hashes.mapNotNull { byName[it] }
        zip.extract(wanted) { e, data -> onBlock(B64.plain(e.name), data) }
        AppLog.i("$vol: read ${wanted.size} blocks partially, ${mb(fetched)} of ${mb(size)} (AES Crypt v${aes.header.version})")
        return hashes - byName.keys
    }

    /**
     * Gets an encrypted volume (cache or download), checks its SHA-256 against `volumehash`,
     * decrypts it (checking the HMAC), reads the blocks, then deletes the decrypted copy.
     */
    private suspend fun readWhole(
        vol: String,
        hashes: Set<String>,
        listener: Listener,
        index: Int,
        count: Int,
        onBlock: (String, ByteArray) -> Unit,
    ): Set<String> {
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
                for (h2 in hashes) {
                    val e = names[h2] ?: continue
                    onBlock(h2, zip.getInputStream(e).use { it.readBytes() })
                }
                return hashes - names.keys
            }
        } finally {
            dec.delete()
            cache.trim(keep = null)
        }
    }

    companion object {
        /** Read partially only when the needed blocks are less than this share of the volume. */
        const val PARTIAL_MAX_FRACTION = 0.4

        /** Rough extra bytes for a partial read: AES header, zip tail and central directory. */
        const val PARTIAL_OVERHEAD = 96L * 1024

        /** Whether the server honours HTTP Range; null until the first partial read. */
        @Volatile var rangeSupported: Boolean? = null

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
