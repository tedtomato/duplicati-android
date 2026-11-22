package io.github.tedtomato.duplicatirestore.format

import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** A parsed remote file name: `<prefix>-<id>.<type>.<compression>.<encryption>`. */
data class RemoteName(
    val name: String,
    val type: Type,
    val compression: String,
    val encryption: String?,
    /** Version time in epoch millis, for dlist files. */
    val time: Long?,
) {
    enum class Type { DLIST, DBLOCK, DINDEX }

    val supported: Boolean get() = compression == "zip" && encryption == "aes"

    companion object {
        private val TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

        fun parse(name: String, prefix: String): RemoteName? {
            if (!name.startsWith("$prefix-")) return null
            val rest = name.substring(prefix.length + 1)
            val parts = rest.split('.')
            if (parts.size < 3) return null
            val type = when (parts[1]) {
                "dlist" -> Type.DLIST
                "dblock" -> Type.DBLOCK
                "dindex" -> Type.DINDEX
                else -> return null
            }
            val time = if (type == Type.DLIST) {
                runCatching {
                    LocalDateTime.parse(parts[0], TIME).toInstant(ZoneOffset.UTC).toEpochMilli()
                }.getOrNull() ?: return null
            } else null
            return RemoteName(name, type, parts[2], parts.getOrNull(3), time)
        }
    }
}
