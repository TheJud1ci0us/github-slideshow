package com.ascolta.app.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.ascolta.app.R
import com.ascolta.app.net.OpmlParser
import com.ascolta.app.net.ParsedEpisode
import com.ascolta.app.net.ParsedFeed
import com.ascolta.app.net.RssParser
import com.ascolta.app.translate.TranslationService
import com.ascolta.app.work.WorkScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

class PodcastRepository(
    private val context: Context,
    db: AppDatabase,
    private val http: OkHttpClient,
    private val settings: Settings,
    private val transcripts: TranscriptStore,
    private val translation: TranslationService,
) {
    private val podcastDao = db.podcastDao()
    private val episodeDao = db.episodeDao()
    private val vocabDao = db.vocabDao()

    val podcasts = podcastDao.observeWithStats()
    val subscribedFeedUrls = podcastDao.observeFeedUrls()
    val importedCount = episodeDao.observeImportedCount()
    val activeEpisodes = episodeDao.observeActive()
    val recentlyPlayed = episodeDao.observeRecentlyPlayed(5)
    val vocab = vocabDao.observeAll()

    fun podcast(id: Long) = podcastDao.observe(id)
    fun episodes(podcastId: Long?) =
        if (podcastId == null) episodeDao.observeImported() else episodeDao.observeForPodcast(podcastId)
    fun episode(id: Long) = episodeDao.observe(id)
    suspend fun getEpisode(id: Long) = episodeDao.get(id)
    suspend fun getPodcast(id: Long) = podcastDao.get(id)

    // ---- Feeds -------------------------------------------------------------

    suspend fun fetchFeed(url: String): ParsedFeed = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            RssParser.parse(response.body!!.byteStream())
        }
    }

    /** Subscribes to a feed and returns the podcast id. */
    suspend fun subscribe(feedUrl: String, downloadLatest: Boolean = true): Long {
        podcastDao.getByFeedUrl(feedUrl)?.let { return it.id }
        val feed = fetchFeed(feedUrl)
        val now = System.currentTimeMillis()
        val podcastId = podcastDao.insert(
            PodcastEntity(
                feedUrl = feedUrl,
                title = feed.title,
                author = feed.author,
                description = feed.description,
                imageUrl = feed.imageUrl,
                language = feed.language,
                autoDownload = settings.autoDownloadNew.value,
                subscribedAt = now,
                lastRefreshedAt = now,
            ),
        )
        if (podcastId <= 0) return podcastDao.getByFeedUrl(feedUrl)!!.id
        episodeDao.insertAll(feed.episodes.map { it.toEntity(podcastId) })

        // Get the newest episode ready straight away so there's something to listen to.
        if (downloadLatest && settings.autoDownloadNew.value) {
            episodeDao.latest(podcastId)?.let { download(it.id, manual = false) }
        }
        return podcastId
    }

    suspend fun unsubscribe(podcastId: Long) {
        episodeDao.forPodcast(podcastId).forEach { deleteFiles(it) }
        podcastDao.delete(podcastId)
    }

    suspend fun setAutoDownload(podcastId: Long, enabled: Boolean) {
        podcastDao.get(podcastId)?.let { podcastDao.update(it.copy(autoDownload = enabled)) }
    }

    /** Checks every feed for new episodes; new ones are downloaded (and transcribed) automatically. */
    suspend fun refreshAll(): Int {
        var newEpisodes = 0
        for (podcast in podcastDao.all()) {
            newEpisodes += runCatching { refresh(podcast) }.getOrDefault(0)
        }
        return newEpisodes
    }

    suspend fun refresh(podcast: PodcastEntity): Int {
        val feed = fetchFeed(podcast.feedUrl)
        val ids = episodeDao.insertAll(feed.episodes.map { it.toEntity(podcast.id) })
        // Feeds sometimes add transcripts to episodes after publishing.
        feed.episodes.filter { it.transcriptUrl != null }.forEach {
            episodeDao.fillFeedTranscript(podcast.id, it.guid, it.transcriptUrl!!, it.transcriptType)
        }
        podcastDao.update(
            podcast.copy(
                title = feed.title,
                author = feed.author ?: podcast.author,
                imageUrl = feed.imageUrl ?: podcast.imageUrl,
                lastRefreshedAt = System.currentTimeMillis(),
            ),
        )
        val newIds = ids.filter { it > 0 }
        if (podcast.autoDownload) {
            // Only episodes published since subscribing, so an old back catalogue isn't downloaded.
            val cutoff = podcast.subscribedAt - 7L * 24 * 3600 * 1000
            newIds.mapNotNull { episodeDao.get(it) }
                .filter { it.pubDate == 0L || it.pubDate >= cutoff }
                .sortedByDescending { it.pubDate }
                .take(3)
                .forEach { download(it.id, manual = false) }
        }
        return newIds.size
    }

    private fun ParsedEpisode.toEntity(podcastId: Long) = EpisodeEntity(
        podcastId = podcastId,
        guid = guid,
        title = title,
        description = description,
        audioUrl = audioUrl,
        mimeType = mimeType,
        pubDate = pubDate,
        durationSec = durationSec,
        imageUrl = imageUrl,
        feedTranscriptUrl = transcriptUrl,
        feedTranscriptType = transcriptType,
    )

    // ---- Imports -----------------------------------------------------------

    /** Copies an audio file into the app and queues it for transcription. */
    suspend fun importAudio(uri: Uri): Long = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name = "Imported audio"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { name = it }
        }
        val ext = name.substringAfterLast('.', "").takeIf { it.length in 2..5 } ?: "audio"
        val dir = File(context.filesDir, "imported").apply { mkdirs() }
        val dest = File(dir, "${System.currentTimeMillis()}.$ext")
        resolver.openInputStream(uri)!!.use { input -> dest.outputStream().use { input.copyTo(it) } }

        val durationSec = runCatching {
            MediaMetadataRetriever().run {
                setDataSource(dest.path)
                val ms = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                release()
                ms / 1000
            }
        }.getOrDefault(0)

        val id = episodeDao.insert(
            EpisodeEntity(
                podcastId = null,
                guid = dest.name,
                title = name.substringBeforeLast('.'),
                localPath = dest.path,
                downloadState = DownloadState.DONE,
                downloadProgress = 1f,
                pubDate = System.currentTimeMillis(),
                durationSec = durationSec,
            ),
        )
        transcribe(id, manual = true)
        id
    }

    /** Subscribes to every feed in an OPML file. Returns how many were added. */
    suspend fun importOpml(uri: Uri): Int {
        val urls = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)!!.use { OpmlParser.parse(it) }
        }
        var added = 0
        for (url in urls) {
            if (runCatching { subscribe(url, downloadLatest = false) }.isSuccess) added++
        }
        return added
    }

    // ---- Downloads & transcription ------------------------------------------

    suspend fun download(episodeId: Long, manual: Boolean = true) {
        val episode = episodeDao.get(episodeId) ?: return
        if (episode.downloadState in listOf(DownloadState.QUEUED, DownloadState.DOWNLOADING, DownloadState.DONE)) return
        episodeDao.setDownload(episodeId, DownloadState.QUEUED, 0f)
        WorkScheduler.download(context, episodeId, transcribeAfter = settings.autoTranscribe.value || manual, manual = manual)
    }

    suspend fun transcribe(episodeId: Long, manual: Boolean = true) {
        val episode = episodeDao.get(episodeId) ?: return
        if (episode.transcriptState in listOf(TranscriptState.QUEUED, TranscriptState.RUNNING, TranscriptState.DONE)) return
        val language = podcastDao.get(episode.podcastId ?: -1)?.language
            ?.takeIf { lang -> LANGUAGES.any { it.code == lang } && lang != settings.nativeLanguage.value }
            ?: settings.learningLanguage.value
        episodeDao.setTranscriptLanguage(episodeId, language)
        episodeDao.setTranscript(episodeId, TranscriptState.QUEUED, episode.transcriptProgress, context.getString(R.string.status_waiting))
        WorkScheduler.transcribe(context, episodeId, manual)
    }

    /**
     * Stops (or removes from the queue) this episode's transcription, and its download if
     * the transcription was waiting for it. What was already transcribed is kept, so
     * "Continue" resumes from there.
     */
    suspend fun stopTranscription(episodeId: Long) {
        AppLog.i("Repo", "Stop transcription #$episodeId")
        val episode = episodeDao.get(episodeId) ?: return
        WorkScheduler.cancelTranscription(context, episodeId)
        val waitingForDownload = episode.downloadState == DownloadState.QUEUED || episode.downloadState == DownloadState.DOWNLOADING
        if (waitingForDownload) WorkScheduler.cancelDownload(context, episodeId)
        // Give the running job a moment to notice, so it doesn't write "running" after us.
        withTimeoutOrNull(5_000) {
            while (WorkScheduler.isActive(context, WorkScheduler.transcribeWorkName(episodeId))) kotlinx.coroutines.delay(200)
        }
        if (waitingForDownload) episodeDao.setDownload(episodeId, DownloadState.NONE, 0f)
        val progress = transcripts.load(episodeId)?.let { t ->
            if (episode.durationSec > 0) (t.processedMs / 1000f / episode.durationSec).coerceIn(0f, 1f) else episode.transcriptProgress
        } ?: 0f
        episodeDao.setTranscript(episodeId, TranscriptState.NONE, progress, null)
    }

    /** Cancels a queued or running episode download. */
    suspend fun cancelDownload(episodeId: Long) {
        val episode = episodeDao.get(episodeId) ?: return
        WorkScheduler.cancelDownload(context, episodeId)
        if (episode.transcriptState == TranscriptState.QUEUED) episodeDao.setTranscript(episodeId, TranscriptState.NONE, 0f, null)
        episodeDao.setDownload(episodeId, DownloadState.NONE, 0f)
    }

    /** Stops whatever is happening for this episode and starts its transcription again (keeps saved progress). */
    suspend fun restartTranscription(episodeId: Long) {
        AppLog.i("Repo", "Restart transcription #$episodeId")
        WorkScheduler.cancelTranscription(context, episodeId)
        episodeDao.setTranscript(episodeId, TranscriptState.NONE, 0f, null)
        val episode = episodeDao.get(episodeId) ?: return
        if (episode.downloadState != DownloadState.DONE && episode.podcastId != null) {
            episodeDao.setDownload(episodeId, DownloadState.NONE, 0f)
        }
        transcribe(episodeId, manual = true)
    }

    /**
     * After the app was killed or the phone restarted, some jobs may be marked as
     * running although nothing is scheduled. Re-queue those.
     */
    suspend fun recoverStuckWork() {
        for (episode in episodeDao.activeList()) {
            val transcribing = episode.transcriptState == TranscriptState.QUEUED || episode.transcriptState == TranscriptState.RUNNING
            val downloading = episode.downloadState == DownloadState.QUEUED || episode.downloadState == DownloadState.DOWNLOADING
            if (downloading && !WorkScheduler.isActive(context, WorkScheduler.downloadWorkName(episode.id))) {
                AppLog.i("Repo", "Recovering download #${episode.id}")
                WorkScheduler.download(context, episode.id, transcribeAfter = settings.autoTranscribe.value, manual = true)
            }
            if (transcribing && !downloading && !WorkScheduler.isActive(context, WorkScheduler.transcribeWorkName(episode.id))) {
                AppLog.i("Repo", "Recovering transcription #${episode.id}")
                episodeDao.setTranscript(episode.id, TranscriptState.QUEUED, episode.transcriptProgress, context.getString(R.string.status_resuming))
                WorkScheduler.transcribe(context, episode.id, manual = true)
            }
        }
    }

    /** Throws away the transcript and generates it again (e.g. after switching to a better model). */
    suspend fun retranscribe(episodeId: Long) {
        transcripts.delete(episodeId)
        episodeDao.setTranscript(episodeId, TranscriptState.NONE, 0f, null)
        transcribe(episodeId, manual = true)
    }

    suspend fun deleteDownload(episodeId: Long) {
        val episode = episodeDao.get(episodeId) ?: return
        if (episode.podcastId == null) {
            deleteFiles(episode)
            episodeDao.delete(episodeId)
            return
        }
        episode.localPath?.let { File(it).delete() }
        episodeDao.setLocalPath(episodeId, null, DownloadState.NONE)
        episodeDao.setDownload(episodeId, DownloadState.NONE, 0f)
    }

    private fun deleteFiles(episode: EpisodeEntity) {
        episode.localPath?.let { File(it).delete() }
        transcripts.delete(episode.id)
    }

    suspend fun savePlayback(episodeId: Long, positionMs: Long) =
        episodeDao.setPlayback(episodeId, positionMs, System.currentTimeMillis())

    // ---- Transcripts & translation ------------------------------------------

    suspend fun loadTranscript(episodeId: Long) = transcripts.load(episodeId)

    /**
     * Fills in missing translations (or redoes them if the native language changed).
     * Returns the updated transcript, or null if translation isn't possible right now.
     */
    suspend fun translateTranscript(episodeId: Long): Transcript? {
        val transcript = transcripts.load(episodeId) ?: return null
        val target = settings.nativeLanguage.value
        if (!translation.isSupported(transcript.language, target)) return null
        val stale = transcript.translationLanguage != null && transcript.translationLanguage != target
        if (!stale && transcript.segments.all { it.translation != null }) return transcript
        withTimeoutOrNull(3 * 60_000L) { translation.ensureModel(transcript.language, target, requireWifi = false) }
            ?: throw IOException("the translation model couldn't be downloaded. Check your connection and try again.")
        val segments = transcript.segments.map { segment ->
            if (segment.translation != null && !stale) segment
            else segment.copy(translation = translation.translate(segment.text, transcript.language, target))
        }
        // Re-read in case transcription appended more segments meanwhile.
        val latest = transcripts.load(episodeId) ?: transcript
        val merged = latest.copy(
            segments = segments + latest.segments.drop(segments.size),
            translationLanguage = target,
        )
        transcripts.save(episodeId, merged)
        return merged
    }

    suspend fun translateWord(text: String, source: String): String {
        val target = settings.nativeLanguage.value
        return withTimeoutOrNull(60_000L) {
            translation.ensureModel(source, target, requireWifi = false)
            translation.translate(text, source, target)
        } ?: throw IOException("translation model not available yet")
    }

    // ---- Vocabulary --------------------------------------------------------

    suspend fun saveWord(entry: VocabEntity): Boolean {
        if (vocabDao.count(entry.word, entry.language) > 0) return false
        vocabDao.insert(entry)
        return true
    }

    suspend fun deleteWord(id: Long) = vocabDao.delete(id)

    /** Tab-separated export that Anki (and most flashcard apps) can import. */
    suspend fun exportVocabTsv(): String = vocabDao.all().joinToString("\n") { v ->
        listOf(v.word, v.translation, v.sentence.orEmpty(), v.sentenceTranslation.orEmpty())
            .joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') }
    }
}
