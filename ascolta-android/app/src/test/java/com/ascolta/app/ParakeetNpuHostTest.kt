package com.ascolta.app

import com.ascolta.app.whisper.EngineOptions
import com.ascolta.app.whisper.ParakeetNpuEngine
import com.ascolta.app.whisper.WhisperSegment
import com.ascolta.app.whisper.WordAccuracy
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs the NPU version of Parakeet (Qualcomm AI Hub export) with the app's own feature
 * extraction and TDT decoding, on this computer's CPU, against the bundled Italian sample.
 * Only runs when the model was downloaded: -DparakeetNpuDir=/path/to/parakeet_tdt-onnx-float
 */
class ParakeetNpuHostTest {
    @Test
    fun transcribesItalianSample() = run(split = false)

    /** The fallback layout: subsampling and conformer layers as two separate models. */
    @Test
    fun transcribesItalianSampleWithSplitEncoder() = run(split = true)

    private fun run(split: Boolean) {
        val dir = System.getProperty("parakeetNpuDir")?.let(::File)
        assumeTrue("set -DparakeetNpuDir to run", dir != null && File(dir, "encoder_onnx/encoder.onnx").exists())
        val tokens = File(System.getProperty("parakeetTokens")!!)
        val wav = File("src/main/assets/benchmark_it.wav").readBytes()
        val pcm = ByteBuffer.wrap(wav, 44, wav.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val audio = FloatArray(pcm.remaining()) { pcm.get(it) / 32768f }
        val reference = File("src/main/assets/benchmark_it.txt").readText()

        if (split) {
            // Same files the app installs next to encoder.data from its assets.
            listOf("encoder_pre.onnx", "encoder_post.onnx").forEach { name ->
                File("src/main/assets/parakeet_npu/$name").copyTo(File(dir!!, "encoder_onnx/$name"), overwrite = true)
            }
        }
        val engine = ParakeetNpuEngine(dir!!, tokens, EngineOptions(threads = 4, flashAttention = false), npu = false, split = split)
        val segments = mutableListOf<WhisperSegment>()
        val t0 = System.currentTimeMillis()
        engine.transcribe(audio, null, "it", null, onProgress = {}, onSegment = { segments += it })
        val took = System.currentTimeMillis() - t0
        engine.close()
        val text = segments.joinToString(" ") { it.text }
        val accuracy = WordAccuracy.accuracy(reference, text)
        println("NPU model on host CPU (split=$split): ${took} ms, accuracy ${(accuracy * 100).toInt()}%")
        segments.forEach { println("  [${it.startMs}-${it.endMs}] ${it.text}  words=${it.words?.take(4)}") }
        assertTrue("accuracy $accuracy: $text", accuracy > 0.8f)
    }
}
