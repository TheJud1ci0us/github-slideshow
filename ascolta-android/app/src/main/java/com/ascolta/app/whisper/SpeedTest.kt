package com.ascolta.app.whisper

import android.content.Context
import com.ascolta.app.R
import com.ascolta.app.data.AppLog
import com.ascolta.app.graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Normalizer

data class SpeedResult(
    val model: String,
    val audioMs: Long,
    val loadMs: Long,
    /** Recognition time of each timed run (after an untimed warm-up run). */
    val runsMs: List<Long>,
    /** Share of reference words recognised correctly (1 − word error rate), 0..1. */
    val accuracy: Float,
    val transcript: String,
    val cpu: String,
    val cores: List<Int>,
    val threads: Int,
    val flashAttention: Boolean,
    val skipNonSpeech: Boolean,
) {
    val medianMs: Long get() = runsMs.sorted()[runsMs.size / 2]
    val minMs: Long get() = runsMs.min()
    val maxMs: Long get() = runsMs.max()

    /** How many seconds of audio are processed per second (median run). */
    val speedFactor: Float get() = audioMs.toFloat() / medianMs.coerceAtLeast(1)
}

/**
 * Transcribes the same 30-second Italian recording bundled with the app, so every run
 * (any model, core count or setting) is measured on identical audio. The sample is
 * from Pirandello's "Novelle per un anno", read by a LibriVox volunteer
 * (Multilingual LibriSpeech, CC BY 4.0).
 *
 * One untimed warm-up run first (the model is read into memory and the CPU ramps up its
 * clock), then [TIMED_RUNS] timed runs; the median is reported with the spread.
 */
object SpeedTest {
    private const val SAMPLE_ASSET = "benchmark_it.wav"
    private const val REFERENCE_ASSET = "benchmark_it.txt"
    private const val SAMPLE_LANGUAGE = "it"
    const val TIMED_RUNS = 3

    suspend fun run(context: Context, onStatus: (String) -> Unit = {}): SpeedResult = withContext(Dispatchers.Default) {
        val graph = context.graph
        val settings = graph.settings
        val model = settings.model
        if (!graph.models.isDownloaded(model)) {
            throw IllegalStateException(context.getString(R.string.speed_test_need_model, model.label))
        }

        val useVad = settings.skipNonSpeech.value || model.engine != Engine.WHISPER
        if (useVad && !graph.models.isVadDownloaded()) {
            onStatus(context.getString(R.string.status_downloading_vad))
            graph.models.downloadVad()
        }
        val threads = settings.effectiveThreads()
        val options = EngineOptions(threads = threads, flashAttention = settings.flashAttention.value)

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
            // Speech detection runs beforehand on the efficiency cores, as during transcription.
            val speech = if (useVad) withContext(CoreDispatchers.light) {
                WhisperLib.nativeDetectSpeech(graph.models.vadFile().path, audio, 2)?.let { SpeechAlignment.pairs(it) }
            } else null
            CoreDispatchers.prepareRecognition(threads)
            val cpu = runCatching { WhisperLib.nativeCpuVariant() }.getOrDefault("?")
            val loadStart = System.currentTimeMillis()
            val engine = withContext(CoreDispatchers.recognition) { SpeechEngine.load(context, model, graph.models, options) }
            try {
                val loadMs = System.currentTimeMillis() - loadStart
                var text = ""
                val runs = mutableListOf<Long>()
                for (run in 0..TIMED_RUNS) {
                    onStatus(
                        if (run == 0) context.getString(R.string.speed_test_warmup)
                        else context.getString(R.string.speed_test_run, run, TIMED_RUNS),
                    )
                    val segments = mutableListOf<WhisperSegment>()
                    val t0 = System.currentTimeMillis()
                    withContext(CoreDispatchers.recognition) {
                        engine.transcribe(audio, speech, SAMPLE_LANGUAGE, null, onProgress = {}, onSegment = { segments += it })
                    }
                    val took = System.currentTimeMillis() - t0
                    if (run > 0) runs += took
                    text = segments.joinToString(" ") { it.text.trim() }
                }
                SpeedResult(
                    model = model.label,
                    audioMs = audio.size / 16L,
                    loadMs = loadMs,
                    runsMs = runs,
                    accuracy = WordAccuracy.accuracy(reference, text),
                    transcript = text,
                    cpu = cpu,
                    cores = CpuTopology.current.recognitionCores(threads),
                    threads = threads,
                    flashAttention = options.flashAttention,
                    skipNonSpeech = useVad,
                ).also {
                    AppLog.i(
                        "SpeedTest",
                        "${it.model}: ${it.audioMs / 1000}s sample, runs ${it.runsMs} ms (median ${it.medianMs}, " +
                            "${"%.1f".format(java.util.Locale.US, it.speedFactor)}x), accuracy ${(it.accuracy * 100).toInt()}%, " +
                            "load ${it.loadMs}ms, threads=${it.threads} cores=${it.cores} flash=${it.flashAttention} " +
                            "vad=${it.skipNonSpeech} cpu=${it.cpu}",
                    )
                }
            } finally {
                withContext(NonCancellable + CoreDispatchers.recognition) { engine.close() }
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
