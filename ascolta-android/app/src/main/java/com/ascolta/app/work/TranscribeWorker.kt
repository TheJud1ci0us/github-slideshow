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
import com.ascolta.app.data.Span
import com.ascolta.app.data.Transcript
import com.ascolta.app.data.TranscriptState
import com.ascolta.app.graph
import com.ascolta.app.net.FeedTranscriptParser
import com.ascolta.app.net.fetchString
import com.ascolta.app.whisper.BlockReader
import com.ascolta.app.whisper.SpeechDetection
import com.ascolta.app.R
import com.ascolta.app.data.languageName
import com.ascolta.app.whisper.CoreDispatchers
import com.ascolta.app.whisper.CpuTopology
import com.ascolta.app.whisper.Engine
import com.ascolta.app.whisper.EngineLock
import com.ascolta.app.whisper.EngineOptions
import com.ascolta.app.whisper.SpeechEngine
import com.ascolta.app.whisper.SpeechModel
import com.ascolta.app.whisper.WhisperLib
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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

    /** Set when this job stepped aside for the episode being played; it is rescheduled. */
    private var yielded = false

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
            transcript = transcribeAudio(path, language, target, transcript) ?: run {
                if (yielded) dao.setTranscript(episodeId, TranscriptState.QUEUED, status.value.progress, applicationContext.getString(R.string.status_waiting))
                return Result.retry()
            }
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

        // Resume only a partial transcript made with the same model and language, keeping only
        // sentences inside the stretches already marked as done (an interrupted block is redone).
        val initial = existing?.takeIf {
            it.source == Transcript.SOURCE_WHISPER && it.language == language && (it.model == null || it.model == model.id)
        }?.let { t ->
            val spans = t.coveredSpans()
            t.copy(covered = spans, segments = t.segments.filter { seg -> spans.any { seg.startMs >= it.startMs - 500 && seg.startMs < it.endMs } })
        } ?: Transcript(language, Transcript.SOURCE_WHISPER, emptyList(), model = model.id)

        val canTranslate = prepareTranslation(language, target)

        update(applicationContext.getString(R.string.status_loading_model), status.value.progress)
        WhisperLib.ensureBackends(applicationContext.applicationInfo.nativeLibraryDir)
        CoreDispatchers.prepareRecognition(threads)
        AppLog.i(TAG, "CPU: ${runCatching { WhisperLib.nativeCpuVariant() }.getOrDefault("?")}; ${CpuTopology.current}")
        var stopped = false
        val started = System.currentTimeMillis()
        val transcribing = applicationContext.getString(R.string.status_transcribing)
        val latest = MutableStateFlow(initial)

        val reader = withContext(CoreDispatchers.light) { BlockReader(path) }
        val totalMs = reader.durationMs.coerceAtLeast(1)
        AppLog.i(TAG, "Audio ${totalMs / 1000}s, already done ${Coverage.totalMs(initial.covered) / 1000}s; " +
            "model=${model.id} threads=$threads flash=${options.flashAttention} skipNonSpeech=$useVad")
        fun progressOf(t: Transcript) = Coverage.totalMs(t.covered).toFloat() / totalMs

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
                                        segments = (t.segments + event.segment.copy(translation = translation)).sortedBy { it.startMs },
                                        model = model.id,
                                        translationLanguage = if (canTranslate) target else t.translationLanguage,
                                    )
                                }
                                is WriterEvent.Done -> {
                                    val covered = Coverage.add(t.covered, event.span)
                                    t = t.copy(covered = covered, processedMs = Coverage.runEnd(covered, 0))
                                }
                            }
                            latest.value = t
                            val now = System.currentTimeMillis()
                            if (event is WriterEvent.Done || now - lastSave >= 700) {
                                graph.transcripts.save(episodeId, t)
                                lastSave = now
                            }
                        }
                        graph.transcripts.save(episodeId, t)
                    }

                    // Audio for a block, prepared on the efficiency cores: decoding, then speech
                    // detection split across all efficiency cores.
                    suspend fun prepare(span: Span): PreparedBlock {
                        val (samples, firstSample) = withContext(CoreDispatchers.light) {
                            val ctx = coroutineContext
                            reader.read(span.startMs, span.endMs - span.startMs) { !ctx.isActive }
                        }
                        val speech = vadPath?.let { SpeechDetection.detect(it, samples) }
                        return PreparedBlock(Span(firstSample / 16, firstSample / 16 + samples.size / 16L), samples, speech)
                    }

                    var prefetch: Pair<Span, Deferred<PreparedBlock>>? = null
                    var anchorMs = 0L          // where the work is heading
                    var lastListenPos = -1L
                    var speedFactor: Float? = null
                    var buffered = false        // paused because far enough ahead of playback
                    var jumpedToMs = -1L

                    while (true) {
                        if (isStopped) { stopped = true; break }
                        val spans = latest.value.covered
                        if (Coverage.isComplete(spans, totalMs)) break

                        // Follow the listener when this episode is loaded in the player.
                        val player = graph.player.state.value
                        val following = player.episodeId == episodeId
                        // Another episode is playing and waiting to be transcribed: let it go first.
                        val playingOther = player.episodeId?.takeIf { it != episodeId }
                        if (playingOther != null && dao.get(playingOther)?.transcriptState == TranscriptState.QUEUED) {
                            AppLog.i(TAG, "Episode #$playingOther is playing and queued: pausing #$episodeId for it")
                            yielded = true
                            stopped = true
                            break
                        }
                        val listening = following && (player.isPlaying || player.isBuffering)
                        if (following) {
                            if (lastListenPos < 0 || kotlin.math.abs(player.positionMs - lastListenPos) > 10_000) jumpedToMs = player.positionMs
                            lastListenPos = player.positionMs
                            anchorMs = player.positionMs
                        }

                        // Like a video buffer: while listening, stop once far enough ahead.
                        if (listening) {
                            val limits = BufferPolicy.limits(speedFactor, player.speed, BLOCK_MS)
                            val ahead = Coverage.runEnd(spans, player.positionMs) - player.positionMs
                            if (limits != null) {
                                if (buffered && ahead < limits.resumeBelowMs) buffered = false
                                if (!buffered && ahead >= limits.pauseAboveMs) {
                                    buffered = true
                                    AppLog.i(TAG, "Buffered ${ahead / 1000}s ahead of playback; pausing until below ${limits.resumeBelowMs / 1000}s")
                                }
                                if (buffered) {
                                    update(applicationContext.getString(R.string.status_buffered_ahead, (ahead / 1000).toInt()), progressOf(latest.value))
                                    delay(1_000)
                                    continue
                                }
                            }
                        } else {
                            buffered = false
                        }

                        // Short first block right after starting or jumping, so text appears within seconds.
                        val nearJump = following && jumpedToMs >= 0 && Coverage.runEnd(spans, jumpedToMs) - jumpedToMs < FIRST_BLOCK_MS
                        val blockMs = if (nearJump) FIRST_BLOCK_MS else BLOCK_MS
                        val block = TranscriptionPlan.nextBlock(spans, anchorMs, totalMs, blockMs) ?: break
                        val blockStarted = System.currentTimeMillis()
                        val ready = prefetch?.takeIf { it.first == block }?.second
                        if (ready == null) prefetch?.second?.cancel()
                        prefetch = null
                        val prepared = ready?.await() ?: prepare(block)
                        if (prepared.samples.isEmpty()) {
                            events.send(WriterEvent.Done(block)) // past the decodable end of the file
                            continue
                        }
                        // Prepare the following block while this one is recognised.
                        val nextSpan = TranscriptionPlan.nextBlock(Coverage.add(spans, block), block.endMs, totalMs, BLOCK_MS)
                        if (nextSpan != null && nextSpan.startMs >= block.endMs) prefetch = nextSpan to async { prepare(nextSpan) }

                        val prompt = latest.value.segments.filter { it.endMs <= block.startMs + 500 }.takeLast(3)
                            .joinToString(" ") { it.text }.takeLast(200)
                        update(transcribing, progressOf(latest.value))

                        // While recognising, watch the player: if the listener jumps somewhere this
                        // block doesn't help, abandon it and go there.
                        var abandoned = false
                        val watcher = launch {
                            while (isActive) {
                                delay(300)
                                val p = graph.player.state.value
                                if (p.episodeId == episodeId && TranscriptionPlan.shouldAbandon(latest.value.covered, p.positionMs, block)) {
                                    abandoned = true
                                    engine.abort()
                                    break
                                }
                            }
                        }
                        val blockStartMs = prepared.span.startMs
                        val blockLenMs = prepared.span.endMs - prepared.span.startMs
                        var lastEnd = blockStartMs
                        var count = 0
                        val recognitionStarted = System.currentTimeMillis()
                        val finished = withContext(CoreDispatchers.recognition) {
                            engine.transcribe(
                                prepared.samples, prepared.speech, language, prompt,
                                onProgress = { percent ->
                                    update(transcribing, progressOf(latest.value) + blockLenMs * percent / 100f / totalMs)
                                },
                                onSegment = { seg ->
                                    val text = seg.text.trim()
                                    if (text.isNotEmpty() && !isHallucination(text)) {
                                        count++
                                        lastEnd = maxOf(lastEnd, seg.endMs + blockStartMs)
                                        events.trySend(
                                            WriterEvent.Sentence(
                                                Segment(
                                                    seg.startMs + blockStartMs,
                                                    seg.endMs + blockStartMs,
                                                    text,
                                                    words = seg.words?.map { it.copy(startMs = it.startMs + blockStartMs) },
                                                ),
                                            ),
                                        )
                                    }
                                },
                            )
                        }
                        watcher.cancel()
                        if (!finished) {
                            if (abandoned && !isStopped) {
                                // Keep what was recognised; the rest of this block is done later.
                                if (lastEnd > blockStartMs) events.send(WriterEvent.Done(Span(blockStartMs, lastEnd)))
                                prefetch?.second?.cancel()
                                prefetch = null
                                AppLog.i(TAG, "Listener moved to ${graph.player.state.value.positionMs / 1000}s: left block at ${blockStartMs / 1000}s")
                                continue
                            }
                            stopped = true
                            break
                        }
                        val now = System.currentTimeMillis()
                        val wall = (now - blockStarted).coerceAtLeast(1)
                        val factor = blockLenMs.toFloat() / wall
                        speedFactor = speedFactor?.let { it * 0.5f + factor * 0.5f } ?: factor
                        AppLog.i(
                            TAG,
                            "Block ${blockStartMs / 1000}-${(blockStartMs + blockLenMs) / 1000}s " +
                                "(${prepared.speech?.sumOf { it.second - it.first }?.div(1000) ?: (blockLenMs / 1000)}s speech): " +
                                "recognition ${(now - recognitionStarted) / 1000.0}s, total ${wall / 1000.0}s " +
                                "(${"%.1f".format(java.util.Locale.US, factor)}x real time)" +
                                "${if (following) ", following playback at ${player.positionMs / 1000}s" else ""}, $count sentences",
                        )
                        events.send(WriterEvent.Done(Span(blockStartMs, blockStartMs + blockLenMs)))
                        if (!following) anchorMs = blockStartMs + blockLenMs
                    }
                    prefetch?.second?.cancel()
                    events.close()
                    writer.join()
                }
            } finally {
                abortHandle?.dispose()
            }
        } finally {
            withContext(NonCancellable + CoreDispatchers.recognition) { engine.close() }
            withContext(NonCancellable + CoreDispatchers.light) { reader.close() }
        }
        if (stopped) return null
        val seconds = (System.currentTimeMillis() - started) / 1000
        AppLog.i(TAG, "Transcription finished in ${seconds}s for ${totalMs / 1000}s of audio")
        return latest.value.copy(complete = true, processedMs = totalMs, covered = listOf(Span(0, totalMs)))
    }

    private class PreparedBlock(val span: Span, val samples: FloatArray, val speech: List<Pair<Long, Long>>?)

    private sealed interface WriterEvent {
        data class Sentence(val segment: Segment) : WriterEvent
        /** This stretch is fully transcribed. */
        data class Done(val span: Span) : WriterEvent
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
        /** First block after starting or jumping: short, so text appears within seconds. */
        private const val FIRST_BLOCK_MS = 20_000L
        /** Later blocks: long enough to keep the engine busy, short enough to react to jumps. */
        private const val BLOCK_MS = 60_000L
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
