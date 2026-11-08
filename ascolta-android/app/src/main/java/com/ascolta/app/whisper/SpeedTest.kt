package com.ascolta.app.whisper

import android.content.Context
import com.ascolta.app.R
import com.ascolta.app.data.AppLog
import com.ascolta.app.graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Normalizer

data class SpeedResult(
    val model: String,
    val audioMs: Long,
    val loadMs: Long,
    val transcribeMs: Long,
    /** Share of reference words recognised correctly (1 − word error rate), 0..1. */
    val accuracy: Float,
    val transcript: String,
    val cpu: String,
    val threads: Int,
    val flashAttention: Boolean,
    val skipNonSpeech: Boolean,
) {
    /** How many seconds of audio are processed per second. */
    val speedFactor: Float get() = audioMs.toFloat() / transcribeMs.coerceAtLeast(1)
}

/**
 * Transcribes the same 30-second Italian recording bundled with the app, so every run
 * (any model, core count or setting) is measured on identical audio. The sample is
 * from Pirandello's "Novelle per un anno", read by a LibriVox volunteer
 * (Multilingual LibriSpeech, CC BY 4.0).
 */
object SpeedTest {
    private const val SAMPLE_ASSET = "benchmark_it.wav"
    private const val REFERENCE_ASSET = "benchmark_it.txt"
    private const val SAMPLE_LANGUAGE = "it"

    suspend fun run(context: Context, onStatus: (String) -> Unit = {}): SpeedResult = withContext(Dispatchers.Default) {
        val graph = context.graph
        val settings = graph.settings
        val model = settings.model
        if (!graph.models.isDownloaded(model)) {
            throw IllegalStateException(context.getString(R.string.speed_test_need_model, model.label))
        }

        val useVad = settings.skipNonSpeech.value || model.engine == Engine.PARAKEET
        if (useVad && !graph.models.isVadDownloaded()) {
            onStatus(context.getString(R.string.status_downloading_vad))
            graph.models.downloadVad()
        }
        val options = EngineOptions(
            threads = settings.threads.value.coerceIn(1, 8),
            flashAttention = settings.flashAttention.value,
            vadModelPath = if (useVad) graph.models.vadFile().path else null,
        )

        // The decoder reads files, so copy the bundled sample out of the APK once.
        val sampleFile = File(context.cacheDir, SAMPLE_ASSET)
        if (!sampleFile.exists()) {
            context.assets.open(SAMPLE_ASSET).use { input -> sampleFile.outputStream().use { input.copyTo(it) } }
        }
        val reference = context.assets.open(REFERENCE_ASSET).bufferedReader().readText()
        var sample: FloatArray? = null
        AudioDecoder(sampleFile.path).decode(60 * AudioDecoder.SAMPLE_RATE, 0) { chunk, _ ->
            sample = chunk
            false
        }
        val audio = sample ?: throw IllegalStateException(context.getString(R.string.speed_test_no_audio))

        onStatus(context.getString(R.string.status_loading_model))
        EngineLock.mutex.withLock {
            WhisperLib.ensureBackends(context.applicationInfo.nativeLibraryDir)
            val cpu = runCatching { WhisperLib.nativeCpuVariant() }.getOrDefault("?")
            val loadStart = System.currentTimeMillis()
            SpeechEngine.load(model, graph.models, options).use { engine ->
                val loadMs = System.currentTimeMillis() - loadStart
                onStatus(context.getString(R.string.status_transcribing))
                val segments = mutableListOf<WhisperSegment>()
                val t0 = System.currentTimeMillis()
                engine.transcribe(audio, SAMPLE_LANGUAGE, null, onProgress = {}, onSegment = { segments += it })
                val took = System.currentTimeMillis() - t0
                val text = segments.joinToString(" ") { it.text.trim() }
                SpeedResult(
                    model = model.label,
                    audioMs = audio.size / 16L,
                    loadMs = loadMs,
                    transcribeMs = took,
                    accuracy = WordAccuracy.accuracy(reference, text),
                    transcript = text,
                    cpu = cpu,
                    threads = options.threads,
                    flashAttention = options.flashAttention,
                    skipNonSpeech = useVad,
                ).also {
                    AppLog.i(
                        "SpeedTest",
                        "${it.model}: ${it.audioMs / 1000}s sample in ${it.transcribeMs}ms " +
                            "(${"%.1f".format(java.util.Locale.US, it.speedFactor)}x), accuracy " +
                            "${(it.accuracy * 100).toInt()}%, load ${it.loadMs}ms, threads=${it.threads} " +
                            "flash=${it.flashAttention} vad=${it.skipNonSpeech} cpu=${it.cpu}",
                    )
                }
            }
        }
    }
}

/** Word accuracy (1 − word error rate) between a reference text and a transcript. */
object WordAccuracy {
    fun words(text: String): List<String> {
        val lower = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFC)
            .replace('’', '\'')
            .replace('j', 'i') // old Italian spelling in the sample ("ajutati", "portinajo")
        return lower.split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotEmpty() }
    }

    fun accuracy(reference: String, hypothesis: String): Float {
        val ref = words(reference)
        val hyp = words(hypothesis)
        if (ref.isEmpty()) return 0f
        // Levenshtein distance over words.
        var prev = IntArray(hyp.size + 1) { it }
        for (i in 1..ref.size) {
            val cur = IntArray(hyp.size + 1)
            cur[0] = i
            for (j in 1..hyp.size) {
                val cost = if (ref[i - 1] == hyp[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        val wer = prev[hyp.size].toFloat() / ref.size
        return (1f - wer).coerceIn(0f, 1f)
    }
}
