package io.github.tedtomato.duplicatirestore.cache

import java.io.File

/**
 * Keeps downloaded *encrypted* dblock volumes in the app's cache folder, trimmed to a size limit by
 * least recent use. Decrypted copies live elsewhere and are deleted after each volume.
 */
class VolumeCache(root: File, private val limitBytes: () -> Long) {
    val dir = File(root, "volumes").apply { mkdirs() }
    val workDir = File(root, "work").apply { mkdirs() }

    fun get(name: String): File? = File(dir, name).takeIf { it.isFile }?.also { it.setLastModified(System.currentTimeMillis()) }

    fun has(name: String) = File(dir, name).isFile

    fun tempFor(name: String) = File(dir, "$name.part")

    fun commit(temp: File, name: String): File {
        val f = File(dir, name)
        f.delete()
        if (!temp.renameTo(f)) throw IllegalStateException("Could not store $name in the cache")
        return f
    }

    fun remove(name: String) { File(dir, name).delete() }

    /** Deletes the least recently used volumes until the cache fits, never [keep]. */
    fun trim(keep: String? = null) {
        val limit = limitBytes()
        val files = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") } ?: return
        var total = files.sumOf { it.length() }
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= limit) break
            if (f.name == keep) continue
            total -= f.length()
            f.delete()
        }
    }

    fun sizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
        workDir.listFiles()?.forEach { it.deleteRecursively() }
    }
}
