package com.ascolta.app.whisper

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.Closeable
import java.io.IOException
import java.nio.ByteOrder

/**
 * Reads arbitrary stretches of an audio file as 16 kHz mono PCM, for transcribing the part
 * of an episode the listener jumped to.
 *
 * Timestamps must match the player exactly, so it never relies on the container's seek
 * (in variable-bit-rate MP3s that is an estimate, often seconds off). Instead it walks the
 * compressed frames without decoding them (fast), whose times are exact when read in order,
 * then decodes from a short pre-roll before the target. Consecutive reads continue where the
 * previous one ended, without re-scanning.
 */
class BlockReader(private val path: String) : Closeable {
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val info = MediaCodec.BufferInfo()
    private var channels: Int
    private var sampleRate: Int
    private var floatPcm = false
    private var resampler: LinearResampler
    private var inputDone = false
    private var outputDone = false

    /** Decoded, resampled samples not yet handed out, starting at sample [pendingStart] (16 kHz). */
    private var pending = FloatArray(16_000 * 30)
    private var pendingSize = 0
    private var pendingStart = -1L
    private var mono = FloatArray(0)

    val durationMs: Long

    init {
        extractor.setDataSource(path)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw IOException("No audio track found")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else 0
        channels = format.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 1
        sampleRate = format.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: 44_100
        resampler = LinearResampler(sampleRate, AudioDecoder.SAMPLE_RATE)
        codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).apply {
            configure(format, null, null, 0)
            start()
        }
    }

    /**
     * Returns up to [lengthMs] of audio starting at [startMs] (shorter at the end of the file),
     * with the index of its first sample. [isCancelled] is checked while decoding.
     */
    fun read(startMs: Long, lengthMs: Long, isCancelled: () -> Boolean = { false }): Pair<FloatArray, Long> {
        val start = startMs * 16
        val wanted = (lengthMs * 16).toInt()
        val pendingEnd = pendingStart + pendingSize
        // Reposition unless the start is within (or just after) what has been decoded.
        if (pendingStart < 0 || start < pendingStart || start > pendingEnd + 16_000L * 5) reposition(startMs)
        while (!outputDone && !isCancelled() && (pendingStart < 0 || pendingStart + pendingSize < start + wanted)) decodeSome()
        if (pendingStart < 0) return FloatArray(0) to start
        val from = (start - pendingStart).coerceIn(0, pendingSize.toLong()).toInt()
        val to = minOf(pendingSize, from + wanted)
        val out = pending.copyOfRange(from, to)
        val firstSample = pendingStart + from
        // Keep only what comes after this block.
        System.arraycopy(pending, to, pending, 0, pendingSize - to)
        pendingSize -= to
        pendingStart += to
        return out to firstSample
    }

    private fun reposition(targetMs: Long) {
        val targetUs = targetMs * 1000
        val prerollUs = 200_000L
        if (extractor.sampleTime < 0 || extractor.sampleTime > targetUs - prerollUs) {
            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        }
        // Walk compressed frames up to just before the target (no decoding: fast and exact).
        while (extractor.sampleTime in 0 until targetUs - prerollUs) {
            if (!extractor.advance()) break
        }
        codec.flush()
        inputDone = false
        outputDone = false
        resampler = LinearResampler(sampleRate, AudioDecoder.SAMPLE_RATE)
        pendingSize = 0
        pendingStart = -1
    }

    private fun decodeSome() {
        if (!inputDone) {
            val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inIndex >= 0) {
                val buffer = codec.getInputBuffer(inIndex)!!
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }
        }
        val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
        when {
            outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                val format = codec.outputFormat
                channels = format.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: channels
                val rate = format.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: sampleRate
                if (rate != sampleRate) {
                    sampleRate = rate
                    resampler = LinearResampler(sampleRate, AudioDecoder.SAMPLE_RATE)
                }
                floatPcm = format.intOrNull(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
            }
            outIndex >= 0 -> {
                if (info.size > 0) {
                    // The first output after a reposition fixes where the decoded audio sits in time.
                    if (pendingStart < 0) pendingStart = info.presentationTimeUs * 16 / 1000
                    val buffer = codec.getOutputBuffer(outIndex)!!
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    buffer.order(ByteOrder.LITTLE_ENDIAN)
                    val frames: Int
                    if (floatPcm) {
                        val fb = buffer.asFloatBuffer()
                        frames = fb.remaining() / channels
                        if (mono.size < frames) mono = FloatArray(frames)
                        for (f in 0 until frames) {
                            var sum = 0f
                            for (c in 0 until channels) sum += fb.get(f * channels + c)
                            mono[f] = sum / channels
                        }
                    } else {
                        val sb = buffer.asShortBuffer()
                        frames = sb.remaining() / channels
                        if (mono.size < frames) mono = FloatArray(frames)
                        for (f in 0 until frames) {
                            var sum = 0f
                            for (c in 0 until channels) sum += sb.get(f * channels + c)
                            mono[f] = sum / channels / 32768f
                        }
                    }
                    val (out, n) = resampler.process(mono, frames)
                    if (pendingSize + n > pending.size) pending = pending.copyOf(maxOf(pending.size * 2, pendingSize + n))
                    System.arraycopy(out, 0, pending, pendingSize, n)
                    pendingSize += n
                }
                codec.releaseOutputBuffer(outIndex, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
            }
        }
    }

    override fun close() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        extractor.release()
    }

    private fun MediaFormat.intOrNull(key: String): Int? = if (containsKey(key)) getInteger(key) else null

    private companion object {
        const val TIMEOUT_US = 10_000L
    }
}
