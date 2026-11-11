package com.ascolta.app.whisper

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Finds the speech in a block using all efficiency cores: the block is cut into slightly
 * overlapping parts, each part is analysed on its own core at the same time, and the results
 * are merged. Silero's model is sequential, so one part per core is what makes it faster.
 */
object SpeechDetection {
    private const val OVERLAP_MS = 1_000L

    suspend fun detect(vadModel: String, samples: FloatArray, fine: Boolean = false): List<Pair<Long, Long>> = coroutineScope {
        val totalMs = samples.size / 16L
        val parts = partition(totalMs, CoreDispatchers.speechDetectionThreads)
        val found = parts.map { (startMs, endMs) ->
            async(CoreDispatchers.speechDetection) {
                val from = (startMs * 16).toInt()
                val to = minOf(samples.size, (endMs * 16).toInt())
                val regions = WhisperLib.nativeDetectSpeech(vadModel, samples.copyOfRange(from, to), 1, fine)
                    ?.let { SpeechAlignment.pairs(it) }.orEmpty()
                regions.map { (s, e) -> (s + startMs) to (e + startMs) }
            }
        }.awaitAll()
        merge(found.flatten(), if (fine) 0 else 100)
    }

    /** [n] parts covering 0..[totalMs], each extended by [OVERLAP_MS] into the next. */
    fun partition(totalMs: Long, n: Int): List<Pair<Long, Long>> {
        val count = if (totalMs < 10_000) 1 else n.coerceAtLeast(1)
        val step = totalMs / count
        return (0 until count).map { i ->
            val s = i * step
            val e = if (i == count - 1) totalMs else minOf(totalMs, (i + 1) * step + OVERLAP_MS)
            s to e
        }
    }

    /**
     * The detector can miss speech over background music (jingles, reportages) for a minute or
     * more. Any stretch of at least [minGapMs] without detected speech in 0..[totalMs] is added
     * back, so the speech model checks it too: on real music or silence it writes nothing.
     */
    fun fillGaps(regions: List<Pair<Long, Long>>, totalMs: Long, minGapMs: Long = 6_000): List<Pair<Long, Long>> {
        val sorted = merge(regions)
        val out = mutableListOf<Pair<Long, Long>>()
        var last = 0L
        for ((s, e) in sorted) {
            if (s - last >= minGapMs) out += last to s
            out += s to e
            last = maxOf(last, e)
        }
        if (totalMs - last >= minGapMs) out += last to totalMs
        return out
    }

    /**
     * Joins neighbouring stretches of speech (pauses of up to [maxPauseMs] kept inside) into
     * pieces of at most [targetMs], so the model hears each phrase with the sentences around
     * it. Recognised alone, a 2-second reply ("Tu sais ?") gives Parakeet too little to tell
     * French from English. Longer silences (music, a jingle) still separate pieces.
     */
    fun groupForContext(regions: List<Pair<Long, Long>>, maxPauseMs: Long = 2_000, targetMs: Long = 20_000): List<Pair<Long, Long>> {
        val out = mutableListOf<Pair<Long, Long>>()
        for ((s, e) in merge(regions)) {
            val last = out.lastOrNull()
            if (last != null && s - last.second <= maxPauseMs && e - last.first <= targetMs) out[out.lastIndex] = last.first to e
            else out += s to e
        }
        return out
    }

    /** Seconds added by [fillGaps] (for the diagnostics log). */
    fun gapMs(regions: List<Pair<Long, Long>>, filled: List<Pair<Long, Long>>): Long =
        filled.sumOf { it.second - it.first } - merge(regions).sumOf { it.second - it.first }

    /** Union of possibly overlapping regions; regions less than 100 ms apart are joined. */
    fun merge(regions: List<Pair<Long, Long>>, joinBelowMs: Long = 100): List<Pair<Long, Long>> {
        val out = mutableListOf<Pair<Long, Long>>()
        for ((s, e) in regions.sortedBy { it.first }) {
            val last = out.lastOrNull()
            if (last != null && s <= last.second + joinBelowMs) out[out.lastIndex] = last.first to maxOf(last.second, e)
            else out += s to e
        }
        return out
    }
}
