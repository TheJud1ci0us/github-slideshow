package com.ascolta.app.whisper

import android.media.MediaPlayer
import com.ascolta.app.data.AppLog
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Short clips of the original audio (the sentence a word was saved from), kept with the
 * saved word so it can be heard again in the Words list and during flashcard review.
 */
object AudioClip {
    const val PAD_BEFORE_MS = 250L
    const val PAD_AFTER_MS = 400L
    private const val MAX_MS = 20_000L

    /** Cuts [startMs]..[endMs] (plus a little padding) of [audioPath] into a 16 kHz WAV file. */
    fun extract(
        audioPath: String, startMs: Long, endMs: Long, out: File,
        padBeforeMs: Long = PAD_BEFORE_MS, padAfterMs: Long = PAD_AFTER_MS,
    ): Boolean = runCatching {
        val from = (startMs - padBeforeMs).coerceAtLeast(0)
        val length = (endMs + padAfterMs - from).coerceIn(300, MAX_MS)
        val samples = BlockReader(audioPath).use { it.read(from, length).first }
        if (samples.isEmpty()) return false
        out.parentFile?.mkdirs()
        writeWav(out, samples)
        true
    }.onFailure { AppLog.w("Clip", "Couldn't cut clip from $audioPath", it) }.getOrDefault(false)

    private fun writeWav(out: File, samples: FloatArray) {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        // Short fades so the clip doesn't click at the cut points.
        val fade = minOf(160, samples.size / 4)
        samples.forEachIndexed { i, s ->
            val gain = when {
                i < fade -> i / fade.toFloat()
                i >= samples.size - fade -> (samples.size - 1 - i) / fade.toFloat()
                else -> 1f
            }
            data.putShort((s * gain * 32767f).coerceIn(-32768f, 32767f).toInt().toShort())
        }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data.capacity()); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(AudioDecoder.SAMPLE_RATE); putInt(AudioDecoder.SAMPLE_RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data.capacity())
        }
        RandomAccessFile(out, "rw").use { f ->
            f.setLength(0)
            f.write(header.array())
            f.write(data.array())
        }
    }

    private var player: MediaPlayer? = null

    /** Plays a clip (stopping one already playing). */
    fun play(path: String) {
        stop()
        player = runCatching {
            MediaPlayer().apply {
                setDataSource(path)
                setOnCompletionListener { it.release(); if (player === it) player = null }
                prepare()
                start()
            }
        }.onFailure { AppLog.w("Clip", "Couldn't play $path", it) }.getOrNull()
    }

    fun stop() {
        player?.let { runCatching { it.stop(); it.release() } }
        player = null
    }
}
