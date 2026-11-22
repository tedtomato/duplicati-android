package io.github.tedtomato.duplicatirestore

import io.github.tedtomato.duplicatirestore.crypto.AesCrypt
import io.github.tedtomato.duplicatirestore.crypto.CorruptFileException
import io.github.tedtomato.duplicatirestore.crypto.WrongPassphraseException
import io.github.tedtomato.duplicatirestore.format.B64
import io.github.tedtomato.duplicatirestore.format.FileListParser
import io.github.tedtomato.duplicatirestore.format.Manifest
import io.github.tedtomato.duplicatirestore.format.ParsedFile
import io.github.tedtomato.duplicatirestore.format.Paths
import io.github.tedtomato.duplicatirestore.format.RemoteName
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

class FormatTest {
    private val pass = "pässwörd 🔑"

    private fun vector(name: String): File {
        val tmp = Files.createTempFile("vec", name).toFile()
        javaClass.getResourceAsStream("/vectors/$name")!!.use { i -> tmp.outputStream().use { i.copyTo(it) } }
        return tmp
    }

    @Test fun decryptsV2AndV3() {
        for (n in listOf(17, 200003)) for (v in listOf(2, 3)) {
            val out = Files.createTempFile("out", ".bin").toFile()
            val h = AesCrypt.decryptFile(vector("v${v}_$n.aes"), out, pass)
            assertEquals(v, h.version)
            assertArrayEquals(vector("p$n.bin").readBytes(), out.readBytes())
        }
    }

    @Test(expected = WrongPassphraseException::class)
    fun wrongPassphrase() {
        AesCrypt.decryptFile(vector("v2_17.aes"), Files.createTempFile("o", "").toFile(), "nope")
    }

    @Test fun damagedFileIsRejectedAndOutputDeleted() {
        val f = vector("v3_200003.aes")
        val b = f.readBytes(); b[b.size - 100] = (b[b.size - 100].toInt() xor 1).toByte(); f.writeBytes(b)
        val out = Files.createTempFile("o", "").toFile()
        try { AesCrypt.decryptFile(f, out, pass); error("no exception") } catch (_: CorruptFileException) {}
        assertFalse(out.exists())
    }

    @Test fun parsesDlist() {
        val zip = Files.createTempFile("dlist", ".zip").toFile()
        AesCrypt.decryptFile(vector("dlist.aes"), zip, pass)
        val files = mutableListOf<ParsedFile>()
        ZipFile(zip).use { z ->
            assertEquals(102400, Manifest.parse(z.getInputStream(z.getEntry("manifest")).readBytes()).blocksize)
            FileListParser.parse(z.getInputStream(z.getEntry("filelist.json"))) { files += it }
        }
        assertEquals(2, files.size)
        assertEquals("C:\\Users\\a\\big.bin", files[0].path)
        assertEquals(1, files[0].blocklists!!.size)
        assertEquals(files[0].blocklists!![0], B64.plain(files[0].blocklists!![0])) // stored as standard base64
        assertNull(files[1].blocklists)
        assertEquals(B64.EMPTY_SHA256, files[1].hash)
    }

    @Test fun base64AndPaths() {
        assertEquals("ab+/cQ==", B64.plain("ab-_cQ"))
        assertEquals("C:/Users/x/a.txt", Paths.normalize("C:\\Users\\x\\a.txt"))
        assertEquals("C:/Users", Paths.parent("C:/Users/x"))
        assertEquals("C:/A", Paths.commonFolder(listOf("C:/A/b/1", "C:/A/c/2")))
        assertEquals("C", Paths.safeSegment("C:"))
        val r = RemoteName.parse("duplicati-20261117T170000Z.dlist.zip.aes", "duplicati")!!
        assertEquals(1794934800000L, r.time)
        assertFalse(RemoteName.parse("duplicati-bab.dblock.7z.aes", "duplicati")!!.supported)
    }

    @Test fun pbkdf2MatchesJdk() {
        val salt = ByteArray(16) { it.toByte() }
        val mine = AesCrypt.pbkdf2HmacSha512("pass".toByteArray(), salt, 1000, 32)
        val jdk = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
            .generateSecret(javax.crypto.spec.PBEKeySpec("pass".toCharArray(), salt, 1000, 256)).encoded
        assertArrayEquals(jdk, mine)
    }
}
