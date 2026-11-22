package io.github.tedtomato.duplicatirestore

import io.github.tedtomato.duplicatirestore.crypto.AesCrypt
import io.github.tedtomato.duplicatirestore.crypto.AesRangeReader
import io.github.tedtomato.duplicatirestore.crypto.RangeSource
import io.github.tedtomato.duplicatirestore.format.B64
import io.github.tedtomato.duplicatirestore.format.RemoteZip
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class PartialReadTest {
    private val pass = "pässwörd 🔑"

    private class Bytes(private val b: ByteArray) : RangeSource {
        var fetched = 0L
        var requests = 0
        override val size get() = b.size.toLong()
        override fun read(offset: Long, length: Int): ByteArray {
            fetched += length; requests++
            return b.copyOfRange(offset.toInt(), offset.toInt() + length)
        }
    }

    private fun res(name: String) = javaClass.getResourceAsStream("/vectors/$name")!!.readBytes()

    private fun fullPlain(enc: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        AesCrypt.decrypt(enc.inputStream(), enc.size.toLong(), out, pass)
        return out.toByteArray()
    }

    @Test fun rangesMatchFullDecryption() {
        for (name in listOf("dblock_v2.aes", "dblock_v3.aes", "v2_17.aes", "v3_17.aes", "v2_200003.aes", "v3_200003.aes")) {
            val enc = res(name)
            val plain = fullPlain(enc)
            val r = AesRangeReader(Bytes(enc), pass)
            assertEquals(name, plain.size.toLong(), r.plainLength)
            val rnd = java.util.Random(1)
            repeat(200) {
                val off = rnd.nextInt(plain.size)
                val len = rnd.nextInt(minOf(5000, plain.size - off) + 1)
                assertArrayEquals("$name $off+$len", plain.copyOfRange(off, off + len), r.read(off.toLong(), len))
            }
            assertArrayEquals(plain, r.read(0, plain.size))
        }
    }

    @Test fun readsOnlyWantedZipEntries() {
        for (name in listOf("dblock_v2.aes", "dblock_v3.aes")) {
            val src = Bytes(res(name))
            val r = AesRangeReader(src, pass)
            val zip = RemoteZip(r.plainLength) { o, l -> r.read(o, l) }
            assertEquals(40, zip.entries.size)
            // Every entry, extracted remotely, must hash to its name.
            var n = 0
            zip.extract(zip.entries) { e, data -> assertEquals(B64.plain(e.name), B64.sha256(data)); n++ }
            assertEquals(40, n)
            // Two far-apart entries cost a fraction of the file.
            src.fetched = 0
            val two = listOf(zip.entries[3], zip.entries[35])
            zip.extract(two, maxGap = 1024) { e, data -> assertEquals(B64.plain(e.name), B64.sha256(data)) }
            assertTrue("fetched ${src.fetched} of ${src.size}", src.fetched < src.size / 4)
        }
    }
}
