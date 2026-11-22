package io.github.tedtomato.duplicatirestore.format

import java.util.zip.CRC32
import java.util.zip.Inflater

class ZipEntryInfo(
    val name: String,
    val method: Int,
    val compressedSize: Long,
    val size: Long,
    val crc: Long,
    val localHeaderOffset: Long,
)

class ZipFormatException(message: String) : Exception(message)

/**
 * Reads a zip archive through a random-access [read] function, fetching only the central directory
 * and the entries asked for. Supports stored and deflated entries and zip64.
 */
class RemoteZip(private val length: Long, private val read: (offset: Long, length: Int) -> ByteArray) {
    val entries: List<ZipEntryInfo> = readCentralDirectory()

    private fun readCentralDirectory(): List<ZipEntryInfo> {
        val tailLen = minOf(length, 22L + 65535L).toInt()
        val tailStart = length - tailLen
        val tail = read(tailStart, tailLen)
        var eocd = -1
        for (i in tail.size - 22 downTo 0) if (u32(tail, i) == 0x06054b50L) { eocd = i; break }
        if (eocd < 0) throw ZipFormatException("No end of central directory record")
        var count = u16(tail, eocd + 10).toLong()
        var cdSize = u32(tail, eocd + 12)
        var cdOffset = u32(tail, eocd + 16)
        if (count == 0xFFFFL || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
            // zip64: the locator sits just before the EOCD record.
            val locAbs = tailStart + eocd - 20
            val loc = if (eocd >= 20) tail.copyOfRange(eocd - 20, eocd) else read(locAbs, 20)
            if (u32(loc, 0) != 0x07064b50L) throw ZipFormatException("Missing zip64 locator")
            val recOffset = u64(loc, 8)
            val rec = read(recOffset, 56)
            if (u32(rec, 0) != 0x06064b50L) throw ZipFormatException("Missing zip64 end record")
            count = u64(rec, 32)
            cdSize = u64(rec, 40)
            cdOffset = u64(rec, 48)
        }
        if (cdOffset + cdSize > length) throw ZipFormatException("Central directory outside the archive")
        val cd = if (cdOffset >= tailStart) {
            tail.copyOfRange((cdOffset - tailStart).toInt(), (cdOffset - tailStart + cdSize).toInt())
        } else read(cdOffset, cdSize.toInt())

        val out = ArrayList<ZipEntryInfo>(count.toInt())
        var p = 0
        while (p + 46 <= cd.size && u32(cd, p) == 0x02014b50L) {
            val method = u16(cd, p + 10)
            val crc = u32(cd, p + 16)
            var comp = u32(cd, p + 20)
            var size = u32(cd, p + 24)
            val nameLen = u16(cd, p + 28)
            val extraLen = u16(cd, p + 30)
            val commentLen = u16(cd, p + 32)
            var lho = u32(cd, p + 42)
            val name = String(cd, p + 46, nameLen, Charsets.UTF_8)
            // zip64 extra field: only the fields that are 0xFFFFFFFF above are present, in order.
            var e = p + 46 + nameLen
            val eEnd = e + extraLen
            while (e + 4 <= eEnd) {
                val id = u16(cd, e)
                val len = u16(cd, e + 2)
                if (id == 0x0001) {
                    var q = e + 4
                    if (size == 0xFFFFFFFFL) { size = u64(cd, q); q += 8 }
                    if (comp == 0xFFFFFFFFL) { comp = u64(cd, q); q += 8 }
                    if (lho == 0xFFFFFFFFL) { lho = u64(cd, q) }
                }
                e += 4 + len
            }
            out += ZipEntryInfo(name, method, comp, size, crc, lho)
            p += 46 + nameLen + extraLen + commentLen
        }
        if (out.size.toLong() != count) throw ZipFormatException("Central directory lists ${out.size} of $count entries")
        return out
    }

    /**
     * Reads the [wanted] entries, joining entries that lie close together into one read so a
     * few hundred small blocks don't cost a few hundred requests. [onEntry] gets the uncompressed
     * bytes, already checked against the CRC-32 in the zip.
     */
    fun extract(
        wanted: Collection<ZipEntryInfo>,
        maxGap: Long = 256 * 1024,
        maxSpan: Long = 8L * 1024 * 1024,
        onEntry: (ZipEntryInfo, ByteArray) -> Unit,
    ) {
        val sorted = wanted.sortedBy { it.localHeaderOffset }
        var i = 0
        while (i < sorted.size) {
            val start = sorted[i].localHeaderOffset
            var j = i
            var end = estimatedEnd(sorted[i])
            while (j + 1 < sorted.size) {
                val next = sorted[j + 1]
                val nextEnd = estimatedEnd(next)
                if (next.localHeaderOffset - end > maxGap || nextEnd - start > maxSpan) break
                end = maxOf(end, nextEnd)
                j++
            }
            val buf = read(start, (minOf(end, length) - start).toInt())
            for (k in i..j) {
                val entry = sorted[k]
                onEntry(entry, entryData(entry, buf, start))
            }
            i = j + 1
        }
    }

    /** Local header (30 bytes + name + an allowance for its extra field) plus the data. */
    private fun estimatedEnd(e: ZipEntryInfo): Long =
        e.localHeaderOffset + 30 + e.name.toByteArray(Charsets.UTF_8).size + 512 + e.compressedSize

    private fun entryData(e: ZipEntryInfo, buf: ByteArray, bufStart: Long): ByteArray {
        var local = (e.localHeaderOffset - bufStart).toInt()
        var b = buf
        if (local + 30 > b.size) { b = read(e.localHeaderOffset, 30); local = 0 }
        if (u32(b, local) != 0x04034b50L) throw ZipFormatException("Bad local header for ${e.name}")
        val dataStart = local + 30 + u16(b, local + 26) + u16(b, local + 28)
        val comp = if (dataStart + e.compressedSize <= b.size) {
            b.copyOfRange(dataStart, (dataStart + e.compressedSize).toInt())
        } else {
            read(e.localHeaderOffset + (dataStart - local), e.compressedSize.toInt())
        }
        val data = when (e.method) {
            0 -> comp
            8 -> inflate(comp, e.size)
            else -> throw ZipFormatException("Unsupported compression method ${e.method} for ${e.name}")
        }
        if (data.size.toLong() != e.size) throw ZipFormatException("${e.name}: ${data.size} bytes instead of ${e.size}")
        val crc = CRC32().apply { update(data) }.value
        if (crc != e.crc) throw ZipFormatException("${e.name}: CRC-32 mismatch")
        return data
    }

    private fun inflate(comp: ByteArray, size: Long): ByteArray {
        val inf = Inflater(true)
        try {
            // With nowrap, zlib may need one extra dummy byte after the deflate stream.
            inf.setInput(comp + byteArrayOf(0))
            val out = ByteArray(size.toInt())
            var n = 0
            while (n < out.size) {
                val r = inf.inflate(out, n, out.size - n)
                if (r == 0 && (inf.finished() || inf.needsInput() || inf.needsDictionary())) break
                n += r
            }
            return if (n == out.size) out else out.copyOf(n)
        } finally {
            inf.end()
        }
    }

    private companion object {
        fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        fun u32(b: ByteArray, o: Int) = (u16(b, o).toLong()) or (u16(b, o + 2).toLong() shl 16)
        fun u64(b: ByteArray, o: Int) = u32(b, o) or (u32(b, o + 4) shl 32)
    }
}
