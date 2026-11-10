@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

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
import kotlinx.coroutines.flow.flatMapLatest
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

    // Each learning language has its own library, words and flashcards: these follow the
    // language currently chosen in the flag bar.
    private val current = settings.learningLanguage.flow
    val podcasts = current.flatMapLatest { podcastDao.observeWithStats(it) }
    val subscribedFeedUrls = podcastDao.observeFeedUrls()
    val importedCount = current.flatMapLatest { episodeDao.observeImportedCount(it) }
    val activeEpisodes = episodeDao.observeActive()
    val recentlyPlayed = current.flatMapLatest { episodeDao.observeRecentlyPlayed(it, 5) }
    val vocab = current.flatMapLatest { vocabDao.observeAll(it) }
    fun dueCards(now: Long) = current.flatMapLatest { vocabDao.observeDueCount(it, now) }
    fun podcastCount(language: String) = podcastDao.observeCount(language)

    fun podcast(id: Long) = podcastDao.observe(id)
    fun episodes(podcastId: Long?) =
        if (podcastId == null) current.flatMapLatest { episodeDao.observeImported(it) } else episodeDao.observeForPodcast(podcastId)

    /**
     * The library a podcast goes into: its own language when that is one being learned,
     * otherwise the language currently selected.
     */
    private fun libraryLanguageFor(feedLanguage: String?): String {
        val code = feedLanguage?.trim()?.lowercase()?.take(2)
        return code?.takeIf { it in settings.learningList() } ?: settings.learningLanguage.value
    }

    /** Podcasts from before per-language libraries get one (once). */
    suspend fun assignLibraryLanguages() {
        podcastDao.all().filter { it.libraryLanguage == null }.forEach {
            podcastDao.setLibraryLanguage(it.id, libraryLanguageFor(it.language))
        }
    }

    suspend fun setFavourite(podcastId: Long, favourite: Boolean) {
        podcastDao.setFavourite(podcastId, favourite)
        Analytics.track(if (favourite) "Podcast.favourited" else "Podcast.unfavourited")
    }

    /** Moves a podcast to another language's library. */
    suspend fun moveToLanguage(podcastId: Long, language: String) {
        Analytics.track("Podcast.movedLanguage", mapOf("to" to language))
        if (language !in settings.learningList()) settings.setLearningList(settings.learningList() + language)
        podcastDao.setLibraryLanguage(podcastId, language)
    }
    fun episode(id: Long) = episodeDao.observe(id)
    suspend fun getEpisode(id: Long) = episodeDao.get(id)
    suspend fun getPodcast(id: Long) = podcastDao.get(id)

    // ---- Feeds -------------------------------------------------------------

    suspend fun fetchFeed(url: String): ParsedFeed = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            // Gone or refused: say so plainly (Apple keeps listing some dead feeds).
            if (response.code in 400..499) throw IOException(context.getString(R.string.feed_gone, response.code))
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
                libraryLanguage = libraryLanguageFor(feed.language),
                autoDownload = settings.autoDownloadNew.value,
                subscribedAt = now,
                lastRefreshedAt = now,
            ),
        )
        if (podcastId <= 0) return podcastDao.getByFeedUrl(feedUrl)!!.id
        episodeDao.insertAll(feed.episodes.map { it.toEntity(podcastId) })
        // Only the podcast's language, never its name or address.
        Analytics.track("Podcast.followed", mapOf("podcastLanguage" to (feed.language?.take(2) ?: "?"), "library" to libraryLanguageFor(feed.language)))

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
        // New episodes of a favourite: a notification (only for recent ones, not a back catalogue).
        if (podcast.favourite && settings.notifyFavourites.value && newIds.isNotEmpty()) {
            val recent = newIds.mapNotNull { episodeDao.get(it) }
                .filter { it.pubDate == 0L || it.pubDate >= System.currentTimeMillis() - 7L * 24 * 3600 * 1000 }
                .sortedByDescending { it.pubDate }
            if (recent.isNotEmpty()) com.ascolta.app.work.Notifications.newEpisodes(context, podcast, recent)
        }
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
        // Downloading doesn't start a transcription: that's chosen per episode (Transcribe button)
        // unless automatic transcription is switched on in Settings.
        WorkScheduler.download(context, episodeId, transcribeAfter = settings.autoTranscribe.value, manual = manual)
        if (manual && WorkScheduler.blockedByNetworkMode(context)) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                android.widget.Toast.makeText(context, context.getString(R.string.waiting_for_wifi), android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Queues this episode's transcription. With [takeOver] (a Transcribe button), any other
     * episode being transcribed or waiting is stopped first (its progress is kept, so
     * "Continue" resumes it) and this one starts right away instead of waiting its turn.
     */
    suspend fun transcribe(episodeId: Long, manual: Boolean = true, takeOver: Boolean = manual) {
        Analytics.track("Transcription.requested", mapOf("manual" to manual.toString()))
        val episode = episodeDao.get(episodeId) ?: return
        // Already waiting its turn: a Transcribe press moves it to the front.
        if (episode.transcriptState == TranscriptState.QUEUED && takeOver) {
            prioritize(episodeId)
            return
        }
        if (episode.transcriptState in listOf(TranscriptState.QUEUED, TranscriptState.RUNNING, TranscriptState.DONE)) return
        if (takeOver) stopOthers(episodeId)
        val podcast = podcastDao.get(episode.podcastId ?: -1)
        val language = podcast?.libraryLanguage
            ?: podcast?.language?.lowercase()?.take(2)?.takeIf { lang -> LANGUAGES.any { it.code == lang } && lang != settings.nativeLanguage.value }
            ?: episode.transcriptLanguage
            ?: settings.learningLanguage.value
        episodeDao.setTranscriptLanguage(episodeId, language)
        episodeDao.setTranscript(episodeId, TranscriptState.QUEUED, episode.transcriptProgress, context.getString(R.string.status_waiting))
        WorkScheduler.transcribe(context, episodeId, manual)
    }

    /** Stops every other queued or running transcription (their progress is kept). */
    private suspend fun stopOthers(episodeId: Long) {
        val others = episodeDao.activeList().filter {
            it.id != episodeId && (it.transcriptState == TranscriptState.QUEUED || it.transcriptState == TranscriptState.RUNNING)
        }
        for (other in others) {
            AppLog.i("Repo", "Switching transcription #${other.id} → #$episodeId")
            stopTranscription(other.id)
        }
    }

    /**
     * The episode being listened to comes first: if its transcription is waiting or was stopped
     * part-way while another episode is being transcribed, that one is stopped (keeping its
     * progress) and this one starts now. Does nothing if this episode has no transcription
     * under way or nothing else is running.
     */
    suspend fun prioritize(episodeId: Long) {
        val episode = episodeDao.get(episodeId) ?: return
        val waiting = episode.transcriptState == TranscriptState.QUEUED
        val partial = episode.transcriptState == TranscriptState.NONE && episode.transcriptProgress > 0f
        if (!waiting && !partial) return
        val othersRunning = episodeDao.activeList().any {
            it.id != episodeId && (it.transcriptState == TranscriptState.QUEUED || it.transcriptState == TranscriptState.RUNNING)
        }
        if (!othersRunning) return
        AppLog.i("Repo", "Playing #$episodeId: its transcription goes first")
        stopOthers(episodeId)
        if (waiting) {
            // Requeue at once (REPLACE) rather than waiting for the others' jobs to let go.
            WorkScheduler.transcribe(context, episodeId, manual = true, replace = true)
        } else {
            transcribe(episodeId, manual = true, takeOver = false)
        }
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
        withTimeoutOrNull(3 * 60_000L) { ensureTranslationModel(transcript.language, target) }
            ?: throw IOException("the translation model couldn't be downloaded. Check your connection and try again.")
        val segments = transcript.segments.map { segment ->
            if (segment.translation != null && !stale) segment
            else segment.copy(translation = translation.translate(segment.text, transcript.language, target))
        }
        // Re-read in case transcription added sentences meanwhile (anywhere, after a jump), and
        // put the translations on matching sentences instead of relying on positions.
        val latest = transcripts.load(episodeId) ?: transcript
        val translated = segments.associateBy { it.startMs to it.text }
        val merged = latest.copy(
            segments = latest.segments.map { seg -> translated[seg.startMs to seg.text] ?: seg },
            translationLanguage = target,
        )
        transcripts.save(episodeId, merged)
        return merged
    }

    /**
     * Makes sure the translation model is on the phone. In Wi-Fi only mode it isn't downloaded
     * over mobile data; the error says so instead.
     */
    suspend fun ensureTranslationModel(source: String, target: String) {
        val present = runCatching { translation.isModelDownloaded(source, target) }.getOrDefault(false)
        if (!present && WorkScheduler.blockedByNetworkMode(context)) {
            throw IOException(context.getString(R.string.translation_needs_wifi))
        }
        translation.ensureModel(source, target, requireWifi = false)
    }

    suspend fun translateWord(text: String, source: String): String =
        translateText(text, source, settings.nativeLanguage.value)

    /** Translates [text] from [source] into [target], downloading the model if needed. */
    suspend fun translateText(text: String, source: String, target: String): String {
        // Google's offline translator doesn't know every language (e.g. Armenian).
        if (!translation.isSupported(source, target)) {
            val missing = if (translation.isSupported(source, "en")) target else source
            throw IOException(context.getString(R.string.no_offline_translation, languageName(missing)))
        }
        return withTimeoutOrNull(60_000L) {
            ensureTranslationModel(source, target)
            translation.translate(text, source, target)
        } ?: throw IOException("translation model not available yet")
    }

    // ---- Vocabulary --------------------------------------------------------

    suspend fun saveWord(entry: VocabEntity): Boolean {
        if (vocabDao.count(entry.word, entry.language) > 0) return false
        vocabDao.insert(entry)
        Analytics.track(
            "Word.saved",
            mapOf("kind" to if (entry.isSentence) "sentence" else "word", "audioClip" to (entry.audioPath != null).toString(),
                "fromEpisode" to (entry.episodeId != null).toString(), "language" to entry.language),
        )
        refreshProgressCounts()
        return true
    }

    suspend fun deleteWord(id: Long) {
        vocabDao.get(id)?.audioPath?.let { File(it).delete() }
        vocabDao.delete(id)
    }

    /**
     * Cuts the original audio of a sentence for a saved word, when the episode's audio is on
     * the phone (streamed episodes have no clip). Returns the clip's path.
     */
    suspend fun makeClip(episode: EpisodeEntity?, startMs: Long, endMs: Long): String? = withContext(Dispatchers.IO) {
        val audio = episode?.localPath?.takeIf { File(it).exists() } ?: return@withContext null
        val out = File(context.filesDir, "clips/${episode.id}-$startMs-${System.nanoTime() % 100000}.wav")
        if (com.ascolta.app.whisper.AudioClip.extract(audio, startMs, endMs, out)) out.path else null
    }

    suspend fun totalWords(): Int = vocabDao.countAll()

    /**
     * Adds a flashcard typed in by the user (a word or a sentence). Without a translation,
     * it is translated on the phone when possible.
     */
    suspend fun addCard(front: String, back: String?, isSentence: Boolean): Boolean {
        val language = settings.learningLanguage.value
        val given = back?.trim()?.takeIf { it.isNotEmpty() }
        // Either side may be typed: an empty front is translated from the learner's language.
        val text = front.trim().ifEmpty {
            given ?: return false
            runCatching { translateText(given, settings.nativeLanguage.value, language) }.getOrDefault("").trim()
        }
        if (text.isEmpty()) return false
        val translationText = given
            ?: runCatching { translateWord(text, language) }.getOrDefault("")
        return saveWord(
            VocabEntity(
                word = if (isSentence) text else text.lowercase(),
                translation = translationText,
                sentence = if (isSentence) text else null,
                sentenceTranslation = if (isSentence) translationText else null,
                language = language,
                episodeId = null,
                episodeTitle = null,
                createdAt = System.currentTimeMillis(),
                isSentence = isSentence,
            ),
        )
    }

    /** Cards due for review in the current language, oldest first. */
    suspend fun dueCardsNow(limit: Int = 50): List<VocabEntity> =
        vocabDao.due(settings.learningLanguage.value, System.currentTimeMillis(), limit)

    /** Records a review answer and schedules the card's next review. */
    suspend fun review(card: VocabEntity, grade: Flashcards.Grade) {
        Analytics.track("Flashcard.reviewed", mapOf("grade" to grade.name, "kind" to if (card.isSentence) "sentence" else "word", "language" to card.language))
        vocabDao.update(Flashcards.schedule(card, grade, System.currentTimeMillis()))
        (context.applicationContext as com.ascolta.app.AscoltaApp).graph.progress.recordReview()
    }

    /** Refreshes the counts that badges depend on (words, transcripts, languages). */
    suspend fun refreshProgressCounts() {
        (context.applicationContext as com.ascolta.app.AscoltaApp).graph.progress.setCounts(
            wordsSaved = vocabDao.countAll(),
            transcribed = episodeDao.countTranscribed(),
            languages = settings.learningList().size,
        )
    }

    /**
     * Deletes downloaded episode audio older than the chosen number of days (transcripts are
     * kept; the episode can still be streamed or downloaded again). The episode playing and
     * episodes being transcribed are left alone.
     */
    suspend fun deleteOldDownloads() {
        val days = settings.deleteAfterDays.value
        if (days <= 0) return
        val cutoff = System.currentTimeMillis() - days * 24L * 3600 * 1000
        val playing = (context.applicationContext as com.ascolta.app.AscoltaApp).graph.player.state.value.episodeId
        for (e in episodeDao.withDownloads()) {
            val file = e.localPath?.let(::File) ?: continue
            val downloadedAt = e.downloadedAt.takeIf { it > 0 } ?: file.lastModified()
            val busy = e.transcriptState == TranscriptState.QUEUED || e.transcriptState == TranscriptState.RUNNING
            if (downloadedAt < cutoff && e.id != playing && !busy) {
                file.delete()
                episodeDao.setLocalPath(e.id, null, DownloadState.NONE, 0)
                AppLog.i("Repo", "Deleted download of #${e.id} (older than $days days)")
            }
        }
    }

    /** Tab-separated export that Anki (and most flashcard apps) can import. */
    suspend fun exportVocabTsv(): String = vocabDao.all().filter { it.language == settings.learningLanguage.value }.joinToString("\n") { v ->
        listOf(v.word, v.translation, v.sentence.orEmpty(), v.sentenceTranslation.orEmpty())
            .joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') }
    }
}
