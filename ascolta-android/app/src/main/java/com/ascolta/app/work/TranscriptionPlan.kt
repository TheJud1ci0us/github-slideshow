package com.ascolta.app.work

import com.ascolta.app.data.Span

/**
 * Which parts of an episode are transcribed, and what to transcribe next. Pure logic
 * (unit tested), used by [TranscribeWorker].
 *
 * The transcription follows playback like a video buffer: it works just ahead of where the
 * listener is, jumps when they jump (keeping everything already done), stops once it is far
 * enough ahead, and when nobody is listening it fills in the rest at full speed.
 */
object Coverage {
    /** Adds [span] and merges overlapping or touching spans (≤ 50 ms apart). */
    fun add(spans: List<Span>, span: Span): List<Span> {
        if (span.endMs <= span.startMs) return spans
        val all = (spans + span).sortedBy { it.startMs }
        val out = mutableListOf<Span>()
        for (s in all) {
            val last = out.lastOrNull()
            if (last != null && s.startMs <= last.endMs + 50) out[out.lastIndex] = Span(last.startMs, maxOf(last.endMs, s.endMs))
            else out += s
        }
        return out
    }

    fun totalMs(spans: List<Span>): Long = spans.sumOf { it.endMs - it.startMs }

    /** End of the covered stretch containing [positionMs], or [positionMs] if it isn't covered. */
    fun runEnd(spans: List<Span>, positionMs: Long): Long =
        spans.firstOrNull { positionMs >= it.startMs - 50 && positionMs < it.endMs }?.endMs ?: positionMs

    fun isCovered(spans: List<Span>, positionMs: Long): Boolean = spans.any { positionMs >= it.startMs && positionMs < it.endMs }

    /** First uncovered stretch starting at or after [fromMs] (gaps shorter than [minGapMs] ignored). */
    fun firstGap(spans: List<Span>, fromMs: Long, durationMs: Long, minGapMs: Long = 500): Span? {
        var cursor = runEnd(spans, fromMs.coerceIn(0, durationMs))
        for (s in spans.sortedBy { it.startMs }) {
            if (s.endMs <= cursor) continue
            if (s.startMs > cursor) {
                if (s.startMs - cursor >= minGapMs) return Span(cursor, s.startMs)
                cursor = s.endMs
            } else {
                cursor = maxOf(cursor, s.endMs)
            }
        }
        return if (durationMs - cursor >= minGapMs) Span(cursor, durationMs) else null
    }

    fun isComplete(spans: List<Span>, durationMs: Long, minGapMs: Long = 500): Boolean = firstGap(spans, 0, durationMs, minGapMs) == null
}

object TranscriptionPlan {
    /**
     * Next block to transcribe: the first gap at or after [anchorMs] (where the listener is,
     * or where the work was heading), otherwise the first gap from the beginning.
     */
    fun nextBlock(spans: List<Span>, anchorMs: Long, durationMs: Long, blockMs: Long): Span? {
        val gap = Coverage.firstGap(spans, anchorMs, durationMs) ?: Coverage.firstGap(spans, 0, durationMs) ?: return null
        return Span(gap.startMs, minOf(gap.endMs, gap.startMs + blockMs))
    }

    /**
     * While listening: should the block being transcribed be abandoned because the listener
     * jumped somewhere it doesn't help? True when the first untranscribed point after their
     * position is outside this block (allowing a little slack).
     */
    fun shouldAbandon(spans: List<Span>, positionMs: Long, block: Span, slackMs: Long = 3_000): Boolean {
        val needed = Coverage.runEnd(spans, positionMs)
        return needed < block.startMs - slackMs || needed > block.endMs + slackMs
    }
}

/**
 * How far ahead of playback the transcription should stay. Measured speed [speedFactor]
 * (audio seconds per second of work) and [playbackSpeed] give how much audio plays while one
 * block is being transcribed; the buffer is kept comfortably above that. Returns null when
 * transcription isn't clearly faster than playback (then it never pauses).
 */
object BufferPolicy {
    data class Limits(val resumeBelowMs: Long, val pauseAboveMs: Long)

    fun limits(speedFactor: Float?, playbackSpeed: Float, blockMs: Long): Limits? {
        if (speedFactor == null || speedFactor < playbackSpeed * 1.2f) return null
        val consumedPerBlock = (playbackSpeed * blockMs / speedFactor).toLong()
        val low = maxOf(45_000L, 2 * consumedPerBlock + 15_000L)
        val high = low + maxOf(2 * blockMs, 120_000L)
        return Limits(low, high)
    }
}
