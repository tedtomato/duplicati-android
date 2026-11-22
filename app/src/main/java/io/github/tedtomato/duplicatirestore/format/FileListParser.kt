package io.github.tedtomato.duplicatirestore.format

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.InputStream
import java.io.InputStreamReader

/** One `type == "File"` entry from a dlist's filelist.json. */
data class ParsedFile(
    val path: String,
    val size: Long,
    val hash: String,
    val time: String?,
    val blockhash: String?,
    val blocklists: List<String>?,
)

/** Streams filelist.json so big backups don't need the whole list in memory. */
object FileListParser {
    fun parse(input: InputStream, onFile: (ParsedFile) -> Unit) {
        val r = JsonReader(InputStreamReader(skipBom(input), Charsets.UTF_8))
        r.beginArray()
        while (r.hasNext()) {
            var type: String? = null
            var path: String? = null
            var size = -1L
            var hash: String? = null
            var time: String? = null
            var blockhash: String? = null
            var blocklists: MutableList<String>? = null
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "type" -> type = r.nextStringOrNull()
                    "path" -> path = r.nextStringOrNull()
                    "size" -> size = if (r.peek() == JsonToken.NULL) { r.nextNull(); -1 } else r.nextLong()
                    "hash" -> hash = r.nextStringOrNull()
                    "time" -> time = r.nextStringOrNull()
                    "blockhash" -> blockhash = r.nextStringOrNull()
                    "blocklists" -> {
                        if (r.peek() == JsonToken.NULL) r.nextNull() else {
                            blocklists = mutableListOf()
                            r.beginArray()
                            while (r.hasNext()) blocklists.add(B64.plain(r.nextString()))
                            r.endArray()
                        }
                    }
                    else -> r.skipValue()
                }
            }
            r.endObject()
            if (type == "File" && path != null && hash != null && size >= 0) {
                onFile(ParsedFile(path, size, B64.plain(hash), time, blockhash?.let(B64::plain), blocklists))
            }
        }
        r.endArray()
    }

    private fun JsonReader.nextStringOrNull(): String? =
        if (peek() == JsonToken.NULL) { nextNull(); null } else nextString()

    private fun skipBom(input: InputStream): InputStream {
        val b = input.buffered()
        b.mark(3)
        val bom = ByteArray(3)
        val n = b.readNBytesCompat(bom)
        if (!(n == 3 && bom[0] == 0xEF.toByte() && bom[1] == 0xBB.toByte() && bom[2] == 0xBF.toByte())) b.reset()
        return b
    }

    private fun InputStream.readNBytesCompat(buf: ByteArray): Int {
        var n = 0
        while (n < buf.size) {
            val r = read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        return n
    }
}
