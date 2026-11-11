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
import com.ascolta.app.data.Names
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
import kotlinx.coroutines.flow.first
import com.ascolta.app.AppVisibility
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
        EngineLock.waiting.incrementAndGet()
        var counted = true
        fun leaveQueue() { if (counted) { counted = false; EngineLock.waiting.decrementAndGet() } }
        return try { EngineLock.mutex.withLock {
            leaveQueue()
            val language = episode.transcriptLanguage ?: graph.settings.learningLanguage.value
            val target = graph.settings.nativeLanguage.value
            AppLog.i(TAG, "Start #$episodeId \"$title\" lang=$language→$target model=${graph.settings.modelFor(language).id} attempt=$runAttemptCount")
            com.ascolta.app.data.Analytics.track(
                "Transcription.started",
                mapOf("model" to graph.settings.modelFor(language).id, "language" to language, "attempt" to runAttemptCount.toString(),
                    "gpu" to graph.settings.useGpu.value.toString(), "threads" to graph.settings.effectiveThreads().toString()),
            )
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
                com.ascolta.app.data.Analytics.track(
                    "Transcription.failed",
                    mapOf("model" to graph.settings.modelFor(language).id, "language" to language, "error" to e.javaClass.simpleName),
                )
                dao.setTranscript(episodeId, TranscriptState.FAILED, 0f, null, e.message ?: e.javaClass.simpleName)
                Result.success()
            }
        } } finally {
            leaveQueue()
            // Any way out that runs this (done, failed, stopped) isn't a crash; a native crash skips it.
            CrashGuard.clear(applicationContext)
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
        runCatching { graph.repo.refreshProgressCounts() }
        AppLog.i(TAG, "Done #$episodeId: ${transcript.segments.size} sentences")
        com.ascolta.app.data.Analytics.track(
            "Transcription.completed",
            mapOf("model" to graph.settings.modelFor(language).id, "language" to language, "source" to transcript.source),
            value = (episode.durationSec / 60.0),
        )
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
        // Only a translation download the learner agreed to (asked with its size).
        if (!graph.settings.isApproved("tr:$source-$target")) {
            AppLog.i(TAG, "Translation model $source→$target not agreed yet; transcript only for now")
            return false
        }
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
        // Parakeet only knows 25 European languages: other podcasts use Whisper instead.
        // Each language has its own model (e.g. Parakeet for Italian, SenseVoice for Japanese).
        // The free version uses the Balanced model where the chosen one is a Pro model.
        val model = com.ascolta.app.data.FreeLimits.allowedModel(settings.modelFor(language), language, graph.isPro)
        if (!graph.isPro && graph.limits.transcriptionLeftMs() <= 0) throw FreeLimitReached(applicationContext.getString(R.string.free_minutes_used))
        // Did the previous attempt crash inside the speech engine (a damaged model file)?
        val mark = CrashGuard.read(applicationContext)
        val guardAction = if (mark == null) CrashGuard.Action.CONTINUE
        else CrashGuard.decide(mark, model.id, episodeId, CrashGuard.lastExitWasCrash(applicationContext))
        when (guardAction) {
            CrashGuard.Action.REDOWNLOAD -> {
                AppLog.w(TAG, "Model ${model.id} crashed the app last time; downloading it again")
                graph.models.delete(model)
            }
            CrashGuard.Action.GIVE_UP -> {
                AppLog.w(TAG, "Model ${model.id} crashed the app again; giving up on #$episodeId")
                CrashGuard.clear(applicationContext)
                throw IllegalStateException(applicationContext.getString(R.string.model_crashed, model.label))
            }
            CrashGuard.Action.CONTINUE -> Unit
        }
        val crashCount = if (guardAction == CrashGuard.Action.CONTINUE) 0 else CrashGuard.crashes(mark, model.id, episodeId) + 1
        ensureModel(model)

        // "Skip music and silence" (on by default, for every model). Off: the whole audio is
        // recognised, in pieces of at most 25 s for Parakeet and the other sherpa models.
        val useVad = settings.skipNonSpeech.value
        if (useVad && !graph.models.isVadDownloaded()) {
            // Agreed together with the speech model (it is listed in the same question).
            if (!settings.isApproved("vad") && !settings.isApproved("model:${model.id}")) {
                throw ModelNeeded(applicationContext.getString(R.string.model_needed, applicationContext.getString(R.string.voice_detection)))
            }
            update(applicationContext.getString(R.string.status_downloading_vad), status.value.progress)
            graph.models.downloadVad()
        }
        val vadPath = if (useVad) graph.models.vadFile().path else null
        val threads = settings.effectiveThreads()
        val options = EngineOptions(
            threads = threads, flashAttention = settings.flashAttention.value, language = language,
            gpu = settings.useGpu.value && model.gpu,
        )

        // Resume only a partial transcript made with the same model and language, keeping only
        // sentences inside the stretches already marked as done (an interrupted block is redone).
        val initial = existing?.takeIf {
            it.source == Transcript.SOURCE_WHISPER && it.language == language && (it.model == null || it.model == model.id)
        }?.let { t ->
            val spans = t.coveredSpans()
            t.copy(covered = spans, segments = t.segments.filter { seg -> spans.any { seg.startMs >= it.startMs - 500 && seg.startMs < it.endMs } })
        } ?: Transcript(language, Transcript.SOURCE_WHISPER, emptyList(), model = model.id)

        val canTranslate = graph.settings.translate.value && prepareTranslation(language, target)

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
        // Loaded on demand and released while the buffer zone is full, so a waiting job
        // doesn't hold hundreds of MB of model in memory.
        // Names the model can't know (guests, places…), from the episode's own texts and the
        // learner's list for this podcast: hints before recognition, corrections after.
        val names = run {
            val ep = dao.get(episodeId)
            val podcast = ep?.podcastId?.let { graph.db.podcastDao().get(it) }
            com.ascolta.app.data.Names.extract(
                listOf(podcast?.title, podcast?.author, ep?.title, ep?.description),
                extra = podcast?.names.orEmpty().lines(),
            )
        }
        if (names.isNotEmpty()) AppLog.i(TAG, "Name hints: ${names.size}")
        var engine: SpeechEngine? = null
        suspend fun engine(): SpeechEngine = engine ?: withContext(CoreDispatchers.recognition) {
            update(applicationContext.getString(R.string.status_loading_model), status.value.progress)
            SpeechEngine.load(applicationContext, model, graph.models, options).also { it.setNames(names) }
        }.also { engine = it }
        suspend fun releaseEngine() {
            val e = engine ?: return
            engine = null
            withContext(NonCancellable + CoreDispatchers.recognition) { e.close() }
            AppLog.i(TAG, "Model released while waiting")
        }
        // Marked until the first block is done: a native crash in between is noticed next time.
        var guardArmed = true
        CrashGuard.arm(applicationContext, model.id, episodeId, crashCount)
        engine()
        try {
            val job = coroutineContext[Job]
            val abortHandle = job?.invokeOnCompletion { engine?.abort() }
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
                                    // Never the same speech twice (block boundaries, recogniser loops).
                                    val result = SentenceMerge.resolve(t.segments, event.segment)
                                    val segment = when (result) {
                                        is SentenceMerge.Result.Add -> result.segment
                                        is SentenceMerge.Result.Replace -> result.segment
                                        SentenceMerge.Result.Drop -> {
                                            AppLog.i(TAG, "Dropped repeated sentence at ${event.segment.startMs / 1000}s: \"${event.segment.text.take(60)}\"")
                                            continue
                                        }
                                    }
                                    val translation = if (canTranslate) runCatching {
                                        withTimeoutOrNull(15_000) { graph.translation.translate(segment.text, language, target) }
                                    }.getOrNull() else null
                                    val translated = segment.copy(translation = translation)
                                    t = t.copy(
                                        segments = SentenceMerge.apply(
                                            t.segments,
                                            when (result) {
                                                is SentenceMerge.Result.Replace -> result.copy(segment = translated)
                                                else -> SentenceMerge.Result.Add(translated)
                                            },
                                        ),
                                        model = model.id,
                                        translationLanguage = if (canTranslate) target else t.translationLanguage,
                                    )
                                }
                                is WriterEvent.ReplaceSpan -> {
                                    val kept = t.segments.filter { it.startMs < event.span.startMs || it.startMs >= event.span.endMs }
                                    val added = event.segments.map { segment ->
                                        val translation = if (canTranslate) runCatching {
                                            withTimeoutOrNull(15_000) { graph.translation.translate(segment.text, language, target) }
                                        }.getOrNull() else null
                                        segment.copy(translation = translation)
                                    }
                                    t = t.copy(
                                        segments = (kept + added).sortedBy { it.startMs },
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
                    // detection split across all efficiency cores. Unless it is the end of the
                    // file, the block is trimmed to end in a pause, so no word is cut in two.
                    suspend fun prepare(span: Span): PreparedBlock {
                        val (decoded, firstSample) = withContext(CoreDispatchers.light) {
                            val ctx = coroutineContext
                            reader.read(span.startMs, span.endMs - span.startMs) { !ctx.isActive }
                        }
                        var samples = decoded
                        var speech = vadPath?.let { SpeechDetection.detect(it, samples, fine = model.engine.cutsAtPauses) }
                        val lengthMs = samples.size / 16L
                        val startMs = firstSample / 16
                        val endOfFile = startMs + lengthMs >= totalMs - 1_000 || lengthMs < span.endMs - span.startMs - 1_000
                        if (!endOfFile && lengthMs > 20_000) {
                            val cut = if (speech != null) BlockCut.atPause(speech, lengthMs)
                            else withContext(CoreDispatchers.light) { BlockCut.quietest(samples) }
                            if (cut != null && cut > lengthMs / 2) {
                                samples = samples.copyOf((cut * 16).toInt())
                                speech = speech?.mapNotNull { (s, e) -> if (s >= cut) null else s to minOf(e, cut) }
                            }
                        }
                        return PreparedBlock(Span(startMs, startMs + samples.size / 16L), samples, speech)
                    }

                    // What this loop has finished; kept here (not read back from the writer,
                    // which may still be busy translating) so no block is ever done twice.
                    var covered = initial.covered
                    suspend fun done(span: Span) {
                        covered = Coverage.add(covered, span)
                        events.send(WriterEvent.Done(span))
                    }

                    var prefetch: Pair<Span, Deferred<PreparedBlock>>? = null
                    var lastListenPos = -1L
                    var speedFactor: Float? = null
                    var buffered = false        // waiting: the zone ahead of the listener is transcribed
                    var waitingSince = 0L
                    var hidden = false          // paused because the app isn't on screen while listening
                    var jumpedToMs = -1L

                    while (true) {
                        if (isStopped) { stopped = true; break }
                        val spans = covered
                        if (Coverage.isComplete(spans, totalMs)) break

                        // Follow the listener when this episode is loaded in the player.
                        val player = graph.player.state.value
                        val following = player.episodeId == episodeId
                        val listening = following && (player.isPlaying || player.isBuffering)

                        // Listening with the phone locked or the app in the background: stop until
                        // the app is opened again, then restart right where the listener is.
                        if (listening && !AppVisibility.visible.value) {
                            if (!hidden) {
                                hidden = true
                                AppLog.i(TAG, "App not on screen while listening: transcription paused")
                                prefetch?.second?.cancel()
                                prefetch = null
                            }
                            update(applicationContext.getString(R.string.status_paused_listening), progressOf(latest.value))
                            AppVisibility.visible.first { it } // returns at once if visible meanwhile
                            continue
                        }
                        if (hidden) {
                            hidden = false
                            lastListenPos = -1 // treat as a jump: short first block at the current position
                            AppLog.i(TAG, "App back on screen: resuming at ${player.positionMs / 1000}s")
                        }

                        // Where the listener is in the timeline: the player's position when this
                        // episode is loaded, otherwise where they last stopped listening.
                        val positionMs = if (following) player.positionMs
                        else dao.get(episodeId)?.playbackPositionMs ?: 0L
                        if (lastListenPos < 0 || kotlin.math.abs(positionMs - lastListenPos) > 10_000) jumpedToMs = positionMs
                        lastListenPos = positionMs

                        // Buffer zone: only 20% of the episode's duration ahead of the listener.
                        val ahead = BufferPolicy.aheadMs(spans, positionMs, totalMs)
                        if (buffered && ahead < BufferPolicy.resumeBelowMs(totalMs, BLOCK_MS)) buffered = false
                        // Free version: 30 minutes of transcription a week; stop (keeping what's done) when used.
                        if (!graph.isPro && graph.limits.transcriptionLeftMs() <= 0) {
                            throw FreeLimitReached(applicationContext.getString(R.string.free_minutes_used))
                        }
                        val block = if (buffered) null else {
                            // Short first block right after starting or jumping, so text appears within seconds.
                            val nearJump = jumpedToMs >= 0 && Coverage.runEnd(spans, jumpedToMs) - jumpedToMs < FIRST_BLOCK_MS
                            BufferPolicy.nextBlock(spans, positionMs, totalMs, if (nearJump) FIRST_BLOCK_MS else BLOCK_MS)
                        }
                        if (block == null) {
                            buffered = true
                            if (waitingSince == 0L) {
                                waitingSince = System.currentTimeMillis()
                                prefetch?.second?.cancel()
                                prefetch = null
                                AppLog.i(
                                    TAG,
                                    "Zone full: ${ahead / 1000}s transcribed ahead of ${positionMs / 1000}s " +
                                        "(zone ${BufferPolicy.zoneMs(totalMs) / 1000}s); waiting",
                                )
                            }
                            update(applicationContext.getString(R.string.status_buffered_ahead, (ahead / 1000).toInt()), progressOf(latest.value))
                            // Free the model while waiting; step aside if another episode waits for it.
                            if (engine != null && System.currentTimeMillis() - waitingSince > 30_000) releaseEngine()
                            if (EngineLock.waiting.get() > 0) {
                                AppLog.i(TAG, "Zone full and another episode is waiting: stepping aside")
                                yielded = true
                                stopped = true
                                break
                            }
                            delay(1_000)
                            continue
                        }
                        waitingSince = 0L
                        val blockStarted = System.currentTimeMillis()
                        val ready = prefetch?.takeIf { it.first.startMs == block.startMs }?.second
                        if (ready == null) prefetch?.second?.cancel()
                        prefetch = null
                        val prepared = ready?.await() ?: prepare(block)
                        if (prepared.samples.isEmpty()) {
                            done(block) // past the decodable end of the file
                            continue
                        }
                        // Prepare the following block while this one is recognised.
                        val nextSpan = BufferPolicy.nextBlock(Coverage.add(spans, prepared.span), positionMs, totalMs, BLOCK_MS)
                        if (nextSpan != null && nextSpan.startMs >= prepared.span.endMs) prefetch = nextSpan to async { prepare(nextSpan) }

                        val previous = latest.value.segments.filter { it.endMs <= prepared.span.startMs + 500 }.takeLast(3)
                            .joinToString(" ") { it.text }.takeLast(200)
                        val prompt = com.ascolta.app.data.Names.prompt(names, previous)
                        update(transcribing, progressOf(latest.value))

                        // While recognising, watch the player: if the listener jumps somewhere this
                        // block doesn't help, or listens with the app closed, leave the block.
                        var abandoned = false
                        val watcher = launch {
                            while (isActive) {
                                delay(300)
                                val p = graph.player.state.value
                                val mine = p.episodeId == episodeId
                                val away = mine && (p.isPlaying || p.isBuffering) && !AppVisibility.visible.value
                                if (mine && (away || TranscriptionPlan.shouldAbandon(covered, p.positionMs, prepared.span))) {
                                    abandoned = true
                                    engine?.abort()
                                    break
                                }
                            }
                        }
                        val blockStartMs = prepared.span.startMs
                        val blockLenMs = prepared.span.endMs - prepared.span.startMs
                        var lastEnd = blockStartMs
                        var count = 0
                        val recognitionStarted = System.currentTimeMillis()
                        val recognizer = engine()
                        // Speech the detector missed (talk over music): long quiet stretches are checked too.
                        val regions = prepared.speech?.let { com.ascolta.app.whisper.SpeechDetection.fillGaps(it, blockLenMs) }
                        val rescuedMs = if (prepared.speech != null && regions != null) com.ascolta.app.whisper.SpeechDetection.gapMs(prepared.speech, regions) else 0L
                        // Parakeet can't be told the language: sentences it wrote in English are redone with Whisper.
                        val wrongLanguage = mutableListOf<Segment>()
                        recognizer.setDetectedSpeech(prepared.speech)
                        val finished = withContext(CoreDispatchers.recognition) {
                            recognizer.transcribe(
                                prepared.samples, regions, language, prompt,
                                onProgress = { percent ->
                                    update(transcribing, progressOf(latest.value) + blockLenMs * percent / 100f / totalMs)
                                },
                                onSegment = { seg ->
                                    val (text, fixed) = com.ascolta.app.data.Names.correct(seg.text.trim(), names)
                                    if (text.isNotEmpty() && !isHallucination(text) && !com.ascolta.app.data.Names.echoesPrompt(text, names)) {
                                        count++
                                        lastEnd = maxOf(lastEnd, seg.endMs + blockStartMs)
                                        val segment = Segment(
                                            seg.startMs + blockStartMs,
                                            seg.endMs + blockStartMs,
                                            text,
                                            words = cleanWords(seg.words, seg.startMs, seg.endMs)
                                                ?.map { w -> w.copy(startMs = w.startMs + blockStartMs, text = fixWord(w.text, fixed)) },
                                        )
                                        if (FIX_WRONG_LANGUAGE && model.engine == Engine.PARAKEET && com.ascolta.app.data.SentenceLanguage.isEnglishInstead(text, language, wordLists, names)) {
                                            wrongLanguage += segment
                                        } else {
                                            events.trySend(WriterEvent.Sentence(segment))
                                        }
                                    }
                                },
                            )
                        }
                        watcher.cancel()
                        if (wrongLanguage.isNotEmpty() && !isStopped) {
                            val redoStarted = System.currentTimeMillis()
                            // A conversation where many sentences slipped into English: the whole block is
                            // redone by Whisper (told the language), which keeps the exchange together.
                            val wholeBlock = finished && TranscriptionPlan.redoWholeBlock(wrongLanguage.size, count) &&
                                redoBlockInLanguage(prepared, blockStartMs, language, names, options)?.let { segments ->
                                    events.send(WriterEvent.ReplaceSpan(Span(blockStartMs, blockStartMs + blockLenMs), segments))
                                    AppLog.i(TAG, "Wrong language: ${wrongLanguage.size} of $count sentences; whole block redone with Whisper " +
                                        "(${segments.size} sentences) in ${(System.currentTimeMillis() - redoStarted) / 1000.0}s")
                                    true
                                } == true
                            if (!wholeBlock) {
                                val redone = redoInLanguage(wrongLanguage, prepared.samples, blockStartMs, language, names, options)
                                AppLog.i(TAG, "Wrong language: ${wrongLanguage.size} sentences, $redone redone with Whisper in ${(System.currentTimeMillis() - redoStarted) / 1000.0}s")
                                wrongLanguage.forEach { events.send(WriterEvent.Sentence(it)) }
                            }
                        }
                        if (rescuedMs > 0) AppLog.i(TAG, "Gap check: ${rescuedMs / 1000}s without detected speech also checked")
                        if (!finished) {
                            if (abandoned && !isStopped) {
                                // Keep what was recognised; the rest of this block is done later.
                                if (lastEnd > blockStartMs) done(Span(blockStartMs, lastEnd))
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
                        done(Span(blockStartMs, blockStartMs + blockLenMs))
                        if (!graph.isPro) graph.limits.addTranscribed(blockLenMs)
                        if (guardArmed) {
                            guardArmed = false
                            CrashGuard.clear(applicationContext)
                        }
                        // Speed (× real time) per model, to see what runs well on which phones.
                        com.ascolta.app.data.Analytics.track(
                            "Transcription.speed",
                            mapOf("model" to model.id, "language" to language, "threads" to threads.toString(), "gpu" to options.gpu.toString()),
                            value = factor.toDouble(),
                        )
                    }
                    prefetch?.second?.cancel()
                    events.close()
                    writer.join()
                }
            } finally {
                abortHandle?.dispose()
            }
        } finally {
            releaseEngine()
            fixer?.let { f -> fixer = null; fixerChecked = false; withContext(NonCancellable + CoreDispatchers.recognition) { f.close() } }
            withContext(NonCancellable + CoreDispatchers.light) { reader.close() }
        }
        if (stopped) return null
        val seconds = (System.currentTimeMillis() - started) / 1000
        AppLog.i(TAG, "Transcription finished in ${seconds}s for ${totalMs / 1000}s of audio")
        return latest.value.copy(complete = true, processedMs = totalMs, covered = listOf(Span(0, totalMs)))
    }

    /**
     * Word timings in order and inside their sentence, so the highlight moves forward
     * smoothly instead of jumping back or past the sentence.
     */
    /** Whisper for redoing wrong-language sentences: loaded once per job, freed at the end. */
    private var fixer: SpeechEngine? = null
    private var fixerChecked = false
    private val wordLists by lazy { com.ascolta.app.data.WordLists(applicationContext) }

    /**
     * Sentences Parakeet wrote in English while the episode is in [language]: their audio,
     * joined, is recognised again by Whisper forced to [language] in one pass (Whisper always
     * processes 30 s windows, so one call per sentence was very slow). Each Whisper sentence
     * goes back to the wrong sentence it overlaps; if Whisper hears English too (an ad, a
     * quote), the original stays. Uses Small, else Base; without either, the player suggests
     * downloading Base (never downloaded silently). Returns how many were redone.
     */
    /** Whisper Small, else Base, loaded once per job; null (and the download suggested) without either. */
    private suspend fun whisperFixer(options: EngineOptions): SpeechEngine? {
        if (!fixerChecked) {
            fixerChecked = true
            val model = listOf(SpeechModel.SMALL, SpeechModel.BASE).firstOrNull { graph.models.isDownloaded(it) }
            if (model != null) fixer = withContext(CoreDispatchers.recognition) {
                SpeechEngine.load(applicationContext, model, graph.models, options.copy(gpu = false))
            }
        }
        return fixer ?: run {
            fixerChecked = false // check again next block (the learner may download one meanwhile)
            graph.settings.languageFixSuggested.set(true)
            null
        }
    }

    /**
     * The whole block recognised again by Whisper forced to [language] (one pass over its
     * detected speech). Returns its sentences, or null when Whisper isn't on the phone, heard
     * nothing, or heard mostly English too (an English ad: Parakeet's text then stays).
     */
    private suspend fun redoBlockInLanguage(
        prepared: PreparedBlock,
        blockStartMs: Long,
        language: String,
        names: List<String>,
        options: EngineOptions,
    ): List<Segment>? {
        val whisper = whisperFixer(options) ?: return null
        val parts = mutableListOf<com.ascolta.app.whisper.WhisperSegment>()
        withContext(CoreDispatchers.recognition) {
            whisper.transcribe(prepared.samples, prepared.speech, language, Names.prompt(names, null), {}, { parts += it })
        }
        val segments = parts.mapNotNull { p ->
            val (text, fixed) = Names.correct(p.text.trim(), names)
            if (text.isEmpty() || isHallucination(text) || Names.echoesPrompt(text, names)) return@mapNotNull null
            Segment(
                p.startMs + blockStartMs, p.endMs + blockStartMs, text,
                words = cleanWords(p.words, p.startMs, p.endMs)?.map { w -> w.copy(startMs = w.startMs + blockStartMs, text = fixWord(w.text, fixed)) },
            )
        }
        if (segments.isEmpty()) return null
        val english = segments.count { com.ascolta.app.data.SentenceLanguage.isEnglishInstead(it.text, language, wordLists, names) }
        return segments.takeIf { english * 2 < segments.size }
    }

    private suspend fun redoInLanguage(
        wrong: MutableList<Segment>,
        samples: FloatArray,
        blockStartMs: Long,
        language: String,
        names: List<String>,
        options: EngineOptions,
    ): Int {
        val whisper = whisperFixer(options) ?: return 0
        val pad = 250L
        val lengthMs = samples.size / 16L
        val regions = wrong.map { (it.startMs - blockStartMs - pad).coerceAtLeast(0) to (it.endMs - blockStartMs + pad).coerceAtMost(lengthMs) }
            .filter { it.second > it.first }
        if (regions.isEmpty()) return 0
        val parts = mutableListOf<com.ascolta.app.whisper.WhisperSegment>()
        withContext(CoreDispatchers.recognition) {
            whisper.transcribe(samples, com.ascolta.app.whisper.SpeechDetection.merge(regions, 0), language, Names.prompt(names, null), {}, { parts += it })
        }
        val texts = TranscriptionPlan.assignByTime(
            wrong.map { (it.startMs - blockStartMs) to (it.endMs - blockStartMs) },
            parts.map { Triple(it.startMs, it.endMs, it.text.trim()) },
        )
        var redone = 0
        wrong.indices.forEach { i ->
            val text = texts[i].trim()
            if (text.isEmpty() || isHallucination(text) ||
                com.ascolta.app.data.SentenceLanguage.isEnglishInstead(text, language, wordLists, names)) return@forEach
            val words = parts.filter { p -> (p.startMs + p.endMs) / 2 in (wrong[i].startMs - blockStartMs - pad)..(wrong[i].endMs - blockStartMs + pad) }
                .flatMap { it.words.orEmpty() }.map { it.copy(startMs = it.startMs + blockStartMs) }.takeIf { it.isNotEmpty() }
            wrong[i] = wrong[i].copy(text = Names.correct(text, names).first, words = words)
            redone++
        }
        return redone
    }

    /** A timed word with the same name correction as its sentence ("Selenski," → "Zelensky,"). */
    private fun fixWord(word: String, fixed: Map<String, String>): String {
        if (fixed.isEmpty()) return word
        val core = word.trim().trim { !it.isLetterOrDigit() }
        val to = fixed[core] ?: return word
        return word.replace(core, to)
    }

    private fun cleanWords(words: List<com.ascolta.app.data.Word>?, startMs: Long, endMs: Long): List<com.ascolta.app.data.Word>? {
        if (words.isNullOrEmpty()) return words
        var last = startMs
        return words.map { w ->
            val t = w.startMs.coerceIn(last, maxOf(last, endMs - 1))
            last = t
            w.copy(startMs = t)
        }
    }

    private class PreparedBlock(val span: Span, val samples: FloatArray, val speech: List<Pair<Long, Long>>?)

    private sealed interface WriterEvent {
        data class Sentence(val segment: Segment) : WriterEvent
        /** The sentences starting in [span] are replaced by [segments] (a block redone by Whisper). */
        data class ReplaceSpan(val span: Span, val segments: List<Segment>) : WriterEvent
        /** This stretch is fully transcribed. */
        data class Done(val span: Span) : WriterEvent
    }

    /** The free version's weekly transcription time is used up. */
    class FreeLimitReached(message: String) : Exception(message)

    /** A model the learner hasn't agreed to download yet: the transcription waits for them. */
    class ModelNeeded(message: String) : Exception(message)

    private suspend fun ensureModel(model: SpeechModel) {
        if (graph.models.isDownloaded(model)) return
        // Never download a model without the learner having seen its size and agreed.
        if (!graph.settings.isApproved("model:${model.id}")) {
            // Agreed and downloading from Settings: wait for it.
            if (WorkScheduler.isActive(applicationContext, WorkScheduler.modelWorkName(model))) {
                update(applicationContext.getString(R.string.status_downloading_model, model.label), status.value.progress)
                while (WorkScheduler.isActive(applicationContext, WorkScheduler.modelWorkName(model))) delay(2_000)
                if (graph.models.isDownloaded(model)) return
            }
            throw ModelNeeded(applicationContext.getString(R.string.model_needed, "${model.label} · ${com.ascolta.app.data.DownloadCost.formatSize(graph.models.missingBytes(model))}"))
        }
        val label = applicationContext.getString(R.string.status_downloading_model, model.label)
        update(label, 0f)
        AppLog.i(TAG, "Downloading model ${model.id}")
        graph.models.download(model) { written, total ->
            update("$label · ${written / 1_000_000} / ${total / 1_000_000} MB", written.toFloat() / total.coerceAtLeast(1))
        }
        AppLog.i(TAG, "Model ${model.id} downloaded")
    }

    private suspend fun translateRemaining(transcript: Transcript, target: String): Transcript {
        if (!graph.settings.translate.value) return transcript // transcript only
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
        /**
         * Redo with Whisper the sentences Parakeet writes in English (1.1.1–1.1.4). Off: it made
         * transcription noticeably slower on conversations, and the learner preferred speed.
         */
        const val FIX_WRONG_LANGUAGE = false

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
            // French, Spanish, German, Portuguese subtitle credits Whisper writes over music.
            "sous-titrage st'", "sous-titrage société radio-canada", "sous-titres réalisés par", "sous-titres par",
            "merci d'avoir regardé", "abonnez-vous à la chaîne",
            "subtítulos realizados por", "subtítulos por la comunidad", "gracias por ver",
            "untertitel im auftrag", "untertitel der amara", "untertitelung des zdf", "danke fürs zuschauen",
            "legendas pela comunidade", "obrigado por assistir",
        )

        fun isHallucination(text: String): Boolean {
            val lower = text.lowercase()
            return HALLUCINATIONS.any { it in lower } || lower.matches(Regex("""^[\s\p{Punct}♪]*$"""))
        }
    }
}
