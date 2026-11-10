package com.ascolta.app.data

/** A word in the transcript: sentence number and word number in it. */
data class WordPos(val segment: Int, val word: Int) : Comparable<WordPos> {
    override fun compareTo(other: WordPos) = compareValuesBy(this, other, { it.segment }, { it.word })
}

/** A stretch of the transcript chosen by the learner: its text, where it is in the audio, and the sentences around it. */
data class Selected(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    /** The whole sentence(s) the selection is in (context for the card). */
    val context: String,
    val contextTranslation: String?,
    /** Just one word. */
    val single: Boolean,
    /**
     * [text] and [context] without the sentence-ending punctuation between transcript sentences
     * (the model sometimes ends a sentence too early); null when there is none to remove.
     */
    val joinedText: String? = null,
    val joinedContext: String? = null,
) {
    /** Crosses two sentences with a "." or "?" between them that can be removed. */
    val canJoin: Boolean get() = joinedText != null && joinedText != text

    /** This selection with the punctuation between the sentences removed. */
    fun joined(): Selected = if (!canJoin) this else copy(text = joinedText!!, context = joinedContext ?: context)
}

/**
 * From a start and an end word (in any order, possibly in different sentences) to the text
 * and the time range of the audio (pure, unit-tested). Words are timed by their start; a
 * word ends where the next one starts (at most 1.5 s later) or with its sentence. Without
 * word times (transcripts published by the podcast), whole sentences are used.
 */
object Selection {
    private const val MAX_WORD_MS = 1_500L

    fun of(
        segments: List<Segment>,
        timed: List<List<Word>?>,
        ranges: List<List<IntRange>>,
        from: WordPos,
        to: WordPos,
    ): Selected {
        val (a, b) = if (from <= to) from to to else to to from
        val first = segments[a.segment]
        val last = segments[b.segment]
        val firstWords = timed.getOrNull(a.segment)
        val lastWords = timed.getOrNull(b.segment)
        val firstRanges = ranges.getOrNull(a.segment).orEmpty()
        val lastRanges = ranges.getOrNull(b.segment).orEmpty()
        val wordTimed = !firstWords.isNullOrEmpty() && !lastWords.isNullOrEmpty() &&
            a.word in firstWords.indices && b.word in lastWords.indices

        val startMs = if (wordTimed) firstWords!![a.word].startMs else first.startMs
        val endMs = if (wordTimed) {
            val w = lastWords!![b.word]
            val next = lastWords.getOrNull(b.word + 1)?.startMs ?: last.endMs
            minOf(next, w.startMs + MAX_WORD_MS).coerceAtLeast(w.startMs + 200)
        } else last.endMs

        val parts = (a.segment..b.segment).map { i ->
            val text = segments[i].text
            val rs = ranges.getOrNull(i).orEmpty()
            val startChar = if (i == a.segment) firstRanges.getOrNull(a.word)?.takeIf { !it.isEmpty() }?.first ?: 0 else 0
            val endChar = if (i == b.segment) lastRanges.getOrNull(b.word)?.takeIf { !it.isEmpty() }?.let { it.last + 1 } ?: text.length else text.length
            if (rs.isEmpty()) text else text.substring(startChar.coerceIn(0, text.length), endChar.coerceIn(startChar, text.length))
        }
        val text = parts.joinToString(" ") { it.trim() }.trim()
        val contextParts = (a.segment..b.segment).map { segments[it].text }
        val context = contextParts.joinToString(" ") { it.trim() }
        val joinedText = if (parts.size > 1) join(parts) else null
        val translations = (a.segment..b.segment).mapNotNull { segments[it].translation?.trim() }
        return Selected(
            text = text,
            startMs = startMs,
            endMs = endMs,
            context = context,
            contextTranslation = translations.takeIf { it.size == b.segment - a.segment + 1 }?.joinToString(" "),
            single = a == b && wordTimed,
            joinedText = joinedText?.takeIf { it != text },
            joinedContext = if (joinedText != null && joinedText != text) join(contextParts) else null,
        )
    }

    /** Marks that end a sentence (Latin, Japanese/Chinese, Armenian, Arabic/Urdu, Devanagari). */
    private const val ENDS = ".?!…;。？！；։՞՜؟۔।"
    /** Marks that open a sentence (Spanish). */
    private const val OPENS = "¿¡"

    /**
     * Joins the pieces of a selection taken from consecutive transcript sentences, removing the
     * sentence-ending punctuation between them (and a Spanish ¿ ¡ opening the next piece).
     * Punctuation inside a piece, at the very end, and capital letters are kept.
     */
    fun join(pieces: List<String>): String {
        val trimmed = pieces.map { it.trim() }.filter { it.isNotEmpty() }
        val sb = StringBuilder()
        trimmed.forEachIndexed { i, raw ->
            var piece = raw
            if (i > 0) piece = piece.trimStart { it in OPENS || it.isWhitespace() }
            if (i < trimmed.lastIndex) piece = piece.trimEnd { it in ENDS || it.isWhitespace() }
            if (piece.isEmpty()) return@forEachIndexed
            if (sb.isNotEmpty() && !(isCjk(sb.last()) && isCjk(piece.first()))) sb.append(' ')
            sb.append(piece)
        }
        return sb.toString()
    }

    private fun isCjk(c: Char): Boolean {
        val block = Character.UnicodeBlock.of(c)
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS || block == Character.UnicodeBlock.HIRAGANA ||
            block == Character.UnicodeBlock.KATAKANA || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
    }
}
