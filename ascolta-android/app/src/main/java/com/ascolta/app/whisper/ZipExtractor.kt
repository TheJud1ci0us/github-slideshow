package com.ascolta.app.whisper

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater

/**
 * Minimal zip extractor that reads the central directory itself, including ZIP64 records.
 * Qualcomm's NPU model zip holds a 2.3 GB file stored with ZIP64 headers, which some
 * Android versions' built-in zip classes don't handle reliably.
 */
object ZipExtractor {
    private const val EOCD = 0x06054b50
    private const val ZIP64_LOCATOR = 0x07064b50
    private const val ZIP64_EOCD = 0x06064b50
    private const val CENTRAL = 0x02014b50
    private const val LOCAL = 0x04034b50
    private const val MAX32 = 0xFFFFFFFFL

    private class Entry(val name: String, val method: Int, val compressed: Long, val size: Long, val localOffset: Long)

    /** Extracts [zip] into [target]; with [stripTopFolder], "folder/a/b" becomes "a/b". */
    fun extract(zip: File, target: File, stripTopFolder: Boolean = true, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        RandomAccessFile(zip, "r").use { raf ->
            val entries = readCentralDirectory(raf)
            val total = entries.sumOf { it.size }
            var done = 0L
            target.mkdirs()
            for (e in entries) {
                var name = e.name
                if (stripTopFolder) name = name.substringAfter('/', name)
                if (name.isEmpty() || name.split('/').any { it == ".." }) continue
                val out = File(target, name)
                if (e.name.endsWith("/")) {
                    out.mkdirs()
                    continue
                }
                out.parentFile?.mkdirs()
                // Local header: fixed 30 bytes, then name and extra field (lengths may differ from the central record).
                val header = read(raf, e.localOffset, 30)
                if (header.getInt(0) != LOCAL) throw IOException("Bad local header for ${e.name}")
                val dataStart = e.localOffset + 30 + (header.getShort(26).toInt() and 0xFFFF) + (header.getShort(28).toInt() and 0xFFFF)
                out.outputStream().buffered(1 shl 20).use { os ->
                    when (e.method) {
                        0 -> copy(raf, dataStart, e.compressed) { buf, n -> os.write(buf, 0, n); done += n; onProgress(done, total) }
                        8 -> inflate(raf, dataStart, e.compressed) { buf, n -> os.write(buf, 0, n); done += n; onProgress(done, total) }
                        else -> throw IOException("Unsupported compression ${e.method} for ${e.name}")
                    }
                }
                if (out.length() != e.size) throw IOException("Size mismatch for ${e.name}: ${out.length()} != ${e.size}")
            }
        }
    }

    private fun readCentralDirectory(raf: RandomAccessFile): List<Entry> {
        val len = raf.length()
        val tailSize = minOf(len, 66_000L).toInt()
        val tail = read(raf, len - tailSize, tailSize)
        var eocd = -1
        for (i in tailSize - 22 downTo 0) if (tail.getInt(i) == EOCD) { eocd = i; break }
        if (eocd < 0) throw IOException("Not a zip file")
        var count = (tail.getShort(eocd + 10).toInt() and 0xFFFF).toLong()
        var cdSize = tail.getInt(eocd + 12).toLong() and MAX32
        var cdOffset = tail.getInt(eocd + 16).toLong() and MAX32
        // ZIP64 end-of-central-directory, located just before the classic record.
        if (eocd >= 20 && tail.getInt(eocd - 20) == ZIP64_LOCATOR) {
            val z64Offset = tail.getLong(eocd - 20 + 8)
            val z64 = read(raf, z64Offset, 56)
            if (z64.getInt(0) == ZIP64_EOCD) {
                count = z64.getLong(32)
                cdSize = z64.getLong(40)
                cdOffset = z64.getLong(48)
            }
        }
        val cd = read(raf, cdOffset, cdSize.toInt())
        val entries = ArrayList<Entry>()
        var p = 0
        repeat(count.toInt()) {
            if (cd.getInt(p) != CENTRAL) throw IOException("Bad central directory")
            val method = cd.getShort(p + 10).toInt() and 0xFFFF
            var compressed = cd.getInt(p + 20).toLong() and MAX32
            var size = cd.getInt(p + 24).toLong() and MAX32
            val nameLen = cd.getShort(p + 28).toInt() and 0xFFFF
            val extraLen = cd.getShort(p + 30).toInt() and 0xFFFF
            val commentLen = cd.getShort(p + 32).toInt() and 0xFFFF
            var localOffset = cd.getInt(p + 42).toLong() and MAX32
            val nameBytes = ByteArray(nameLen)
            cd.position(p + 46)
            cd.get(nameBytes)
            // ZIP64 extra field (id 1): holds the values that were set to 0xFFFFFFFF, in this order.
            var x = p + 46 + nameLen
            val extraEnd = x + extraLen
            while (x + 4 <= extraEnd) {
                val id = cd.getShort(x).toInt() and 0xFFFF
                val dataLen = cd.getShort(x + 2).toInt() and 0xFFFF
                if (id == 1) {
                    var q = x + 4
                    if (size == MAX32) { size = cd.getLong(q); q += 8 }
                    if (compressed == MAX32) { compressed = cd.getLong(q); q += 8 }
                    if (localOffset == MAX32) { localOffset = cd.getLong(q) }
                }
                x += 4 + dataLen
            }
            entries += Entry(String(nameBytes, Charsets.UTF_8), method, compressed, size, localOffset)
            p += 46 + nameLen + extraLen + commentLen
        }
        return entries
    }

    private fun read(raf: RandomAccessFile, offset: Long, length: Int): ByteBuffer {
        val bytes = ByteArray(length)
        raf.seek(offset)
        raf.readFully(bytes)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun copy(raf: RandomAccessFile, start: Long, length: Long, sink: (ByteArray, Int) -> Unit) {
        val buf = ByteArray(1 shl 20)
        raf.seek(start)
        var left = length
        while (left > 0) {
            val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw IOException("Unexpected end of zip")
            sink(buf, n)
            left -= n
        }
    }

    private fun inflate(raf: RandomAccessFile, start: Long, length: Long, sink: (ByteArray, Int) -> Unit) {
        val inflater = Inflater(true)
        val input = ByteArray(1 shl 20)
        val output = ByteArray(1 shl 20)
        try {
            raf.seek(start)
            var left = length
            while (!inflater.finished()) {
                if (inflater.needsInput()) {
                    if (left <= 0) break
                    val n = raf.read(input, 0, minOf(input.size.toLong(), left).toInt())
                    if (n < 0) throw IOException("Unexpected end of zip")
                    left -= n
                    inflater.setInput(input, 0, n)
                }
                val produced = inflater.inflate(output)
                if (produced > 0) sink(output, produced)
                else if (inflater.needsDictionary()) throw IOException("Zip needs a dictionary")
            }
        } finally {
            inflater.end()
        }
    }
}
