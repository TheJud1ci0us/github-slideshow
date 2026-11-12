package com.ascolta.app.work

import com.ascolta.app.data.Segment
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
     * Where "next sentence" goes: the next transcribed sentence after [positionMs] (however far,
     * so an ad is crossed in one tap), or 15 s on when there is none yet. Never backwards.
     */
    fun nextTarget(sentenceStarts: List<Long>, positionMs: Long, durationMs: Long): Long {
        val next = sentenceStarts.firstOrNull { it > positionMs + 300 } ?: (positionMs + 15_000)
        return if (durationMs > 0) next.coerceAtMost(durationMs - 1_000).coerceAtLeast(positionMs) else next
    }

    /**
     * A block where a quarter or more of the sentences (at least 2) came out in the wrong
     * language (a fast conversation, short replies) is redone whole rather than patched.
     */
    fun redoWholeBlock(wrong: Int, total: Int): Boolean = wrong >= 2 && wrong * 4 >= total

    /**
     * Gives each of [targets] (start, end) the text of the [parts] (start, end, text) whose
     * middle falls inside it (with 250 ms of margin); the nearest target when none contains it.
     * Used to put Whisper's redone sentences back on the right wrong-language sentence.
     */
    fun assignByTime(targets: List<Pair<Long, Long>>, parts: List<Triple<Long, Long, String>>): List<String> {
        val texts = Array(targets.size) { StringBuilder() }
        if (targets.isEmpty()) return emptyList()
        for ((s, e, text) in parts.sortedBy { it.first }) {
            if (text.isBlank()) continue
            val mid = (s + e) / 2
            val i = targets.indices.firstOrNull { mid in (targets[it].first - 250)..(targets[it].second + 250) }
                ?: targets.indices.minBy { minOf(kotlin.math.abs(targets[it].first - mid), kotlin.math.abs(targets[it].second - mid)) }
            if (texts[i].isNotEmpty()) texts[i].append(' ')
            texts[i].append(text.trim())
        }
        return texts.map { it.toString() }
    }

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
 * The buffer zone: transcription only covers the stretch from where the listener is in the
 * timeline (playing or not) up to the "Transcribe ahead" time ahead of it (20% of the
 * episode by default here). Once that is
 * transcribed it waits; it continues when the listener has used up part of it (at least one
 * block, or a quarter of the zone for short episodes), or jumps somewhere not yet done.
 */
object BufferPolicy {
    const val SHARE = 0.20f
    private const val MIN_ZONE_MS = 30_000L
    /** Shortest zone with a slow model (near real time), so playback can't catch up with it. */
    const val SLOW_MIN_MS = 5 * 60_000L

    /** Length of the zone ahead of the listener: 20% of the episode (at least 30 s). */
    fun zoneMs(totalMs: Long): Long = maxOf(MIN_ZONE_MS, (totalMs * SHARE).toLong()).coerceAtMost(totalMs)

    /**
     * The zone for the "Transcribe ahead" setting: [minutes] ahead of the listener (0: the
     * whole episode), at least five minutes with a [slow] model, never past the end.
     */
    fun zoneFor(minutes: Int, totalMs: Long, slow: Boolean): Long {
        val wanted = if (minutes <= 0) totalMs else minutes * 60_000L
        return maxOf(wanted, if (slow) SLOW_MIN_MS else MIN_ZONE_MS).coerceAtMost(totalMs)
    }

    /** End of the zone for a listener at [positionMs]. */
    fun zoneEnd(positionMs: Long, totalMs: Long, zone: Long = zoneMs(totalMs)): Long = minOf(totalMs, positionMs + zone)

    /** After a full zone, work starts again once less than this is transcribed ahead. */
    fun resumeBelowMs(totalMs: Long, blockMs: Long, zone: Long = zoneMs(totalMs)): Long = zone - minOf(blockMs, zone / 4)

    /**
     * The next stretch to transcribe for a listener at [positionMs], or null when the zone
     * ahead of them is fully transcribed (nothing beyond the zone, nothing behind them).
     */
    fun nextBlock(spans: List<Span>, positionMs: Long, totalMs: Long, blockMs: Long, zone: Long = zoneMs(totalMs)): Span? {
        val end = zoneEnd(positionMs, totalMs, zone)
        val gap = Coverage.firstGap(spans, positionMs, totalMs) ?: return null
        if (gap.startMs >= end - 500) return null
        return Span(gap.startMs, minOf(gap.endMs, gap.startMs + blockMs, end))
    }

    /** Transcribed time right ahead of a listener at [positionMs]. */
    fun aheadMs(spans: List<Span>, positionMs: Long, totalMs: Long): Long =
        (Coverage.firstGap(spans, positionMs, totalMs)?.startMs ?: totalMs) - positionMs
}

/** Where to end a block so it doesn't cut a word in two (which made words appear twice). */
object BlockCut {
    /**
     * With speech regions (ms within a block of [lengthMs]): the middle of the last pause in
     * the final [searchMs]. Null when the block already ends in silence or has no pause there.
     */
    fun atPause(speech: List<Pair<Long, Long>>, lengthMs: Long, searchMs: Long = 15_000, minGapMs: Long = 120): Long? {
        val regions = speech.sortedBy { it.first }
        val last = regions.lastOrNull() ?: return null
        if (last.second < lengthMs - 100) return null // no speech running into the end
        for (i in regions.indices.reversed()) {
            if (i == 0) break
            val gapStart = regions[i - 1].second
            val gapEnd = regions[i].first
            val mid = (gapStart + gapEnd) / 2
            if (mid < lengthMs - searchMs) break
            if (gapEnd - gapStart >= minGapMs) return mid
        }
        return null
    }

