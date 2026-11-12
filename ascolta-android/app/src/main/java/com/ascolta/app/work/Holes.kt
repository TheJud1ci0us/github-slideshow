package com.ascolta.app.work

import com.ascolta.app.data.Segment

/**
 * Stretches a speech model skipped: speech was detected there, but no word was recognised
 * (pure, unit-tested). Parakeet sometimes goes silent for a few seconds in the middle of a
 * sentence; those stretches are then heard again on their own.
 */
object Holes {
    data class Hole(val startMs: Long, val endMs: Long, val speechMs: Long)

    /** How long a word is taken to last when only its start is known. */
    private const val WORD_MS = 700L

    /**
     * The holes of a block [blockStartMs]..[blockEndMs] (episode times): stretches of at least
     * [minHoleMs] with no recognised word, of which at least [minSpeechMs] is detected [speech]
     * (episode times too). The [max] with the most speech, in time order.
     */
    fun find(
        speech: List<Pair<Long, Long>>,
        segments: List<Segment>,
        blockStartMs: Long,
        blockEndMs: Long,
        minHoleMs: Long = 2_000,
        minSpeechMs: Long = 1_500,
        max: Int = 3,
    ): List<Hole> {
        // Where words were heard.
        val heard = mutableListOf<Pair<Long, Long>>()
        for (s in segments) {
            val words = s.words?.sortedBy { it.startMs }
            if (words.isNullOrEmpty()) { heard += s.startMs to s.endMs; continue }
            heard += s.startMs to minOf(s.endMs, words.first().startMs + WORD_MS)
            words.forEachIndexed { i, w ->
                val end = if (i + 1 < words.size) minOf(words[i + 1].startMs, w.startMs + WORD_MS) else maxOf(s.endMs, w.startMs)
                heard += w.startMs to maxOf(end, w.startMs)
            }
        }
        val merged = merge(heard)
        // The gaps between them inside the block.
        val gaps = mutableListOf<Pair<Long, Long>>()
        var at = blockStartMs
        for ((a, b) in merged) {
            if (b <= blockStartMs || a >= blockEndMs) continue
            if (a > at) gaps += at to a
            at = maxOf(at, b)
        }
        if (at < blockEndMs) gaps += at to blockEndMs
        return gaps.filter { it.second - it.first >= minHoleMs }
            .map { (a, b) -> Hole(a, b, speech.sumOf { (s, e) -> (minOf(b, e) - maxOf(a, s)).coerceAtLeast(0) }) }
            .filter { it.speechMs >= minSpeechMs }
            .sortedByDescending { it.speechMs }.take(max)
            .sortedBy { it.startMs }
    }

    private fun merge(spans: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
        val out = mutableListOf<Pair<Long, Long>>()
        for ((a, b) in spans.sortedBy { it.first }) {
            val last = out.lastOrNull()
            if (last != null && a <= last.second + 300) out[out.size - 1] = last.first to maxOf(last.second, b) else out += a to b
        }
        return out
    }
}
