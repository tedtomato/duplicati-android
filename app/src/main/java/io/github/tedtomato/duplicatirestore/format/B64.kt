package io.github.tedtomato.duplicatirestore.format

import java.security.MessageDigest
import java.util.Base64

/** Base64 helpers. Duplicati uses standard base64 in JSON and may use base64url in zip entry names. */
object B64 {
    /** Converts base64url to standard base64 with padding. */
    fun plain(s: String): String {
        val t = s.trim().replace('-', '+').replace('_', '/')
        val pad = (4 - t.length % 4) % 4
        return if (pad == 0 || t.endsWith("=")) t else t + "=".repeat(pad)
    }

    fun encode(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    fun decode(s: String): ByteArray = Base64.getDecoder().decode(plain(s))

    fun sha256(b: ByteArray, off: Int = 0, len: Int = b.size): String =
        encode(MessageDigest.getInstance("SHA-256").apply { update(b, off, len) }.digest())

    /** A blocklist block is the raw 32-byte hashes of data blocks, joined. */
    fun splitBlocklist(data: ByteArray): List<String> {
        if (data.size % 32 != 0) throw IllegalArgumentException("Blocklist length ${data.size} is not a multiple of 32")
        return (0 until data.size / 32).map { encode(data.copyOfRange(it * 32, it * 32 + 32)) }
    }

    val EMPTY_SHA256: String = sha256(ByteArray(0))
}
