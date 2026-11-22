package io.github.tedtomato.duplicatirestore.crypto

import java.io.ByteArrayInputStream
import java.io.EOFException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Random access to the bytes of a remote (or local) file. [read] returns exactly [length] bytes. */
interface RangeSource {
    val size: Long
    fun read(offset: Long, length: Int): ByteArray
}

/**
 * Decrypts any byte range of an AES Crypt file without reading the rest of it. CBC allows this:
 * ciphertext block n decrypts with block n-1 as its IV (block 0 uses IV2 from the header).
 *
 * The whole-file HMAC cannot be checked this way, so callers must verify what they read by other
 * means (Duplicati's block hashes).
 */
class AesRangeReader(private val src: RangeSource, passphrase: String) {
    val header: AesHeader
    private val cipherLen: Long

    /** Length of the decrypted content. */
    val plainLength: Long

    init {
        header = readHeader(passphrase)
        cipherLen = src.size - header.dataOffset - header.trailerLength
        if (cipherLen < 0 || cipherLen % 16 != 0L) {
            throw CorruptFileException("Ciphertext length $cipherLen is not a multiple of 16")
        }
        plainLength = computePlainLength()
    }

    private fun readHeader(passphrase: String): AesHeader {
        var n = minOf(src.size, 4096L).toInt()
        while (true) {
            try {
                return AesCrypt.readHeader(ByteArrayInputStream(src.read(0, n)), passphrase)
            } catch (e: EOFException) {
                // Header extensions longer than we fetched; fetch more.
                if (n.toLong() >= src.size) throw CorruptFileException("Truncated AES Crypt header")
                n = minOf(src.size, n * 16L).toInt()
            }
        }
    }

    private fun computePlainLength(): Long {
        if (cipherLen == 0L) {
            if (header.version == 3) throw CorruptFileException("AES Crypt v3 file has no ciphertext")
            return 0
        }
        // The last ciphertext block, the one before it (its IV) and the trailer.
        val lastBlockStart = header.dataOffset + cipherLen - 16
        val fetchStart = if (cipherLen >= 32) lastBlockStart - 16 else lastBlockStart
        val tail = src.read(fetchStart, (src.size - fetchStart).toInt())
        val ivOff = if (cipherLen >= 32) 0 else -1
        val iv = if (ivOff < 0) header.iv2 else tail.copyOfRange(0, 16)
        val ctOff = if (ivOff < 0) 0 else 16
        val last = decrypt(iv, tail, ctOff, 16)
        return if (header.version == 2) {
            val lastLen = tail[ctOff + 16].toInt() and 0x0F
            cipherLen - 16 + (if (lastLen == 0) 16 else lastLen)
        } else {
            val pad = last[15].toInt() and 0xFF
            if (pad !in 1..16) throw CorruptFileException("Bad PKCS#7 padding")
            cipherLen - pad
        }
    }

    /** Decrypts [length] plaintext bytes starting at [offset]. */
    fun read(offset: Long, length: Int): ByteArray {
        require(offset >= 0 && length >= 0 && offset + length <= plainLength) {
            "Range $offset+$length outside 0..$plainLength"
        }
        if (length == 0) return ByteArray(0)
        val firstBlock = offset / 16
        val lastBlock = (offset + length - 1) / 16
        val fetchStart = header.dataOffset + (if (firstBlock == 0L) 0 else (firstBlock - 1) * 16)
        val fetchEnd = header.dataOffset + (lastBlock + 1) * 16
        val bytes = src.read(fetchStart, (fetchEnd - fetchStart).toInt())
        val plain = if (firstBlock == 0L) decrypt(header.iv2, bytes, 0, bytes.size)
        else decrypt(bytes.copyOfRange(0, 16), bytes, 16, bytes.size - 16)
        val skip = (offset - firstBlock * 16).toInt()
        return plain.copyOfRange(skip, skip + length)
    }

    private fun decrypt(iv: ByteArray, data: ByteArray, off: Int, len: Int): ByteArray {
        val c = Cipher.getInstance("AES/CBC/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(header.key2, "AES"), IvParameterSpec(iv))
        return c.doFinal(data, off, len)
    }
}
