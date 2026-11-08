package com.ascolta.app.work

import android.content.Context
import android.net.ConnectivityManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ascolta.app.data.AppLog
import com.ascolta.app.data.DownloadState
import com.ascolta.app.data.EpisodeEntity
import com.ascolta.app.data.Segment
import com.ascolta.app.data.Transcript
import com.ascolta.app.data.TranscriptState
import com.ascolta.app.graph
import com.ascolta.app.net.FeedTranscriptParser
import com.ascolta.app.net.fetchString
import com.ascolta.app.whisper.AudioDecoder
import com.ascolta.app.R
import com.ascolta.app.data.languageName
import com.ascolta.app.whisper.Engine
import com.ascolta.app.whisper.EngineLock
import com.ascolta.app.whisper.EngineOptions
import com.ascolta.app.whisper.SpeechEngine
import com.ascolta.app.whisper.SpeechModel
import com.ascolta.app.whisper.WhisperLib
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Produces a transcript (+ translation) for one episode, entirely on the phone:
 *  1. If the podcast publishes a timed transcript, use it.
 *  2. Otherwise decode the audio and run whisper.cpp on it in chunks (a short first
 *     chunk so text appears quickly, then 5-minute chunks). Progress is saved after
 *     every chunk, so an interrupted job resumes where it stopped and the partial
 *     transcript is readable while the rest is processed.
 *  3. Translate each sentence with ML Kit. Translation never blocks transcription:
 *     if the translation model isn't available, it is filled in later.
 */
class TranscribeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val graph = context.graph
    private val dao = graph.db.episodeDao()
    private var episodeId = -1L
    private var title = ""

    private data class Status(val text: String, val progress: Float)
    private val status = MutableStateFlow(Status("…", 0f))

    override suspend fun doWork(): Result {
        episodeId = inputData.getLong(WorkScheduler.KEY_EPISODE_ID, -1)
        val episode = dao.get(episodeId) ?: return Result.success()
        if (episode.transcriptState == TranscriptState.DONE || episode.transcriptState == TranscriptState.NONE) {
            return Result.success() // already done, or cancelled by the user
        }
        title = episode.title

        if (EngineLock.mutex.isLocked) {
            dao.setTranscript(episodeId, TranscriptState.QUEUED, episode.transcriptProgress, applicationContext.getString(R.string.status_waiting_other))
        }
        return EngineLock.mutex.withLock {
            val language = episode.transcriptLanguage ?: graph.settings.learningLanguage.value
            val target = graph.settings.nativeLanguage.value
            AppLog.i(TAG, "Start #$episodeId \"$title\" lang=$language→$target model=${graph.settings.model.id} attempt=$runAttemptCount")
            try {
                coroutineScope {
                    val reporter = launch { reportProgress() }
                    try {
                        run(episode, language, target)
                    } finally {
                        reporter.cancel()
                    }
                }
            } catch (e: CancellationException) {
                AppLog.i(TAG, "Stopped #$episodeId (reason ${stopReasonText()}); will resume")
                throw e
            } catch (e: Throwable) {
                AppLog.w(TAG, "Failed #$episodeId", e)
                dao.setTranscript(episodeId, TranscriptState.FAILED, 0f, null, e.message ?: e.javaClass.simpleName)
                Result.success()
            }
        }
    }

    private fun stopReasonText(): String =
        if (android.os.Build.VERSION.SDK_INT >= 31) stopReason.toString() else "?"

    private suspend fun run(episode: EpisodeEntity, language: String, target: String): Result {
        var transcript = graph.transcripts.load(episodeId)

        if (transcript == null && episode.feedTranscriptUrl != null) {
            update(applicationContext.getString(R.string.status_fetching_published), 0f)
            transcript = runCatching { fetchFeedTranscript(episode, language) }
                .onFailure { AppLog.w(TAG, "Feed transcript unusable", it) }
                .getOrNull()
            transcript?.let {
                AppLog.i(TAG, "Using published transcript (${it.segments.size} sentences)")
                graph.transcripts.save(episodeId, it)
            }
        }

        if (transcript == null || !transcript.complete) {
            val path = episode.localPath
            if (path == null || !File(path).exists()) {
                // Needs the audio first; the download queues this job again when it finishes.
                AppLog.i(TAG, "Audio not downloaded yet; queuing download")
                dao.setTranscript(episodeId, TranscriptState.QUEUED, 0f, applicationContext.getString(R.string.status_waiting_download))
                dao.setDownload(episodeId, DownloadState.QUEUED, 0f)
                WorkScheduler.download(applicationContext, episodeId, transcribeAfter = true, manual = true)
                return Result.success()
            }
            transcript = transcribeAudio(path, language, target, transcript) ?: return Result.retry()
        }

        transcript = translateRemaining(transcript, target)
        graph.transcripts.save(episodeId, transcript)
        dao.setTranscript(episodeId, TranscriptState.DONE, 1f, null)
        AppLog.i(TAG, "Done #$episodeId: ${transcript.segments.size} sentences")
        return Result.success()
    }

    private suspend fun fetchFeedTranscript(episode: EpisodeEntity, language: String): Transcript? {
        val body = graph.http.fetchString(episode.feedTranscriptUrl!!)
        val segments = FeedTranscriptParser.parse(body, episode.feedTranscriptType)
        if (segments.isEmpty()) return null
        return Transcript(
            language = language,
            source = Transcript.SOURCE_FEED,
            segments = segments,
            processedMs = segments.last().endMs,
            complete = true,
        )
    }

    /** Downloads the translation model if allowed and quick enough; never waits indefinitely. */
    private suspend fun prepareTranslation(source: String, target: String): Boolean {
        if (!graph.translation.isSupported(source, target)) return false
        if (runCatching { graph.translation.isModelDownloaded(source, target) }.getOrDefault(false)) return true
        val metered = applicationContext.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true
        if (metered && graph.settings.wifiOnly.value) {
            AppLog.i(TAG, "Translation model missing and not on Wi-Fi; translating later")
            return false
        }
        update(applicationContext.getString(R.string.status_downloading_translation), status.value.progress)
        val ok = withTimeoutOrNull(TRANSLATION_DOWNLOAD_TIMEOUT_MS) {
            runCatching { graph.translation.ensureModel(source, target, requireWifi = false) }
                .onFailure { AppLog.w(TAG, "Translation model download failed", it) }
                .isSuccess
        } ?: false.also { AppLog.w(TAG, "Translation model download timed out; translating later") }
        return ok
    }

    /** Returns null if stopped before finishing (progress so far is saved). */
    private suspend fun transcribeAudio(
        path: String,
        language: String,
        target: String,
        existing: Transcript?,
    ): Transcript? {
        val settings = graph.settings
        val model = settings.model
        if (!model.supports(language)) {
            throw IllegalStateException(
                applicationContext.getString(R.string.error_model_language, model.label, languageName(language)),
            )
        }
        ensureModel(model)

        val useVad = settings.skipNonSpeech.value || model.engine == Engine.PARAKEET
        if (useVad && !graph.models.isVadDownloaded()) {
            update(applicationContext.getString(R.string.status_downloading_vad), status.value.progress)
            graph.models.downloadVad()
        }
        val options = EngineOptions(
            threads = settings.threads.value.coerceIn(1, 8),
            flashAttention = settings.flashAttention.value,
            vadModelPath = if (useVad) graph.models.vadFile().path else null,
        )

        // Resume only a partial transcript made with the same model and language.
        var transcript = existing?.takeIf {
            it.source == Transcript.SOURCE_WHISPER && it.language == language && (it.model == null || it.model == model.id)
        } ?: Transcript(language, Transcript.SOURCE_WHISPER, emptyList(), model = model.id)

        val decoder = AudioDecoder(path)
        val totalMs = decoder.durationMs().coerceAtLeast(1)
        AppLog.i(TAG, "Audio ${totalMs / 1000}s, resuming at ${transcript.processedMs / 1000}s; " +
            "model=${model.id} threads=${options.threads} flash=${options.flashAttention} skipNonSpeech=$useVad")

        val canTranslate = prepareTranslation(language, target)

        update(applicationContext.getString(R.string.status_loading_model), transcript.processedMs.toFloat() / totalMs)
        WhisperLib.ensureBackends(applicationContext.applicationInfo.nativeLibraryDir)
        AppLog.i(TAG, "CPU: ${runCatching { WhisperLib.nativeCpuVariant() }.getOrDefault("?")}")
        var stopped = false
        val started = System.currentTimeMillis()
        val transcribing = applicationContext.getString(R.string.status_transcribing)

        SpeechEngine.load(model, graph.models, options).use { engine ->
            val job = coroutineContext[Job]
            val abortHandle = job?.invokeOnCompletion { engine.abort() }
            try {
                update(applicationContext.getString(R.string.status_reading_audio), transcript.processedMs.toFloat() / totalMs)
                decoder.decode(
                    chunkSamples = CHUNK_SECONDS * AudioDecoder.SAMPLE_RATE,
                    skipSamples = transcript.processedMs * 16,
                    firstChunkSamples = FIRST_CHUNK_SECONDS * AudioDecoder.SAMPLE_RATE,
                ) { samples, startSample ->
                    if (isStopped) {
                        stopped = true
                        return@decode false
                    }
                    val chunkStartMs = startSample / 16
                    val chunkMs = samples.size / 16L
                    val prompt = transcript.segments.takeLast(3).joinToString(" ") { it.text }.takeLast(200)
                    update(transcribing, chunkStartMs.toFloat() / totalMs)

                    val chunkStarted = System.currentTimeMillis()
                    val result = engine.transcribe(samples, language, prompt) { percent ->
                        update(transcribing, (chunkStartMs + chunkMs * percent / 100f) / totalMs)
                    }
                    if (result == null) {
                        stopped = true
                        return@decode false
                    }
                    val took = System.currentTimeMillis() - chunkStarted
                    AppLog.i(
                        TAG,
                        "Chunk at ${chunkStartMs / 1000}s (${chunkMs / 1000}s audio) took ${took / 1000.0}s " +
                            "(${"%.1f".format(java.util.Locale.US, chunkMs.toFloat() / took.coerceAtLeast(1))}x real time), ${result.size} segments",
                    )

                    var newSegments = result
                        .map { Segment(it.startMs + chunkStartMs, it.endMs + chunkStartMs, it.text.trim()) }
                        .filter { it.text.isNotEmpty() && !isHallucination(it.text) }
                    if (canTranslate) {
                        newSegments = newSegments.map { s ->
                            s.copy(translation = runCatching {
                                withTimeoutOrNull(15_000) { graph.translation.translate(s.text, language, target) }
                            }.getOrNull())
                        }
                    }
                    transcript = transcript.copy(
                        segments = transcript.segments + newSegments,
                        processedMs = chunkStartMs + chunkMs,
                        model = model.id,
                        translationLanguage = if (canTranslate) target else transcript.translationLanguage,
                    )
                    graph.transcripts.save(episodeId, transcript)
                    update(transcribing, transcript.processedMs.toFloat() / totalMs)
                    true
                }
            } finally {
                abortHandle?.dispose()
            }
        }
        if (stopped) return null
        val seconds = (System.currentTimeMillis() - started) / 1000
        AppLog.i(TAG, "Transcription finished in ${seconds}s for ${totalMs / 1000}s of audio")
        return transcript.copy(complete = true, processedMs = maxOf(transcript.processedMs, totalMs))
    }

    private suspend fun ensureModel(model: SpeechModel) {
        if (graph.models.isDownloaded(model)) return
        val label = applicationContext.getString(R.string.status_downloading_model, model.label)
        update(label, 0f)
        AppLog.i(TAG, "Downloading model ${model.id}")
        graph.models.download(model) { written, total ->
            update("$label · ${written / 1_000_000} / ${total / 1_000_000} MB", written.toFloat() / total.coerceAtLeast(1))
        }
        AppLog.i(TAG, "Model ${model.id} downloaded")
    }

    private suspend fun translateRemaining(transcript: Transcript, target: String): Transcript {
        if (!graph.translation.isSupported(transcript.language, target)) return transcript
        val stale = transcript.translationLanguage != null && transcript.translationLanguage != target
        val missing = transcript.segments.count { it.translation == null || stale }
        if (missing == 0) return transcript
        if (!prepareTranslation(transcript.language, target)) return transcript
        return try {
            var done = 0
            val segments = transcript.segments.map { s ->
                if (s.translation != null && !stale) s
                else {
                    update(applicationContext.getString(R.string.status_translating), done++.toFloat() / missing)
                    s.copy(translation = withTimeoutOrNull(15_000) {
                        graph.translation.translate(s.text, transcript.language, target)
                    })
                }
            }
            transcript.copy(segments = segments, translationLanguage = target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "Translation failed; will retry from the player", e)
            transcript
        }
    }

    private fun update(text: String, progress: Float) {
        status.value = Status(text, progress.coerceIn(0f, 1f))
    }

    /** Pushes the latest status to the database / notification about once a second. */
    private suspend fun reportProgress() {
        var last: Status? = null
        while (coroutineContext.isActive) {
            val current = status.value
            if (isStopped) break // stopped by the user: don't overwrite the "stopped" state
            if (current != last) {
                last = current
                dao.setTranscript(episodeId, TranscriptState.RUNNING, current.progress, current.text)
                setProgress(workDataOf(WorkScheduler.KEY_PROGRESS to current.progress))
                trySetForeground(
                    Notifications.foregroundInfo(
                        applicationContext,
                        NOTIFICATION_ID,
                        "${current.text} · ${(current.progress * 100).toInt()}%",
                        title,
                        current.progress,
                        mediaProcessing = true,
                    ),
                )
            }
            delay(1000)
        }
    }

    companion object {
        private const val TAG = "Transcribe"
        private const val NOTIFICATION_ID = 901
        private const val FIRST_CHUNK_SECONDS = 45
        private const val CHUNK_SECONDS = 5 * 60
        private const val TRANSLATION_DOWNLOAD_TIMEOUT_MS = 3 * 60_000L


        /** Phrases whisper is known to invent during music or silence (from subtitle training data). */
        private val HALLUCINATIONS = listOf(
            "amara.org", "sottotitoli creati dalla comunità", "sottotitoli a cura di",
            "sottotitoli e revisione a cura di", "qtss", "subtitles by the amara",
            "thanks for watching", "grazie per la visione",
        )

        fun isHallucination(text: String): Boolean {
            val lower = text.lowercase()
            return HALLUCINATIONS.any { it in lower } || lower.matches(Regex("""^[\s\p{Punct}♪]*$"""))
        }
    }
}
