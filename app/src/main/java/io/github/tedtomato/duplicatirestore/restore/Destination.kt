package io.github.tedtomato.duplicatirestore.restore

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import io.github.tedtomato.duplicatirestore.data.DestinationMode
import io.github.tedtomato.duplicatirestore.format.Paths
import java.io.File

/**
 * Copies a checked file out of the cache to where the user wants it, keeping the folder layout
 * below [rootFolder] (the deepest folder holding everything selected; its own name is kept).
 */
class Destination(
    private val context: Context,
    private val mode: DestinationMode,
    private val treeUri: Uri?,
    private val rootFolder: String,
) {
    private val dirCache = HashMap<String, DocumentFile>()

    /** Relative folder segments for a normalized backup path, made safe for Android. */
    private fun relativeDirs(normPath: String): List<String> {
        val parent = Paths.parent(normPath)
        val base = Paths.parent(rootFolder) // keep the root folder's own name
        val rel = if (base.isEmpty()) parent else parent.removePrefix(base).trimStart('/')
        return Paths.segments(rel).map(Paths::safeSegment)
    }

    fun deliver(built: File, normPath: String): Uri {
        val name = Paths.safeSegment(Paths.name(normPath))
        val dirs = relativeDirs(normPath)
        return when (mode) {
            DestinationMode.FOLDER -> toTree(built, dirs, name)
            DestinationMode.DOWNLOADS -> toDownloads(built, dirs, name)
        }
    }

    private fun toTree(built: File, dirs: List<String>, name: String): Uri {
        val root = treeUri?.let { DocumentFile.fromTreeUri(context, it) }
            ?: throw IllegalStateException("No destination folder chosen")
        var dir = root
        var key = ""
        for (d in dirs) {
            key += "/$d"
            dir = dirCache[key] ?: run {
                val found = dir.findFile(d)?.takeIf { it.isDirectory }
                (found ?: dir.createDirectory(d) ?: throw IllegalStateException("Cannot create folder $d"))
                    .also { dirCache[key] = it }
            }
        }
        // Never overwrite: if the name exists, the provider picks a new one, e.g. "a (1).txt".
        val doc = dir.createFile("application/octet-stream", name)
            ?: throw IllegalStateException("Cannot create $name")
        copy(built, doc.uri)
        return doc.uri
    }

    private fun toDownloads(built: File, dirs: List<String>, name: String): Uri {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw IllegalStateException("Saving to Downloads needs Android 10 or later; choose a folder instead")
        }
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, (listOf(Environment.DIRECTORY_DOWNLOADS, "DuplicatiRestore") + dirs).joinToString("/"))
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Cannot create $name in Downloads")
        try {
            copy(built, uri)
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Throwable) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    private fun copy(src: File, uri: Uri) {
        val out = context.contentResolver.openOutputStream(uri, "w") ?: throw IllegalStateException("Cannot write $uri")
        out.use { o -> src.inputStream().use { it.copyTo(o, 1 shl 16) } }
    }
}
