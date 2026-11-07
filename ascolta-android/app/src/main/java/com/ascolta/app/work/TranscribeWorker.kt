package com.ascolta.app.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ascolta.app.data.DownloadState
import com.ascolta.app.data.EpisodeEntity
import com.ascolta.app.data.Segment
import com.ascolta.app.data.Transcript
import com.ascolta.app.data.TranscriptState
import com.ascolta.app.graph
import com.ascolta.app.net.FeedTranscriptParser
import com.ascolta.app.net.fetchString
import com.ascolta.app.whisper.AudioDecoder
import com.ascolta.app.whisper.WhisperContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Produces a transcript (+ translation) for one episode, entirely on the phone:
 *  1. If the podcast publishes a timed transcript, use it.
 *  2. Otherwise decode the audio and run whisper.cpp on it in 5-minute chunks.
 *     Progress is saved after every chunk, so an interrupted job resumes where it stopped
 *     and the partial transcript is readable while the rest is still being processed.
 *  3. Translate each sentence with ML Kit.
 */
class TranscribeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val graph = context.graph
    private val dao = graph.db.episodeDao()
    private var episodeId = -1L
    private lateinit var title: String

    private data class Status(val text: String, val progress: Float)
    private val status = MutableStateFlow(Status("Starting", 0f))

    override suspend fun doWork(): Result {
        episodeId = inputData.getLong(WorkScheduler.KEY_EPISODE_ID, -1)
        val episode = dao.get(episodeId) ?: return Result.success()
        if (episode.transcriptState == TranscriptState.DONE || episode.transcriptState == TranscriptState.NONE) {
            return Result.success() // already done, or cancelled by the user
        }
        title = episode.title
        val language = episode.transcriptLanguage ?: graph.settings.learningLanguage.value
        val target = graph.settings.nativeLanguage.value

        return try {
            coroutineScope {
                val reporter = launch { reportProgress() }
                try {
                    run(episode, language, target)
                } finally {
                    reporter.cancel()
                }
            }
        } catch (e: CancellationException) {
            // Stopped by the system (or the user); the next run resumes from saved progress.
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Transcription failed", e)
            dao.setTranscript(episodeId, TranscriptState.FAILED, 0f, null, e.message ?: e.javaClass.simpleName)
            // Report success so the rest of the transcription queue keeps going.
            Result.success()
        }
    }

    private suspend fun run(episode: EpisodeEntity, language: String, target: String): Result {
        var transcript = graph.transcripts.load(episodeId)

        if (transcript == null && episode.feedTranscriptUrl != null) {
            update("Fetching published transcript", 0f)
            transcript = runCatching { fetchFeedTranscript(episode, language) }
                .onFailure { Log.w(TAG, "Feed transcript unusable", it) }
                .getOrNull()
            transcript?.let { graph.transcripts.save(episodeId, it) }
        }

        if (transcript == null || !transcript.complete) {
            val path = episode.localPath
            if (path == null || !File(path).exists()) {
                // Needs the audio first; the download queues this job again when it finishes.
                dao.setTranscript(episodeId, TranscriptState.QUEUED, 0f, "Waiting for download")
                dao.setDownload(episodeId, DownloadState.QUEUED, 0f)
                WorkScheduler.download(applicationContext, episodeId, transcribeAfter = true, manual = true)
                return Result.success()
            }
            transcript = transcribeAudio(path, language, target, transcript) ?: return Result.retry()
        }

        transcript = translateRemaining(transcript, target)
        graph.transcripts.save(episodeId, transcript)
        dao.setTranscript(episodeId, TranscriptState.DONE, 1f, null)
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

    /** Returns null if stopped before finishing (progress so far is saved). */
    private suspend fun transcribeAudio(
        path: String,
        language: String,
        target: String,
        existing: Transcript?,
    ): Transcript? {
        val model = graph.settings.model
        if (!graph.models.isDownloaded(model)) {
            update("Downloading speech model (${model.label})", 0f)
            graph.models.download(model) { update("Downloading speech model (${model.label})", it.coerceAtLeast(0f)) }
        }

        var transcript = existing?.takeIf { it.source == Transcript.SOURCE_WHISPER && it.language == language }
            ?: Transcript(language, Transcript.SOURCE_WHISPER, emptyList(), model = model.id)

        val canTranslate = graph.translation.isSupported(language, target) && runCatching {
            update("Preparing translation", 0f)
            graph.translation.ensureModel(language, target, requireWifi = graph.settings.wifiOnly.value)
        }.isSuccess

        val decoder = AudioDecoder(path)
        val totalMs = decoder.durationMs().coerceAtLeast(1)
        var stopped = false

        update("Loading speech model", transcript.processedMs.toFloat() / totalMs)
        WhisperContext.load(graph.models.file(model).path).use { whisper ->
            val job = coroutineContext[Job]
            val abortHandle = job?.invokeOnCompletion { whisper.abort() }
            try {
                decoder.decode(CHUNK_SECONDS * AudioDecoder.SAMPLE_RATE, transcript.processedMs * 16) { samples, startSample ->
                    if (isStopped) {
                        stopped = true
                        return@decode false
                    }
                    val chunkStartMs = startSample / 16
                    val chunkMs = samples.size / 16L
                    val prompt = transcript.segments.takeLast(3).joinToString(" ") { it.text }.takeLast(200)

                    val result = whisper.transcribe(samples, language, prompt) { percent ->
                        update("Transcribing", (chunkStartMs + chunkMs * percent / 100f) / totalMs)
                    }
                    if (result == null) {
                        stopped = true
                        return@decode false
                    }

                    var newSegments = result
                        .map { Segment(it.startMs + chunkStartMs, it.endMs + chunkStartMs, it.text.trim()) }
                        .filter { it.text.isNotEmpty() && !isHallucination(it.text) }
                    if (canTranslate) {
                        update("Translating", (chunkStartMs + chunkMs).toFloat() / totalMs)
                        newSegments = newSegments.map { s ->
                            s.copy(translation = runCatching { graph.translation.translate(s.text, language, target) }.getOrNull())
                        }
                    }
                    transcript = transcript.copy(
                        segments = transcript.segments + newSegments,
                        processedMs = chunkStartMs + chunkMs,
                        translationLanguage = if (canTranslate) target else transcript.translationLanguage,
                    )
                    graph.transcripts.save(episodeId, transcript)
                    true
                }
            } finally {
                abortHandle?.dispose()
            }
        }
        if (stopped) return null
        return transcript.copy(complete = true)
    }

    private suspend fun translateRemaining(transcript: Transcript, target: String): Transcript {
        if (!graph.translation.isSupported(transcript.language, target)) return transcript
        val stale = transcript.translationLanguage != null && transcript.translationLanguage != target
        val missing = transcript.segments.count { it.translation == null || stale }
        if (missing == 0) return transcript
        return try {
            graph.translation.ensureModel(transcript.language, target, requireWifi = graph.settings.wifiOnly.value)
            var done = 0
            val segments = transcript.segments.map { s ->
                if (s.translation != null && !stale) s
                else {
                    update("Translating", done++.toFloat() / missing)
                    s.copy(translation = graph.translation.translate(s.text, transcript.language, target))
                }
            }
            transcript.copy(segments = segments, translationLanguage = target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The translation model may not be downloadable right now (e.g. not on Wi-Fi).
            // The transcript is still useful; translations are filled in later from the player.
            Log.w(TAG, "Translation unavailable", e)
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
        private const val TAG = "TranscribeWorker"
        private const val NOTIFICATION_ID = 901
        private const val CHUNK_SECONDS = 5 * 60

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
