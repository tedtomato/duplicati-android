package io.github.tedtomato.duplicatirestore.crypto

import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class WrongPassphraseException :
    Exception("Wrong backup passphrase (the AES Crypt header check failed)")

class CorruptFileException(message: String) : Exception(message)

class UnsupportedFormatException(message: String) : Exception(message)

/** What the header of an AES Crypt file says. [dataOffset] is where the ciphertext starts. */
class AesHeader(
    val version: Int,
    val dataOffset: Long,
    val iterations: Int,
    internal val key2: ByteArray,
    internal val iv2: ByteArray,
) {
    /** Bytes after the ciphertext: v2 = 1 length byte + 32 HMAC, v3 = 32 HMAC. */
    val trailerLength: Int get() = if (version == 2) 33 else 32
}

/**
 * Streaming decryptor for the AES Crypt file format (versions 2 and 3), which Duplicati uses for
 * its built-in `.aes` encryption. Spec: https://www.aescrypt.com/aes_file_format.html
 */
object AesCrypt {
    private const val V2_ROUNDS = 8192

    /** Decrypts [src] into [dst]. [dst] is deleted if anything fails, including the final HMAC. */
    fun decryptFile(src: File, dst: File, passphrase: String): AesHeader {
        try {
            src.inputStream().buffered(1 shl 16).use { input ->
                dst.outputStream().buffered(1 shl 16).use { out ->
                    return decrypt(input, src.length(), out, passphrase)
                }
            }
        } catch (e: Throwable) {
            dst.delete()
            throw e
        }
    }

    /** Reads and checks the header only (enough to test the passphrase). */
    fun readHeader(input: InputStream, passphrase: String): AesHeader {
        val counting = CountingInputStream(input)
        return parseHeader(DataInputStream(counting), passphrase) { counting.count }
    }

