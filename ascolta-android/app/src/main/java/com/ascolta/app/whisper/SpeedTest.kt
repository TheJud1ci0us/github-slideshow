package com.ascolta.app.whisper

import android.content.Context
import com.ascolta.app.R
import com.ascolta.app.data.AppLog
import com.ascolta.app.graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class SpeedResult(
    val model: String,
    val audioMs: Long,
    val loadMs: Long,
    val transcribeMs: Long,
    val sentences: Int,
    val firstSentence: String?,
    val cpu: String,
    val threads: Int,
    val flashAttention: Boolean,
    val skipNonSpeech: Boolean,
) {
    /** How many seconds of audio are processed per second. */
    val speedFactor: Float get() = audioMs.toFloat() / transcribeMs.coerceAtLeast(1)
}

/**
 * Transcribes one minute of a downloaded episode with the current settings and times it,
 * so you can compare models, core counts and flash attention on your own phone.
 */
object SpeedTest {
    private const val SAMPLE_SECONDS = 60

    suspend fun run(context: Context): SpeedResult = withContext(Dispatchers.Default) {
        val graph = context.graph
        val settings = graph.settings
        val model = settings.model
        if (!graph.models.isDownloaded(model)) {
            throw IllegalStateException(context.getString(R.string.speed_test_need_model, model.label))
        }
        val episode = graph.db.episodeDao().downloaded().firstOrNull { File(it.localPath!!).exists() }
            ?: throw IllegalStateException(context.getString(R.string.speed_test_need_episode))
        val language = episode.transcriptLanguage ?: settings.learningLanguage.value
        if (!model.supports(language)) {
            throw IllegalStateException(context.getString(R.string.error_model_language, model.label, language))
        }

        val useVad = settings.skipNonSpeech.value || model.engine == Engine.PARAKEET
        if (useVad) graph.models.downloadVad()
        val options = EngineOptions(
            threads = settings.threads.value.coerceIn(1, 8),
            flashAttention = settings.flashAttention.value,
            vadModelPath = if (useVad) graph.models.vadFile().path else null,
        )

        // Skip the first two minutes when possible: intros are often music.
        val decoder = AudioDecoder(episode.localPath!!)
        val duration = decoder.durationMs()
        val startMs = if (duration > 4 * 60_000) 2 * 60_000L else 0L
        var sample: FloatArray? = null
        decoder.decode(SAMPLE_SECONDS * AudioDecoder.SAMPLE_RATE, startMs * 16) { chunk, _ ->
            sample = chunk
            false
        }
        val audio = sample ?: throw IllegalStateException(context.getString(R.string.speed_test_no_audio))

        EngineLock.mutex.withLock {
            WhisperLib.ensureBackends(context.applicationInfo.nativeLibraryDir)
            val cpu = runCatching { WhisperLib.nativeCpuVariant() }.getOrDefault("?")
            val loadStart = System.currentTimeMillis()
            SpeechEngine.load(model, graph.models, options).use { engine ->
                val loadMs = System.currentTimeMillis() - loadStart
                val t0 = System.currentTimeMillis()
                val segments = engine.transcribe(audio, language, null) {} ?: emptyList()
                val took = System.currentTimeMillis() - t0
                SpeedResult(
                    model = model.label,
                    audioMs = audio.size / 16L,
                    loadMs = loadMs,
                    transcribeMs = took,
                    sentences = segments.size,
                    firstSentence = segments.firstOrNull { it.text.isNotBlank() }?.text?.trim(),
                    cpu = cpu,
                    threads = options.threads,
                    flashAttention = options.flashAttention,
                    skipNonSpeech = useVad,
                ).also {
                    AppLog.i(
                        "SpeedTest",
                        "${it.model}: ${it.audioMs / 1000}s audio in ${it.transcribeMs}ms " +
                            "(${"%.1f".format(java.util.Locale.US, it.speedFactor)}x), load ${it.loadMs}ms, " +
                            "threads=${it.threads} flash=${it.flashAttention} vad=${it.skipNonSpeech} cpu=${it.cpu}",
                    )
                }
            }
        }
    }
}
