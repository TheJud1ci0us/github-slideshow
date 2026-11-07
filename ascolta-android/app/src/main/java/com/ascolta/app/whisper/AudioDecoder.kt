package com.ascolta.app.whisper

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteOrder

/**
 * Decodes any audio file Android can play (MP3, AAC/M4A, Opus, …) into
 * 16 kHz mono float PCM, which is what whisper expects.
 */
class AudioDecoder(private val path: String) {

    fun durationMs(): Long {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(path)
            val track = findAudioTrack(extractor) ?: return 0
            val format = extractor.getTrackFormat(track)
            if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else 0
        } finally {
            extractor.release()
        }
    }

    /**
     * Streams the audio as chunks of [chunkSamples] samples. The first [skipSamples]
     * are skipped (used to resume). [onChunk] receives the samples and the index of the
     * chunk's first sample; returning false stops decoding.
     */
    suspend fun decode(
        chunkSamples: Int,
        skipSamples: Long,
        firstChunkSamples: Int = chunkSamples,
        onDecoded: (Long) -> Unit = {},
        onChunk: suspend (FloatArray, Long) -> Boolean,
    ) = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(path)
            val track = findAudioTrack(extractor) ?: throw IOException("No audio track found")
            extractor.selectTrack(track)
            val inputFormat = extractor.getTrackFormat(track)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(inputFormat, null, null, 0)
                start()
            }

            var channels = inputFormat.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 1
            var sampleRate = inputFormat.getIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: 44100
            var floatPcm = false
            var resampler = LinearResampler(sampleRate, SAMPLE_RATE)
            val accumulator = ChunkAccumulator(chunkSamples, skipSamples, firstChunkSamples)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var mono = FloatArray(0)

            while (!outputDone) {
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
                        channels = format.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: channels
                        sampleRate = format.getIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: sampleRate
                        floatPcm = format.getIntegerOrNull(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        resampler = LinearResampler(sampleRate, SAMPLE_RATE)
                    }
                    outIndex >= 0 -> {
                        if (info.size > 0) {
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
                            accumulator.add(out, n)
                            onDecoded(accumulator.samplesSeen)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true

                        while (true) {
                            val (chunk, start) = accumulator.poll() ?: break
                            if (!onChunk(chunk, start)) return@withContext
                        }
                    }
                }
            }
            accumulator.flush()?.let { (chunk, start) ->
                // Ignore a trailing fragment shorter than half a second.
                if (chunk.size > SAMPLE_RATE / 2) onChunk(chunk, start)
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int? =
        (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }

    private fun MediaFormat.getIntegerOrNull(key: String): Int? =
        if (containsKey(key)) getInteger(key) else null

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val TIMEOUT_US = 10_000L
    }
}
