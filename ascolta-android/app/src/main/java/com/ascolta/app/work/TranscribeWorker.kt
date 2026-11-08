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
import com.ascolta.app.whisper.CoreDispatchers
import com.ascolta.app.whisper.CpuTopology
import com.ascolta.app.whisper.Engine
import com.ascolta.app.whisper.SpeechAlignment
import com.ascolta.app.whisper.EngineLock
import com.ascolta.app.whisper.EngineOptions
import com.ascolta.app.whisper.SpeechEngine
import com.ascolta.app.whisper.SpeechModel
import com.ascolta.app.whisper.WhisperLib
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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

        // Parakeet always works on detected speech; for Whisper it's the "skip music and silence" option.
        val useVad = settings.skipNonSpeech.value || model.engine != Engine.WHISPER
        if (useVad && !graph.models.isVadDownloaded()) {
            update(applicationContext.getString(R.string.status_downloading_vad), status.value.progress)
            graph.models.downloadVad()
        }
        val vadPath = if (useVad) graph.models.vadFile().path else null
        val threads = settings.effectiveThreads()
        val options = EngineOptions(threads = threads, flashAttention = settings.flashAttention.value)

        // Resume only a partial transcript made with the same model and language. Sentences
        // after the last finished block (from an interrupted run) are dropped: that audio is
        // transcribed again.
        val initial = existing?.takeIf {
            it.source == Transcript.SOURCE_WHISPER && it.language == language && (it.model == null || it.model == model.id)
        }?.let { t -> t.copy(segments = t.segments.filter { it.startMs < t.processedMs }) }
            ?: Transcript(language, Transcript.SOURCE_WHISPER, emptyList(), model = model.id)

        val decoder = AudioDecoder(path)
        val totalMs = decoder.durationMs().coerceAtLeast(1)
        AppLog.i(TAG, "Audio ${totalMs / 1000}s, resuming at ${initial.processedMs / 1000}s; " +
            "model=${model.id} threads=$threads flash=${options.flashAttention} skipNonSpeech=$useVad")

        val canTranslate = prepareTranslation(language, target)

        update(applicationContext.getString(R.string.status_loading_model), initial.processedMs.toFloat() / totalMs)
        WhisperLib.ensureBackends(applicationContext.applicationInfo.nativeLibraryDir)
        CoreDispatchers.prepareRecognition(threads)
        AppLog.i(TAG, "CPU: ${runCatching { WhisperLib.nativeCpuVariant() }.getOrDefault("?")}; ${CpuTopology.current}")
        var stopped = false
        val started = System.currentTimeMillis()
        val transcribing = applicationContext.getString(R.string.status_transcribing)
        val latest = MutableStateFlow(initial)

        // The engine is created on the recognition thread so its worker threads stay on the fast cores.
        val engine = withContext(CoreDispatchers.recognition) { SpeechEngine.load(applicationContext, model, graph.models, options) }
        try {
            val job = coroutineContext[Job]
            val abortHandle = job?.invokeOnCompletion { engine.abort() }
            try {
                coroutineScope {
                    // Sentences arrive here the moment the engine recognises them; this writer
                    // translates and saves them while recognition carries on.
                    val events = Channel<WriterEvent>(Channel.UNLIMITED)
                    val writer = launch(Dispatchers.Default) {
                        var t = initial
                        var lastSave = 0L
                        for (event in events) {
                            when (event) {
                                is WriterEvent.Sentence -> {
                                    val translation = if (canTranslate) runCatching {
                                        withTimeoutOrNull(15_000) { graph.translation.translate(event.segment.text, language, target) }
                                    }.getOrNull() else null
                                    t = t.copy(
                                        segments = t.segments + event.segment.copy(translation = translation),
                                        model = model.id,
                                        translationLanguage = if (canTranslate) target else t.translationLanguage,
                                    )
                                }
                                is WriterEvent.BlockDone -> t = t.copy(processedMs = event.processedMs)
                            }
                            latest.value = t
                            val now = System.currentTimeMillis()
                            if (event is WriterEvent.BlockDone || now - lastSave >= 700) {
                                graph.transcripts.save(episodeId, t)
                                lastSave = now
                            }
                        }
                        graph.transcripts.save(episodeId, t)
                    }

                    // Producer on the efficiency cores: decodes the audio and finds the speech of
                    // the NEXT block while the current one is being recognised, so recognition
                    // never waits for it.
                    val blocks = Channel<PreparedBlock>(capacity = 1)
                    val producer = launch(CoreDispatchers.light) {
                        try {
                            decoder.decode(
                                chunkSamples = CHUNK_SECONDS * AudioDecoder.SAMPLE_RATE,
                                skipSamples = initial.processedMs * 16,
                                firstChunkSamples = FIRST_CHUNK_SECONDS * AudioDecoder.SAMPLE_RATE,
                            ) { samples, startSample ->
                                val speech = vadPath?.let { p ->
                                    WhisperLib.nativeDetectSpeech(p, samples, 2)?.let { SpeechAlignment.pairs(it) }
                                }
                                blocks.send(PreparedBlock(samples, startSample, speech))
                                true
                            }
                        } finally {
                            blocks.close()
                        }
                    }

                    update(applicationContext.getString(R.string.status_reading_audio), initial.processedMs.toFloat() / totalMs)
                    for (block in blocks) {
                        if (isStopped) {
                            stopped = true
                            break
                        }
                        val chunkStartMs = block.startSample / 16
                        val chunkMs = block.samples.size / 16L
                        val prompt = latest.value.segments.takeLast(3).joinToString(" ") { it.text }.takeLast(200)
                        update(transcribing, maxOf(status.value.progress, chunkStartMs.toFloat() / totalMs))

                        val chunkStarted = System.currentTimeMillis()
                        var count = 0
                        val finished = withContext(CoreDispatchers.recognition) {
                            engine.transcribe(
                                block.samples, block.speech, language, prompt,
                                onProgress = { percent ->
                                    update(transcribing, maxOf(status.value.progress, (chunkStartMs + chunkMs * percent / 100f) / totalMs))
                                },
                                onSegment = { seg ->
                                    val text = seg.text.trim()
                                    if (text.isNotEmpty() && !isHallucination(text)) {
                                        count++
                                        events.trySend(
                                            WriterEvent.Sentence(
                                                Segment(
                                                    seg.startMs + chunkStartMs,
                                                    seg.endMs + chunkStartMs,
                                                    text,
                                                    words = seg.words?.map { it.copy(startMs = it.startMs + chunkStartMs) },
                                                ),
                                            ),
                                        )
                                        update(transcribing, maxOf(status.value.progress, (chunkStartMs + seg.endMs).toFloat() / totalMs))
                                    }
                                },
                            )
                        }
                        if (!finished) {
                            stopped = true
                            break
                        }
                        val took = System.currentTimeMillis() - chunkStarted
                        AppLog.i(
                            TAG,
                            "Block at ${chunkStartMs / 1000}s (${chunkMs / 1000}s audio, " +
                                "${block.speech?.sumOf { it.second - it.first }?.div(1000) ?: (chunkMs / 1000)}s speech) took " +
                                "${took / 1000.0}s (${"%.1f".format(java.util.Locale.US, chunkMs.toFloat() / took.coerceAtLeast(1))}x real time), $count sentences",
                        )
                        events.trySend(WriterEvent.BlockDone(chunkStartMs + chunkMs))
                        update(transcribing, (chunkStartMs + chunkMs).toFloat() / totalMs)
                    }
                    producer.cancel()
                    events.close()
                    writer.join()
                }
            } finally {
                abortHandle?.dispose()
            }
        } finally {
            withContext(NonCancellable + CoreDispatchers.recognition) { engine.close() }
        }
        if (stopped) return null
        val seconds = (System.currentTimeMillis() - started) / 1000
        AppLog.i(TAG, "Transcription finished in ${seconds}s for ${totalMs / 1000}s of audio")
        val transcript = latest.value
        return transcript.copy(complete = true, processedMs = maxOf(transcript.processedMs, totalMs))
    }

    private class PreparedBlock(val samples: FloatArray, val startSample: Long, val speech: List<Pair<Long, Long>>?)

    private sealed interface WriterEvent {
        data class Sentence(val segment: Segment) : WriterEvent
        data class BlockDone(val processedMs: Long) : WriterEvent
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
        private const val FIRST_CHUNK_SECONDS = 30
        private const val CHUNK_SECONDS = 2 * 60
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
