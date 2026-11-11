package com.ascolta.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * How familiar the learner is with each word of a language (optional feature): new words are
 * softly highlighted in transcripts until marked Familiar or Known. One small file per language.
 */
class KnownWords(private val dir: File) {
    companion object {
        const val IGNORED = -1
        const val NEW = 0
        const val SEEN = 1
        const val FAMILIAR = 2
        const val KNOWN = 3
        val LEVELS = listOf(NEW, SEEN, FAMILIAR, KNOWN)

        /** Highlighted as new: never rated, New or Seen. Familiar, Known and ignored words aren't. */
        fun highlighted(level: Int?): Boolean = level == null || level == NEW || level == SEEN

        private val ELISION = Regex("""^(l|d|j|qu|n|s|c|m|t|lorsqu|puisqu|jusqu|all|dell|nell|sull|un|quest|po)['’]""", RegexOption.IGNORE_CASE)
        val WORD = Regex("""[\p{L}][\p{L}\p{M}'’\-]*""")

        /** The form a word is remembered by: lowercase, without an elided article ("l'homme" → "homme"). */
        fun key(word: String): String =
            ELISION.replace(word.trim().trim { !it.isLetter() }, "").lowercase().trim('-', '\'', '’')

        /**
         * The word ranges of [text] to highlight as new, given the [levels] known. Skipped:
         * single letters and capitalised words inside a sentence (names).
         */
        fun newWordRanges(text: String, levels: Map<String, Int>, common: Set<String> = emptySet()): List<IntRange> {
            val out = mutableListOf<IntRange>()
            var sentenceStart = true
            var last = 0
            for (m in WORD.findAll(text)) {
                // A sentence boundary between the previous word and this one.
                if (text.substring(last, m.range.first).any { it in ".!?…" }) sentenceStart = true
                last = m.range.last + 1
                val w = m.value
                val k = key(w)
                val isName = !sentenceStart && w[0].isUpperCase()
                sentenceStart = false
                if (k.length < 2 || isName) continue
                // The language's most common words ("le", "de", "et") count as known unless rated.
                val level = levels[k] ?: if (k in common) KNOWN else null
                if (highlighted(level)) out += m.range
            }
            return out
        }

        /**
         * How much of [texts] the learner knows: share of word occurrences that are Familiar,
         * Known or ignored, and the new words by how often they come up (most first).
         */
        fun coverage(texts: List<String>, levels: Map<String, Int>, common: Set<String> = emptySet()): Pair<Float, List<Pair<String, Int>>> {
            val counts = HashMap<String, Int>()
            var total = 0
            var known = 0
            for (t in texts) for (r in newWordRangesAll(t)) {
                val k = key(t.substring(r))
                if (k.length < 2) continue
                total++
                if (!highlighted(levels[k] ?: if (k in common) KNOWN else null)) known++ else counts[k] = (counts[k] ?: 0) + 1
            }
            val share = if (total == 0) 1f else known.toFloat() / total
            return share to counts.entries.sortedByDescending { it.value }.map { it.key to it.value }
        }

        /** All word ranges of [text] except names inside sentences. */
        private fun newWordRangesAll(text: String): List<IntRange> = newWordRanges(text, emptyMap())
    }

    /** The [count] most common words of [language] (none for languages without a list). */
    fun common(language: String, lists: WordLists, count: Int = 100): Set<String> =
        lists.ranks(language)?.filterValues { it < count }?.keys.orEmpty()

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), Int.serializer())
    private val flows = ConcurrentHashMap<String, MutableStateFlow<Map<String, Int>>>()

    private fun file(language: String) = File(dir, "$language.json")

    fun levels(language: String): StateFlow<Map<String, Int>> = flows.getOrPut(language) {
        val loaded = runCatching { json.decodeFromString(serializer, file(language).readText()) }.getOrDefault(emptyMap())
        MutableStateFlow(loaded)
    }

    fun level(language: String, word: String): Int? = levels(language).value[key(word)]

    fun set(language: String, word: String, level: Int) {
        val k = key(word)
        if (k.isEmpty()) return
        val flow = levels(language) as MutableStateFlow
        flow.value = flow.value + (k to level)
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "$language.json.tmp")
            tmp.writeText(json.encodeToString(serializer, flow.value))
            tmp.renameTo(file(language))
        }
    }
}
