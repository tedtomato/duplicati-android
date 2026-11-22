package io.github.tedtomato.duplicatirestore.format

import com.google.gson.stream.JsonReader
import java.io.File
import java.io.InputStreamReader
import java.util.zip.ZipFile

data class IndexedVolume(
    val dblockName: String,
    val volumeHash: String?,
    val volumeSize: Long,
    val blocks: List<Pair<String, Long>>,
)

data class DIndexContent(val volumes: List<IndexedVolume>, val blocklists: Map<String, ByteArray>)

/** Reads a decrypted dindex zip: `vol/<dblock>` JSON entries and `list/<hash>` blocklist copies. */
object DIndexParser {
    fun parse(zipFile: File): DIndexContent {
        val vols = mutableListOf<IndexedVolume>()
        val lists = HashMap<String, ByteArray>()
        ZipFile(zipFile).use { zip ->
            for (e in zip.entries()) {
                if (e.isDirectory) continue
                when {
                    e.name.startsWith("vol/") -> zip.getInputStream(e).use { s ->
                        vols += parseVol(e.name.removePrefix("vol/"), JsonReader(InputStreamReader(s, Charsets.UTF_8)))
                    }
                    e.name.startsWith("list/") -> zip.getInputStream(e).use { s ->
                        lists[B64.plain(e.name.removePrefix("list/"))] = s.readBytes()
                    }
                }
            }
        }
        return DIndexContent(vols, lists)
    }

    private fun parseVol(dblock: String, r: JsonReader): IndexedVolume {
        var hash: String? = null
        var size = -1L
        val blocks = mutableListOf<Pair<String, Long>>()
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "volumehash" -> hash = B64.plain(r.nextString())
                "volumesize" -> size = r.nextLong()
                "blocks" -> {
                    r.beginArray()
                    while (r.hasNext()) {
                        var h: String? = null
                        var s = -1L
                        r.beginObject()
                        while (r.hasNext()) when (r.nextName()) {
                            "hash" -> h = B64.plain(r.nextString())
                            "size" -> s = r.nextLong()
                            else -> r.skipValue()
                        }
                        r.endObject()
                        if (h != null) blocks += h to s
                    }
                    r.endArray()
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        return IndexedVolume(dblock, hash, size, blocks)
    }
}
