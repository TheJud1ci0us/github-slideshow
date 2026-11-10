package com.ascolta.app.data

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.text.Normalizer
import java.util.zip.GZIPInputStream

/**
 * Word meanings from Wiktionary (CC BY-SA 4.0, via the kaikki.org extract), for languages
 * whose offline translation is weak or missing (Armenian). A tapped word is looked up as
 * written, or through its inflected forms ("գրքերս" → "գիրք", book).
 *
 * The data ships compressed in the app (assets/dict/<lang>.dictgz) and is unpacked once into
 * two files sorted byte by byte (meanings, forms) that are searched on disk, so the hundreds
 * of thousands of forms never sit in memory.
 */
class Dictionary(private val context: Context) {
    data class Entry(
        /** The dictionary word (may differ from the word tapped). */
        val word: String,
        /** Part of speech: noun, verb… */
        val pos: String,
        /** Meanings in English, most common first. */
        val meanings: List<String>,
        /** The word in Latin letters. */
        val romanization: String?,
    )

    /** Languages with a dictionary in the app. */
    fun has(language: String): Boolean = language in available

    private val available: Set<String> by lazy {
        context.assets.list("dict").orEmpty().filter { it.endsWith(".dictgz") }.map { it.removeSuffix(".dictgz") }.toSet()
    }

    /** Meanings of [word] in [language], or null if not found. */
    @Synchronized
    fun lookup(word: String, language: String): Entry? {
        if (!has(language)) return null
        val files = unpack(language)
        val tried = LinkedHashSet<String>()
        val clean = Normalizer.normalize(word.trim().trim { !it.isLetterOrDigit() }, Normalizer.Form.NFC)
        tried += clean
        tried += clean.lowercase()
        for (w in tried) {
            if (w.isEmpty()) continue
            find(files.first, w)?.let { return parse(it) }
            // An inflected form: its dictionary word.
            find(files.second, w)?.split('\t')?.getOrNull(2)?.let { lemma -> find(files.first, lemma)?.let { return parse(it) } }
        }
        return null
    }

    private fun parse(line: String): Entry {
        val p = line.split('\t')
        return Entry(
            word = p[1],
            pos = p.getOrNull(2).orEmpty(),
            meanings = p.getOrNull(3).orEmpty().split(" | ").filter { it.isNotBlank() },
            romanization = p.getOrNull(4)?.takeIf { it.isNotBlank() },
        )
    }

    /** Unpacks the language's data into meanings and forms files (once per app version). */
    private fun unpack(language: String): Pair<File, File> {
        val dir = File(context.filesDir, "dict/$language")
        val meanings = File(dir, "E.tsv")
        val forms = File(dir, "F.tsv")
        val stamp = File(dir, "version")
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString() }.getOrDefault("1")
        if (meanings.exists() && forms.exists() && stamp.takeIf { it.exists() }?.readText() == version) return meanings to forms
        dir.mkdirs()
        val e = File(dir, "E.tmp").bufferedWriter()
        val f = File(dir, "F.tmp").bufferedWriter()
        GZIPInputStream(context.assets.open("dict/$language.dictgz")).bufferedReader().useLines { lines ->
            lines.forEach { line ->
                when {
                    line.startsWith("E\t") -> { e.write(line); e.write("\n") }
                    line.startsWith("F\t") -> { f.write(line); f.write("\n") }
                }
            }
        }
        e.close(); f.close()
        File(dir, "E.tmp").renameTo(meanings)
        File(dir, "F.tmp").renameTo(forms)
        stamp.writeText(version)
        return meanings to forms
    }

    companion object {
        /** Binary search for the line whose second field is [key], in a file sorted by that field's UTF-8 bytes. */
        internal fun find(file: File, key: String): String? = RandomAccessFile(file, "r").use { raf ->
            val target = key.toByteArray(Charsets.UTF_8)
            var lo = 0L
            var hi = raf.length()
            while (lo < hi) {
                val mid = (lo + hi) / 2
                val (start, line) = lineAt(raf, mid) ?: return null
                val cmp = compare(field(line), target)
                when {
                    cmp == 0 -> return String(line, Charsets.UTF_8)
                    cmp < 0 -> lo = start + line.size + 1
                    else -> hi = start
                }
            }
            null
        }

        /** The line containing byte [pos]: its start and bytes (without the newline). */
        private fun lineAt(raf: RandomAccessFile, pos: Long): Pair<Long, ByteArray>? {
            var start = pos
            while (start > 0) {
                raf.seek(start - 1)
                if (raf.read() == '\n'.code) break
                start--
            }
            raf.seek(start)
            val out = java.io.ByteArrayOutputStream()
            while (true) {
                val b = raf.read()
                if (b < 0 || b == '\n'.code) break
                out.write(b)
            }
            return if (out.size() == 0 && start >= raf.length()) null else start to out.toByteArray()
        }

        /** The key: bytes between the first and second tab. */
        private fun field(line: ByteArray): ByteArray {
            val a = line.indexOf('\t'.code.toByte()) + 1
            var b = a
            while (b < line.size && line[b] != '\t'.code.toByte()) b++
            return line.copyOfRange(a, b)
        }

        private fun compare(a: ByteArray, b: ByteArray): Int {
            for (i in 0 until minOf(a.size, b.size)) {
                val x = a[i].toInt() and 0xff
                val y = b[i].toInt() and 0xff
                if (x != y) return x - y
            }
            return a.size - b.size
        }
    }
}