    /** Without speech regions: the quietest 50 ms in the final [searchMs] of the block. */
    fun quietest(samples: FloatArray, searchMs: Long = 10_000): Long? {
        val frame = 800 // 50 ms
        val from = (samples.size - (searchMs * 16).toInt()).coerceAtLeast(0)
        var best = -1
        var bestEnergy = Double.MAX_VALUE
        var i = from
        while (i + frame <= samples.size - frame) {
            var e = 0.0
            for (j in i until i + frame) e += samples[j] * samples[j]
            if (e <= bestEnergy) { bestEnergy = e; best = i }
            i += frame
        }
        return if (best < 0) null else (best + frame / 2) / 16L
    }
}

/**
 * Adds recognised sentences to a transcript without repeating speech: the same stretch
 * recognised twice (e.g. at block boundaries) is kept once, and words at the start of a
 * sentence that repeat the end of the one before it (a word cut in two) are dropped.
 */
object SentenceMerge {
    sealed interface Result {
        data object Drop : Result
        data class Add(val segment: Segment) : Result
        data class Replace(val index: Int, val segment: Segment) : Result
    }

    fun resolve(segments: List<Segment>, new: Segment): Result {
        var seg = new
        val newWords = words(seg.text)
        if (newWords.isEmpty()) return Result.Drop
        // Same speech twice: overlapping in time with similar text.
        segments.forEachIndexed { i, e ->
            val overlap = minOf(e.endMs, seg.endMs) - maxOf(e.startMs, seg.startMs)
            val shorter = minOf(e.endMs - e.startMs, seg.endMs - seg.startMs).coerceAtLeast(1)
            if (overlap * 2 > shorter && similar(e.text, seg.text)) {
                return if (newWords.size > words(e.text).size) Result.Replace(i, seg) else Result.Drop
            }
        }
        val prev = segments.lastOrNull { it.startMs < seg.startMs }
        if (prev != null && seg.startMs - prev.endMs < 5_000 && norm(prev.text) == norm(seg.text) && newWords.size >= 3) {
            return Result.Drop // a sentence repeated right after itself (recogniser loop)
        }
        if (prev != null && seg.startMs - prev.endMs < 2_000) {
            val k = overlapWords(words(prev.text), newWords)
            if (k > 0) seg = dropFirstWords(seg, k) ?: return Result.Drop
        }
        val next = segments.firstOrNull { it.startMs > seg.startMs }
        if (next != null && next.startMs - seg.endMs < 2_000) {
            val k = overlapWords(words(seg.text), words(next.text))
            if (k > 0) seg = dropLastWords(seg, k) ?: return Result.Drop
        }
        return Result.Add(seg)
    }

    fun apply(segments: List<Segment>, result: Result): List<Segment> = when (result) {
        Result.Drop -> segments
        is Result.Add -> (segments + result.segment).sortedBy { it.startMs }
        is Result.Replace -> segments.toMutableList().also { it[result.index] = result.segment }.sortedBy { it.startMs }
    }

    private fun words(text: String) = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    private fun key(word: String) = word.lowercase().trim { !it.isLetterOrDigit() }
    private fun norm(text: String) = words(text).joinToString(" ") { key(it) }

    private fun similar(a: String, b: String): Boolean {
        val na = norm(a)
        val nb = norm(b)
        if (na == nb) return true
        if (na.length >= 8 && nb.length >= 8 && (na.contains(nb) || nb.contains(na))) return true
        val sa = na.split(" ").toSet()
        val sb = nb.split(" ").toSet()
        val common = sa.intersect(sb).size
        return common.toFloat() / (sa.union(sb).size.coerceAtLeast(1)) >= 0.6f
    }

    /** Largest k (≤ 4) such that the last k words of [before] equal the first k of [after]. */
    fun overlapWords(before: List<String>, after: List<String>): Int {
        for (k in minOf(4, before.size, after.size) downTo 1) {
            val tail = before.takeLast(k).map(::key)
            val head = after.take(k).map(::key)
            if (tail == head && tail.all { it.isNotEmpty() }) {
                // One repeated short word is often legitimate ("… e. E poi …").
                if (k == 1 && tail[0].length < 4) return 0
                return k
            }
        }
        return 0
    }

    private fun dropFirstWords(seg: Segment, k: Int): Segment? {
        val w = words(seg.text)
        if (w.size <= k) return null
        val timed = seg.words?.takeIf { it.size == w.size }?.drop(k)
        return seg.copy(
            startMs = timed?.firstOrNull()?.startMs ?: seg.startMs,
            text = w.drop(k).joinToString(" "),
            words = timed ?: seg.words?.let { null },
        )
    }

    private fun dropLastWords(seg: Segment, k: Int): Segment? {
        val w = words(seg.text)
        if (w.size <= k) return null
        val timed = seg.words?.takeIf { it.size == w.size }
        return seg.copy(
            endMs = timed?.get(w.size - k)?.startMs ?: seg.endMs,
            text = w.dropLast(k).joinToString(" "),
            words = timed?.dropLast(k),
        )
    }
}
