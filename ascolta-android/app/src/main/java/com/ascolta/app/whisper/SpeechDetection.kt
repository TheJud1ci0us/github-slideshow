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

    suspend fun detect(vadModel: String, samples: FloatArray): List<Pair<Long, Long>> = coroutineScope {
        val totalMs = samples.size / 16L
        val parts = partition(totalMs, CoreDispatchers.speechDetectionThreads)
        val found = parts.map { (startMs, endMs) ->
            async(CoreDispatchers.speechDetection) {
                val from = (startMs * 16).toInt()
                val to = minOf(samples.size, (endMs * 16).toInt())
                val regions = WhisperLib.nativeDetectSpeech(vadModel, samples.copyOfRange(from, to), 1)
                    ?.let { SpeechAlignment.pairs(it) }.orEmpty()
                regions.map { (s, e) -> (s + startMs) to (e + startMs) }
            }
        }.awaitAll()
        merge(found.flatten())
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

    /** Union of possibly overlapping regions; regions less than 100 ms apart are joined. */
    fun merge(regions: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
        val out = mutableListOf<Pair<Long, Long>>()
        for ((s, e) in regions.sortedBy { it.first }) {
            val last = out.lastOrNull()
            if (last != null && s <= last.second + 100) out[out.lastIndex] = last.first to maxOf(last.second, e)
            else out += s to e
        }
        return out
    }
}
