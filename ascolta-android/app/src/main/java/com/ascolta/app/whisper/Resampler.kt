package com.ascolta.app.whisper

/**
 * Streaming linear-interpolation resampler for mono float audio.
 * A simple box filter runs first when downsampling, to limit aliasing.
 */
class LinearResampler(inRate: Int, outRate: Int) {
    private val step = inRate.toDouble() / outRate
    private val boxWidth = if (step >= 2.0) step.toInt() else 1
    private val history = FloatArray(boxWidth)
    private var historyPos = 0
    private var historySum = 0f
    private var filtered = FloatArray(0)
    private var output = FloatArray(0)

    /** Position of the next output sample, in input-sample units relative to the current block. */
    private var pos = 0.0
    private var prev = 0f

    /**
     * Resamples [count] samples of [input]. Returns a buffer (reused between calls)
     * and the number of valid samples in it.
     */
    fun process(input: FloatArray, count: Int): Pair<FloatArray, Int> {
        if (count == 0) return output to 0
        val src = if (boxWidth > 1) lowPass(input, count) else input
        val maxOut = (count / step).toInt() + 2
        if (output.size < maxOut) output = FloatArray(maxOut)
        var n = 0
        while (pos <= count - 1) {
            val i = kotlin.math.floor(pos).toInt()
            val frac = (pos - i).toFloat()
            val s0 = if (i < 0) prev else src[i]
            val s1 = if (i + 1 < count) src[i + 1] else s0
            output[n++] = s0 + (s1 - s0) * frac
            pos += step
        }
        pos -= count
        prev = src[count - 1]
        return output to n
    }

    private fun lowPass(input: FloatArray, count: Int): FloatArray {
        if (filtered.size < count) filtered = FloatArray(count)
        for (i in 0 until count) {
            historySum += input[i] - history[historyPos]
            history[historyPos] = input[i]
            historyPos = (historyPos + 1) % boxWidth
            filtered[i] = historySum / boxWidth
        }
        return filtered
    }
}

/**
 * Collects resampled audio into chunks, skipping the first [skipSamples].
 * The first chunk can be shorter ([firstChunkSamples]) so results appear quickly.
 */
class ChunkAccumulator(
    private val chunkSamples: Int,
    private val skipSamples: Long,
    firstChunkSamples: Int = chunkSamples,
) {
    private var buffer = FloatArray(firstChunkSamples)
    private var filled = 0
    private var produced = 0L
    /** Index (in output samples) of the first sample in the current buffer. */
    private var bufferStart = skipSamples
    private val ready = ArrayDeque<Pair<FloatArray, Long>>()

    /** Total samples seen so far, including skipped ones. */
    val samplesSeen: Long get() = produced

    fun add(samples: FloatArray, count: Int) {
        var i = 0
        if (produced < skipSamples) {
            val skip = minOf(skipSamples - produced, count.toLong()).toInt()
            produced += skip
            i = skip
        }
        while (i < count) {
            val n = minOf(count - i, buffer.size - filled)
            System.arraycopy(samples, i, buffer, filled, n)
            filled += n
            i += n
            produced += n
            if (filled == buffer.size) {
                ready.addLast(buffer to bufferStart)
                bufferStart += buffer.size
                buffer = FloatArray(chunkSamples)
                filled = 0
            }
        }
    }

    fun add(sample: Float) = add(floatArrayOf(sample), 1)

    fun poll(): Pair<FloatArray, Long>? = ready.removeFirstOrNull()

    /** Returns the final partial chunk, if any. */
    fun flush(): Pair<FloatArray, Long>? {
        if (filled == 0) return null
        val out = buffer.copyOf(filled) to bufferStart
        filled = 0
        return out
    }
}
