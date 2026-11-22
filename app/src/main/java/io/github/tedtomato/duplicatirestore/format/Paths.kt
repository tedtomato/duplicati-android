package io.github.tedtomato.duplicatirestore.format

/**
 * Turns backup paths into a folder tree. Windows paths split on `\` (and `/`), with the drive
 * letter as the root folder: `C:\Users\x\a.txt` -> `C:/Users/x/a.txt`.
 */
object Paths {
    fun segments(path: String): List<String> = path.split('\\', '/').filter { it.isNotEmpty() }

    fun normalize(path: String): String = segments(path).joinToString("/")

    fun parent(norm: String): String = norm.substringBeforeLast('/', "")

    fun name(norm: String): String = norm.substringAfterLast('/')

    /** Deepest folder that contains all the given normalized file paths ("" if none in common). */
    fun commonFolder(paths: Collection<String>): String {
        if (paths.isEmpty()) return ""
        var common = segments(parent(paths.first()))
        for (p in paths) {
            val s = segments(parent(p))
            var i = 0
            while (i < common.size && i < s.size && common[i] == s[i]) i++
            common = common.subList(0, i)
        }
        return common.joinToString("/")
    }

    /** Makes a path segment safe for Android file systems (drive "C:" becomes "C"). */
    fun safeSegment(s: String): String =
        s.replace(Regex("[:*?\"<>|]"), "").trim().ifEmpty { "_" }
}
