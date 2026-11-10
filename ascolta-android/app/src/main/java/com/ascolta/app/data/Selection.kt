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
)

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
        val context = (a.segment..b.segment).joinToString(" ") { segments[it].text.trim() }
        val translations = (a.segment..b.segment).mapNotNull { segments[it].translation?.trim() }
        return Selected(
            text = text,
            startMs = startMs,
            endMs = endMs,
            context = context,
            contextTranslation = translations.takeIf { it.size == b.segment - a.segment + 1 }?.joinToString(" "),
            single = a == b && wordTimed,
        )
    }
}
