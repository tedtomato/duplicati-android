package io.github.tedtomato.duplicatirestore.webdav

import android.util.Xml
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class WebDavException(message: String, val code: Int = 0) : IOException(message)

class RangeNotSupportedException : IOException("The server does not support partial downloads (HTTP Range)")

data class RemoteFile(val name: String, val size: Long)

/**
 * Read-only WebDAV client: PROPFIND (Depth 1) to list the backup folder and GET (optionally with
 * Range) to download. It never sends PUT, MOVE, DELETE or any other writing method.
 */
class WebDavClient(baseUrl: String, username: String, password: String) {
    private val base: HttpUrl
    private val auth = Credentials.basic(username, password, Charsets.UTF_8)

    init {
        val withSlash = if (baseUrl.trim().endsWith("/")) baseUrl.trim() else baseUrl.trim() + "/"
        base = withSlash.toHttpUrlOrNull() ?: throw WebDavException("Invalid URL: $baseUrl")
        if (!base.isHttps) throw WebDavException("The URL must start with https://")
    }

    private fun fileUrl(name: String): HttpUrl = base.newBuilder().addPathSegment(name).build()

    fun list(): List<RemoteFile> {
        val body = """<?xml version="1.0" encoding="utf-8"?>
            |<d:propfind xmlns:d="DAV:"><d:prop><d:getcontentlength/><d:resourcetype/></d:prop></d:propfind>"""
            .trimMargin().toRequestBody("application/xml; charset=utf-8".toMediaType())
        val req = Request.Builder().url(base)
            .method("PROPFIND", body)
            .header("Depth", "1")
            .header("Authorization", auth)
            .build()
        client.newCall(req).execute().use { resp ->
            check(resp, "list the folder")
            return parsePropfind(resp.body!!.byteStream())
        }
    }

    private fun parsePropfind(input: InputStream): List<RemoteFile> {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        p.setInput(input, null)
        val out = mutableListOf<RemoteFile>()
        var href: String? = null
        var length = -1L
        var collection = false
        var text = StringBuilder()
        while (true) {
            when (p.next()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.START_TAG -> {
                    text = StringBuilder()
                    when (p.name) {
                        "response" -> { href = null; length = -1; collection = false }
                        "collection" -> if (p.namespace == "DAV:") collection = true
                    }
                }
                XmlPullParser.TEXT -> text.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "href" -> href = text.toString().trim()
                    "getcontentlength" -> length = text.toString().trim().toLongOrNull() ?: -1
                    "response" -> {
                        val h = href
                        if (h != null && !collection) {
                            val name = URLDecoder.decode(h.trimEnd('/').substringAfterLast('/').replace("+", "%2B"), "UTF-8")
                            if (name.isNotEmpty()) out += RemoteFile(name, length)
                        }
                    }
                }
            }
        }
        return out
    }

    /**
     * Downloads [name] into [dest], feeding every byte to [digest] (if any) and reporting the
     * running byte count to [onProgress]. [dest] is deleted on failure.
     */
    fun download(
        name: String,
        dest: File,
        digest: MessageDigest? = null,
        isCancelled: () -> Boolean = { false },
        onProgress: (Long) -> Unit = {},
    ) {
        val req = Request.Builder().url(fileUrl(name)).header("Authorization", auth).get().build()
        try {
            client.newCall(req).execute().use { resp ->
                check(resp, "download $name")
                resp.body!!.byteStream().use { input ->
                    dest.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16)
                        var total = 0L
                        var lastReport = 0L
                        while (true) {
                            if (isCancelled()) throw InterruptedException("Cancelled")
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            digest?.update(buf, 0, n)
                            total += n
                            if (total - lastReport >= (1 shl 19)) { onProgress(total); lastReport = total }
                        }
                        onProgress(total)
                    }
                }
            }
        } catch (e: Throwable) {
            dest.delete()
            throw e
        }
    }

    /** Small download into memory, optionally a byte range (inclusive). Returns (HTTP code, bytes). */
    fun get(name: String, range: LongRange? = null): Pair<Int, ByteArray> {
        val b = Request.Builder().url(fileUrl(name)).header("Authorization", auth).get()
        if (range != null) b.header("Range", "bytes=${range.first}-${range.last}")
        client.newCall(b.build()).execute().use { resp ->
            check(resp, "download $name")
            return resp.code to resp.body!!.bytes()
        }
    }

    /**
     * Downloads exactly [length] bytes at [offset] with an HTTP Range request. Throws
     * [RangeNotSupportedException] if the server answers with the whole file instead of 206.
     */
    fun readRange(name: String, offset: Long, length: Int): ByteArray {
        if (length == 0) return ByteArray(0)
        val req = Request.Builder().url(fileUrl(name)).header("Authorization", auth)
            .header("Range", "bytes=$offset-${offset + length - 1}")
            .header("Accept-Encoding", "identity")
            .get().build()
        client.newCall(req).execute().use { resp ->
            if (resp.code == 200) throw RangeNotSupportedException()
            check(resp, "download part of $name")
            if (resp.code != 206) throw WebDavException("Unexpected HTTP ${resp.code} for a range request")
            val cr = resp.header("Content-Range")
            if (cr != null && !cr.startsWith("bytes $offset-")) throw WebDavException("Server returned the wrong range: $cr")
            val bytes = resp.body!!.bytes()
            if (bytes.size != length) throw WebDavException("Range request returned ${bytes.size} bytes instead of $length")
            return bytes
        }
    }

    private fun check(resp: Response, what: String) {
        if (resp.isSuccessful) return
        val msg = when (resp.code) {
            401 -> "Wrong WebDAV username or password (HTTP 401)"
            403 -> "Access denied to this folder (HTTP 403). Check the sub-account's permissions."
            404 -> "Not found (HTTP 404). Check the WebDAV URL and folder path."
            405 -> "The server refused to $what (HTTP 405). This usually means the username or the URL " +
                "path is wrong, e.g. a sub-account's URL path differs from the main account's."
            else -> "Could not $what: HTTP ${resp.code} ${resp.message}"
        }
        throw WebDavException(msg, resp.code)
    }

    companion object {
        val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
