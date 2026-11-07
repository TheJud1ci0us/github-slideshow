package com.ascolta.app.whisper

/**
 * Streaming linear-interpolation resampler for mono float audio.
 * Feed blocks with [process]; output samples are passed to [sink].
 * A simple box filter runs first when downsampling, to limit aliasing.
 */
class LinearResampler(inRate: Int, outRate: Int) {
    private val step = inRate.toDouble() / outRate
    private val boxWidth = if (step >= 2.0) step.toInt() else 1
    private val history = FloatArray(boxWidth)
    private var historyPos = 0
    private var historySum = 0f

    /** Position of the next output sample, in input-sample units relative to the current block. */
    private var pos = 0.0
    private var prev = 0f

    fun process(input: FloatArray, count: Int, sink: (Float) -> Unit) {
        if (count == 0) return
        val filtered = if (boxWidth > 1) lowPass(input, count) else input
        while (pos <= count - 1) {
            val i = kotlin.math.floor(pos).toInt()
            val frac = (pos - i).toFloat()
            val s0 = if (i < 0) prev else filtered[i]
            val s1 = if (i + 1 < count) filtered[i + 1] else s0
            sink(s0 + (s1 - s0) * frac)
            pos += step
        }
        pos -= count
        prev = filtered[count - 1]
    }

    private fun lowPass(input: FloatArray, count: Int): FloatArray {
        val out = FloatArray(count)
        for (i in 0 until count) {
            historySum += input[i] - history[historyPos]
            history[historyPos] = input[i]
            historyPos = (historyPos + 1) % boxWidth
            out[i] = historySum / boxWidth
        }
        return out
    }
}

/** Collects resampled audio into fixed-size chunks, skipping the first [skipSamples]. */
class ChunkAccumulator(private val chunkSamples: Int, private val skipSamples: Long) {
    private var buffer = FloatArray(chunkSamples)
    private var filled = 0
    private var produced = 0L
    /** Index (in output samples) of the first sample in the current buffer. */
    private var bufferStart = skipSamples
    private val ready = ArrayDeque<Pair<FloatArray, Long>>()

    fun add(sample: Float) {
        produced++
        if (produced <= skipSamples) return
        buffer[filled++] = sample
        if (filled == chunkSamples) {
            ready.addLast(buffer to bufferStart)
            bufferStart += chunkSamples
            buffer = FloatArray(chunkSamples)
            filled = 0
        }
    }

    fun poll(): Pair<FloatArray, Long>? = ready.removeFirstOrNull()

    /** Returns the final partial chunk, if any. */
    fun flush(): Pair<FloatArray, Long>? {
        if (filled == 0) return null
        val out = buffer.copyOf(filled) to bufferStart
        filled = 0
        return out
    }
}
