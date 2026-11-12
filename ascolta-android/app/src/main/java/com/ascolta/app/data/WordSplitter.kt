package com.ascolta.app.data

import java.text.BreakIterator
import java.util.Locale

/**
 * Splits sentences into words in every language, including those written without spaces
 * (Japanese, Chinese, Thai…). Android's BreakIterator is ICU's, which uses dictionaries for
 * those scripts, so "今日はいい天気です" becomes 今日 / は / いい / 天気 / です instead of one
 * "word" for the whole sentence.
 *
 * It also gives each word a start time from the speech engine's timed pieces (tokens), so
 * the highlight moves word by word: a word starts when the piece containing its first
 * character starts; inside a long piece, time is spread evenly over its characters.
 */
object WordSplitter {
    /** Character ranges of the words in [text] (punctuation and spaces left out). */
    fun ranges(text: String, language: String?): List<IntRange> {
        if (text.isEmpty()) return emptyList()
        // Japanese: the same words that carry the readings (furigana / romaji), once loaded.
        if (language == "ja") Japanese.tokens(text)?.let { tokens ->
            return tokens.filter { it.isWord }.map { it.start until it.end }
        }
        val it = BreakIterator.getWordInstance(language?.let(Locale::forLanguageTag) ?: Locale.getDefault())
        it.setText(text)
        val out = mutableListOf<IntRange>()
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            if ((start until end).any { i -> text[i].isLetterOrDigit() }) out += start until end
            start = end
            end = it.next()
        }
        return joinApostrophes(text, out)
    }

    /** Keeps "dell'acqua" / "aujourd'hui" / "don't" as one word, as learners expect. */
    private fun joinApostrophes(text: String, ranges: List<IntRange>): List<IntRange> {
        val out = mutableListOf<IntRange>()
        for (r in ranges) {
            val last = out.lastOrNull()
            if (last != null && r.first == last.last + 2 && text[last.last + 1] in "'’") {
                out[out.lastIndex] = last.first..r.last
            } else out += r
        }
        return out
    }

    /** The word at character [offset] (or just before it, when tapping between words). */
    fun wordAt(text: String, offset: Int, language: String?): String? {
        val ranges = ranges(text, language)
        val hit = ranges.firstOrNull { offset in it }
            ?: ranges.lastOrNull { it.last < offset && offset - it.last <= 1 }
            ?: return null
        return text.substring(hit.first, hit.last + 1)
    }

    /**
     * Words of [text] with start times, from timed [pieces] (engine tokens or older,
     * coarser word timings) that together spell the sentence. [endMs] bounds the last piece.
     */
    /**
     * The words of [text] with times spread over the sentence by their place in it: for
     * selecting words when the speech model gave no word times (the audio of a selection is
     * then approximate).
     */
    fun estimatedWords(text: String, startMs: Long, endMs: Long, language: String?): List<Word> {
        val length = text.length.coerceAtLeast(1)
        return ranges(text, language).map { r -> Word(startMs + (endMs - startMs) * r.first / length, text.substring(r.first, r.last + 1)) }
    }

    fun timedWords(text: String, pieces: List<Word>?, startMs: Long, endMs: Long, language: String?): List<Word>? {
        if (pieces.isNullOrEmpty()) return pieces
        val times = charTimes(text, pieces, startMs, endMs) ?: return null
        val words = ranges(text, language).map { r -> Word(times[r.first], text.substring(r.first, r.last + 1)) }
        // Keep times in order (engines occasionally give a later token an earlier time).
        var last = startMs
        return words.map { w -> w.copy(startMs = maxOf(w.startMs, last).also { last = it }) }.ifEmpty { null }
    }

    /**
     * Start time of every character of [text]: the pieces' characters are matched to the
     * text's, ignoring spaces (pieces may be trimmed or have leading spaces).
     */
    private fun charTimes(text: String, pieces: List<Word>, startMs: Long, endMs: Long): LongArray? {
        // Time of each non-space character of the pieces, spread across each piece.
        val pieceChars = StringBuilder()
        val pieceTimes = ArrayList<Long>()
        pieces.forEachIndexed { k, p ->
            val chars = p.text.filterNot { it.isWhitespace() }
            val t0 = p.startMs
            val t1 = maxOf(t0, pieces.getOrNull(k + 1)?.startMs ?: endMs)
            chars.forEachIndexed { j, c ->
                pieceChars.append(c)
                pieceTimes += t0 + (t1 - t0) * j / chars.length.coerceAtLeast(1)
            }
        }
        if (pieceTimes.isEmpty()) return null
        val out = LongArray(text.length)
        var k = 0
        var current = startMs
        for (i in text.indices) {
            val c = text[i]
            if (!c.isWhitespace()) {
                // Advance to the matching character; if the texts differ, keep the position.
                var m = k
                while (m < pieceChars.length && pieceChars[m] != c && m - k < 4) m++
                if (m < pieceChars.length && pieceChars[m] == c) {
                    current = pieceTimes[m]
                    k = m + 1
                } else if (k < pieceTimes.size) {
                    current = pieceTimes[k]
                }
            }
            out[i] = current
        }
        return out
    }
}
