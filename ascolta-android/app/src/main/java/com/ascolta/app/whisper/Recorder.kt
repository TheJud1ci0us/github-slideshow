package com.ascolta.app.whisper

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * Records the learner (shadowing) as a 16 kHz mono WAV file: the same format as the saved
 * clips, so both can be drawn as waveforms and the recording can go straight to the speech
 * model for "Check what I said". Recordings stay on the phone.
 */
class Recorder {
    @Volatile private var running = false
    private var worker: Thread? = null

    val isRecording get() = running

    /** Starts recording into [out]; stops by itself after [maxMs]. Needs the microphone permission. */
    @SuppressLint("MissingPermission")
    fun start(out: File, maxMs: Long) {
        stop()
        out.parentFile?.mkdirs()
        val rate = SAMPLE_RATE
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate))
        running = true
        worker = thread(name = "shadow-recorder") {
            val buffer = ShortArray(rate / 10)
            RandomAccessFile(out, "rw").use { file ->
                file.setLength(0)
                file.write(ByteArray(44)) // header, filled in at the end
                var samples = 0L
                val limit = maxMs * rate / 1000
                record.startRecording()
                try {
                    while (running && samples < limit) {
                        val n = record.read(buffer, 0, buffer.size)
                        if (n <= 0) continue
                        val bytes = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                        for (i in 0 until n) bytes.putShort(buffer[i])
                        file.write(bytes.array())
                        samples += n
                    }
                } finally {
                    runCatching { record.stop() }
                    record.release()
                    running = false
                }
                file.seek(0)
                file.write(Wav.header(samples, rate))
            }
        }
    }

    /** Stops and waits for the file to be complete. */
    fun stop() {
        running = false
        worker?.join(2_000)
        worker = null
    }

    companion object {
        const val SAMPLE_RATE = 16_000
    }
}

/** Reading and writing the app's simple WAV files (16-bit mono PCM). */
object Wav {
    fun header(samples: Long, rate: Int): ByteArray {
        val data = samples * 2
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt((36 + data).toInt()); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data.toInt())
        }.array()
    }

    /** The samples of a 16-bit mono WAV file as floats (-1..1); empty if unreadable. */
    fun read(path: String): FloatArray = runCatching {
        val bytes = File(path).readBytes()
        // Find the "data" chunk (headers may carry extra chunks).
        var pos = 12
        var start = 44
        var size = bytes.size - 44
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val len = ByteBuffer.wrap(bytes, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "data") { start = pos + 8; size = minOf(len, bytes.size - start); break }
            pos += 8 + len
        }
        val buf = ByteBuffer.wrap(bytes, start, size).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        FloatArray(buf.remaining()) { buf.get(it) / 32768f }
    }.getOrDefault(FloatArray(0))

    /** Loudness of [bars] equal slices, 0..1, for drawing a waveform. */
    fun peaks(samples: FloatArray, bars: Int): FloatArray {
        if (samples.isEmpty()) return FloatArray(bars)
        val per = maxOf(1, samples.size / bars)
        val out = FloatArray(bars) { b ->
            var max = 0f
            val from = b * per
            for (i in from until minOf(samples.size, from + per)) max = maxOf(max, kotlin.math.abs(samples[i]))
            max
        }
        val top = out.maxOrNull()?.takeIf { it > 0f } ?: 1f
        return FloatArray(bars) { out[it] / top }
    }
}