    /**
     * Decrypts a whole AES Crypt stream of [totalLength] bytes into [out], checking the HMAC of
     * the ciphertext at the end.
     */
    fun decrypt(input: InputStream, totalLength: Long, out: OutputStream, passphrase: String): AesHeader {
        val counting = CountingInputStream(input)
        val din = DataInputStream(counting)
        val header = parseHeader(din, passphrase) { counting.count }

        val cipherLen = totalLength - header.dataOffset - header.trailerLength
        if (cipherLen < 0 || cipherLen % 16 != 0L) {
            throw CorruptFileException("Ciphertext length $cipherLen is not a multiple of 16")
        }
        if (header.version == 3 && cipherLen == 0L) {
            throw CorruptFileException("AES Crypt v3 file has no ciphertext")
        }

        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(header.key2, "AES"), IvParameterSpec(header.iv2))
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(header.key2, "HmacSHA256"))

        // Plaintext is written one chunk behind, so the last 16-byte block can be trimmed.
        val buf = ByteArray(1 shl 16)
        var held = ByteArray(0)
        var remaining = cipherLen
        while (remaining > 0) {
            val n = minOf(buf.size.toLong(), remaining).toInt()
            din.readFully(buf, 0, n)
            remaining -= n
            mac.update(buf, 0, n)
            val plain = cipher.update(buf, 0, n) ?: ByteArray(0)
            val all = if (held.isEmpty()) plain else held + plain
            if (all.size > 16) {
                out.write(all, 0, all.size - 16)
                held = all.copyOfRange(all.size - 16, all.size)
            } else {
                held = all
            }
        }

        val lastLen: Int
        val expectedMac = ByteArray(32)
        if (header.version == 2) {
            val b = din.read()
            if (b < 0) throw EOFException("Missing AES Crypt trailer")
            lastLen = b and 0x0F
            din.readFully(expectedMac)
        } else {
            lastLen = -1
            din.readFully(expectedMac)
        }
        if (!MessageDigest.isEqual(mac.doFinal(), expectedMac)) {
            throw CorruptFileException("Ciphertext HMAC mismatch: the file is damaged")
        }

        if (held.isNotEmpty()) {
            if (header.version == 2) {
                out.write(held, 0, if (lastLen == 0) 16 else lastLen)
            } else {
                val pad = held[15].toInt() and 0xFF
                if (pad !in 1..16 || (16 - pad until 16).any { (held[it].toInt() and 0xFF) != pad }) {
                    throw CorruptFileException("Bad PKCS#7 padding")
                }
                out.write(held, 0, 16 - pad)
            }
        }
        out.flush()
        return header
    }

    private fun parseHeader(din: DataInputStream, passphrase: String, position: () -> Long): AesHeader {
        val magic = ByteArray(3)
        din.readFully(magic)
        if (String(magic, Charsets.US_ASCII) != "AES") {
            throw UnsupportedFormatException("Not an AES Crypt file")
        }
        val version = din.readUnsignedByte()
        if (version != 2 && version != 3) {
            throw UnsupportedFormatException("AES Crypt version $version is not supported (only 2 and 3)")
        }
        din.readUnsignedByte() // reserved
        while (true) {
            val len = din.readUnsignedShort()
            if (len == 0) break
            din.skipFully(len)
        }
        val iterations = if (version == 3) din.readInt() else V2_ROUNDS
        val iv1 = ByteArray(16).also { din.readFully(it) }
        val encKeys = ByteArray(48).also { din.readFully(it) }
        val keysMac = ByteArray(32).also { din.readFully(it) }

        val key = if (version == 2) deriveV2(iv1, passphrase) else deriveV3(iv1, passphrase, iterations)

        val hmac = Mac.getInstance("HmacSHA256")
        hmac.init(SecretKeySpec(key, "HmacSHA256"))
        val macPlain = hmac.doFinal(encKeys)
        val ok = MessageDigest.isEqual(macPlain, keysMac) || (version == 3 && run {
            hmac.update(encKeys)
            hmac.update(version.toByte())
            MessageDigest.isEqual(hmac.doFinal(), keysMac)
        })
        if (!ok) throw WrongPassphraseException()

        val c = Cipher.getInstance("AES/CBC/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv1))
        val keys = c.doFinal(encKeys)
        return AesHeader(
            version = version,
            dataOffset = position(),
            iterations = iterations,
            key2 = keys.copyOfRange(16, 48),
            iv2 = keys.copyOfRange(0, 16),
        )
    }

    /** v2: key = IV1 + 16 zero bytes, then key = SHA256(key + UTF-16LE passphrase), 8192 times. */
    internal fun deriveV2(iv1: ByteArray, passphrase: String): ByteArray {
        val pass = passphrase.toByteArray(Charsets.UTF_16LE)
        var key = iv1 + ByteArray(16)
        val sha = MessageDigest.getInstance("SHA-256")
        repeat(V2_ROUNDS) {
            sha.update(key)
            sha.update(pass)
            key = sha.digest()
        }
        return key
    }

    /** v3: PBKDF2-HMAC-SHA512(UTF-8 passphrase, salt = IV1, iterations, 32 bytes). */
    internal fun deriveV3(iv1: ByteArray, passphrase: String, iterations: Int): ByteArray {
        if (iterations <= 0) throw CorruptFileException("Bad PBKDF2 iteration count $iterations")
        return pbkdf2HmacSha512(passphrase.toByteArray(Charsets.UTF_8), iv1, iterations, 32)
    }

    /**
     * PBKDF2 over raw bytes with HmacSHA512. Done by hand so the passphrase encoding is exactly
     * UTF-8 regardless of how a provider's PBEKeySpec turns chars into bytes.
     */
    internal fun pbkdf2HmacSha512(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        // HMAC accepts an empty key in theory, but SecretKeySpec does not.
        mac.init(if (password.isEmpty()) EmptyKey("HmacSHA512") else SecretKeySpec(password, "HmacSHA512"))
        val out = ByteArray(length)
        var block = 1
        var pos = 0
        while (pos < length) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            for (i in 1 until iterations) {
                u = mac.doFinal(u)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            block++
        }
        return out
    }

    private class EmptyKey(private val alg: String) : javax.crypto.SecretKey {
        override fun getAlgorithm() = alg
        override fun getFormat() = "RAW"
        override fun getEncoded() = ByteArray(0)
    }

    private fun DataInputStream.skipFully(n: Int) {
        var left = n
        while (left > 0) {
            val s = skipBytes(left)
            if (s <= 0) {
                if (read() < 0) throw EOFException()
                left--
            } else left -= s
        }
    }
}

internal class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    var count = 0L
        private set

    override fun read(): Int = super.read().also { if (it >= 0) count++ }
    override fun read(b: ByteArray, off: Int, len: Int): Int =
        super.read(b, off, len).also { if (it > 0) count += it }
    override fun skip(n: Long): Long = super.skip(n).also { count += it }
}
