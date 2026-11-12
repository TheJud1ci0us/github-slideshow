package com.ascolta.app.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.ascolta.app.data.AppDatabase
import com.ascolta.app.data.EpisodeEntity
import com.ascolta.app.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class PlayerState(
    val episodeId: Long? = null,
    val title: String = "",
    val subtitle: String? = null,
    val artworkUrl: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
)

/**
 * Sleep timer: playback stops at [endsAt] (wall clock), or at the end of the episode. With a
 * transcript it waits for the sentence being spoken to finish ([snapToMs]) and fades out.
 */
data class SleepTimer(val endsAt: Long?, val endOfEpisode: Boolean = false, val snapToMs: Long? = null)

/**
 * The UI's handle on [PlaybackService]. Exposes playback state as a flow and
 * implements "pause after each sentence" for language practice.
 */
class PlayerConnection(
    private val context: Context,
    db: AppDatabase,
    private val settings: Settings,
    private val progress: com.ascolta.app.data.ProgressStore,
    private val fish: com.ascolta.app.data.FishStore,
) {
    private val podcastDao = db.podcastDao()
    /** Language of the episode playing, for listening statistics. */
    @Volatile private var playingLanguage: String? = null
    private var lastTickWall = 0L
    private val episodeDao = db.episodeDao()
    private val listeningDao = db.listeningDao()

    /** Listening log (Statistics): time per (day, episode) not yet written, written every 30 s and on pause. */
    private data class LogKey(val day: Int, val episodeId: Long, val podcastId: Long, val language: String)
    private val pendingLog = HashMap<LogKey, Long>()
    private var lastLogWrite = 0L
    private var playingPodcastId = 0L

    private fun logListening(ms: Long, language: String) {
        val id = _state.value.episodeId ?: return
        val key = LogKey(java.time.LocalDate.now().toEpochDay().toInt(), id, playingPodcastId, language)
        pendingLog[key] = (pendingLog[key] ?: 0L) + ms
        if (System.currentTimeMillis() - lastLogWrite > 30_000) writeLog()
    }

    private fun writeLog() {
        lastLogWrite = System.currentTimeMillis()
        if (pendingLog.isEmpty()) return
        val batch = pendingLog.toMap()
        pendingLog.clear()
        scope.launch(Dispatchers.IO) {
            batch.forEach { (k, ms) -> runCatching { listeningDao.record(k.day, k.episodeId, k.podcastId, k.language, ms) } }
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: MediaController? = null

    private val _state = MutableStateFlow(PlayerState(speed = settings.playbackSpeed.value))
    val state: StateFlow<PlayerState> = _state

    private val _sleep = MutableStateFlow<SleepTimer?>(null)
    val sleep: StateFlow<SleepTimer?> = _sleep

    /** Starts the sleep timer: [minutes] from now, 0 = at the end of the episode, null = off. */
    fun setSleepTimer(minutes: Int?) {
        com.ascolta.app.data.Analytics.track("SleepTimer.set", mapOf("minutes" to (minutes?.toString() ?: "off")))
        controller?.volume = 1f
        _sleep.value = when (minutes) {
            null -> null
            0 -> SleepTimer(endsAt = null, endOfEpisode = true)
            else -> SleepTimer(endsAt = System.currentTimeMillis() + minutes * 60_000L)
        }
    }

    /** Sentence end times of the current episode, used for auto-pause. */
    @Volatile var sentenceEnds: LongArray = LongArray(0)
    private var lastTickPosition = 0L
    private var lastSavedAt = 0L

    private suspend fun controller(): MediaController {
        controller?.let { return it }
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        val c = suspendCancellableCoroutine { cont ->
            future.addListener({
                try {
                    cont.resume(future.get())
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            }, ContextCompat.getMainExecutor(context))
        }
        c.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                syncState()
                if (events.contains(Player.EVENT_IS_PLAYING_CHANGED) && !player.isPlaying) {
                    saveCurrentPosition()
                    progress.flush()
                    writeLog()
                }
            }
        })
        controller = c
        startTicker()
        syncState()
        return c
    }

    /** Reconnects to an already-playing session (e.g. after the activity is recreated). */
    fun connect() {
        scope.launch { runCatching { controller() } }
    }

    suspend fun play(episode: EpisodeEntity, podcastTitle: String?, artworkUrl: String?) {
        val c = controller()
        if (_state.value.episodeId == episode.id && c.mediaItemCount > 0) {
            c.play()
            return
        }
        saveCurrentPosition()
        com.ascolta.app.data.Analytics.track(
            "Episode.played",
            mapOf("source" to if (episode.localPath?.let { File(it).exists() } == true) "downloaded" else "streamed",
                "imported" to (episode.podcastId == null).toString()),
        )
        val uri = episode.localPath?.takeIf { File(it).exists() }?.let { Uri.fromFile(File(it)) }
            ?: episode.audioUrl?.let(Uri::parse)
            ?: return
        val item = MediaItem.Builder()
            .setMediaId(episode.id.toString())
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(episode.title)
                    .setArtist(podcastTitle)
                    .setArtworkUri(artworkUrl?.let(Uri::parse))
                    .build(),
            )
            .build()
        sentenceEnds = LongArray(0)
        playingPodcastId = episode.podcastId ?: 0L
        playingLanguage = episode.podcastId?.let { podcastDao.get(it)?.libraryLanguage } ?: episode.transcriptLanguage
        _state.value = _state.value.copy(episodeId = episode.id, title = episode.title, subtitle = podcastTitle, artworkUrl = artworkUrl)
        c.setMediaItem(item, episode.playbackPositionMs)
        if (uri.scheme == "file") fileWatch?.cancel() else switchToFileWhenDownloaded(episode.id, item)
        c.setPlaybackParameters(PlaybackParameters(settings.playbackSpeed.value))
        c.prepare()
        c.play()
        lastTickPosition = episode.playbackPositionMs
    }

    /** Waits for the download of the episode being streamed, to switch to it (see [play]). */
    private var fileWatch: kotlinx.coroutines.Job? = null

    /**
     * Hosts such as Acast or Megaphone put different ads in every copy of an episode, so the
     * streamed copy and the downloaded one (which is the one transcribed) don't line up: the
     * transcript would run early or late by the difference between the ads. As soon as the
     * episode being streamed is downloaded, the player switches to the file (same position).
     */
    private fun switchToFileWhenDownloaded(episodeId: Long, item: MediaItem) {
        fileWatch?.cancel()
        fileWatch = scope.launch {
            val path = episodeDao.observe(episodeId)
                .mapNotNull { e -> e?.localPath?.takeIf { File(it).exists() } }
                .first()
            val c = controller()
            if (c.currentMediaItem?.mediaId != episodeId.toString()) return@launch
            val position = c.currentPosition
            val playing = c.playWhenReady
            c.setMediaItem(item.buildUpon().setUri(Uri.fromFile(File(path))).build(), position)
            c.prepare()
            c.playWhenReady = playing
            com.ascolta.app.data.AppLog.i("Player", "Switched #$episodeId to the downloaded file at ${position / 1000}s")
            android.widget.Toast.makeText(context, context.getString(com.ascolta.app.R.string.switched_to_download), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private var snippetJob: kotlinx.coroutines.Job? = null
    private var snippetReturn = 0L
    private var snippetWasPlaying = false

    /**
     * Plays just [fromMs]..[toMs] of the loaded episode (a word, as the speaker said it), then
     * goes back to where playback was and resumes if it was playing.
     */
    fun playSnippet(fromMs: Long, toMs: Long) {
        val c = controller ?: return
        // Tapping again while a word plays keeps the original place to return to.
        val back = if (snippetJob != null) snippetReturn else c.currentPosition
        val wasPlaying = if (snippetJob != null) snippetWasPlaying else c.isPlaying
        snippetReturn = back
        snippetWasPlaying = wasPlaying
        snippetJob?.cancel()
        val start = (fromMs - 80).coerceAtLeast(0)
        c.seekTo(start)
        c.play()
        val speed = c.playbackParameters.speed.coerceAtLeast(0.25f)
        snippetJob = scope.launch {
            delay(((toMs - start + 150) / speed).toLong().coerceIn(200, 4_000))
            c.pause()
            c.seekTo(back)
            if (wasPlaying) c.play()
            snippetJob = null
        }
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun pause() {
        controller?.pause()
    }

    fun seekTo(positionMs: Long, play: Boolean = false) {
        val c = controller ?: return
        c.seekTo(positionMs.coerceAtLeast(0))
        lastTickPosition = positionMs
        if (play) c.play()
        syncState()
    }

    fun seekBy(deltaMs: Long) = seekTo((controller?.currentPosition ?: 0) + deltaMs)

    fun setSpeed(speed: Float) {
        settings.playbackSpeed.set(speed)
        controller?.setPlaybackParameters(PlaybackParameters(speed))
        syncState()
    }

    private fun syncState() {
        val c = controller ?: return
        val mediaId = c.currentMediaItem?.mediaId?.toLongOrNull()
        val metadata = c.mediaMetadata
        _state.value = _state.value.copy(
            episodeId = mediaId,
            title = metadata.title?.toString() ?: _state.value.title,
            subtitle = metadata.artist?.toString() ?: _state.value.subtitle,
            artworkUrl = metadata.artworkUri?.toString() ?: _state.value.artworkUrl,
            isPlaying = c.isPlaying,
            isBuffering = c.playbackState == Player.STATE_BUFFERING,
            positionMs = c.currentPosition,
            durationMs = c.duration.takeIf { it > 0 } ?: 0,
            speed = c.playbackParameters.speed,
        )
    }

    private fun startTicker() {
        scope.launch {
            while (true) {
                val c = controller
                val wall = System.currentTimeMillis()
                if (c != null && c.isPlaying) {
                    // Listening time ("auditory milestones"), real time, per language.
                    if (lastTickWall > 0) {
                        val language = playingLanguage ?: settings.learningLanguage.value
                        val ms = (wall - lastTickWall).coerceIn(0, 1_000)
                        progress.addListening(language, ms)
                        logListening(ms, language)
                        // The Aquarium is paid in coins from foreign-language listening.
                        fish.addListening(ms, language != settings.nativeLanguage.value, c.currentMediaItem?.mediaId?.toLongOrNull() ?: 0L)
                    }
                    val position = c.currentPosition
                    val length = c.duration
                    if (length > 60_000 && position >= length - 5_000) fish.episodeFinished(c.currentMediaItem?.mediaId?.toLongOrNull() ?: 0L, length)
                    checkAutoPause(c, position)
                    checkSleep(c, position)
                    lastTickPosition = position
                    syncState()
                    if (System.currentTimeMillis() - lastSavedAt > 5_000) saveCurrentPosition()
                }
                lastTickWall = if (c != null && c.isPlaying) wall else 0L
                if (c != null && !c.isPlaying && _sleep.value?.endOfEpisode == true && c.playbackState == Player.STATE_ENDED) {
                    _sleep.value = null
                }
                delay(if (settings.autoPause.value || _sleep.value?.snapToMs != null) 50 else 100) // 10× a second: smooth word highlighting
            }
        }
    }

    /**
     * Sleep timer with sentence snapping: when the time is up, playback continues to the end
     * of the sentence being spoken (if it ends within 30 s) and fades out over its last
     * second and a half, so it never stops mid-word. Without a transcript it fades out over 3 s.
     */
    private fun checkSleep(c: MediaController, position: Long) {
        val timer = _sleep.value ?: return
        val endsAt = timer.endsAt ?: return
        val now = System.currentTimeMillis()
        if (now < endsAt) return
        val target = timer.snapToMs ?: run {
            val next = sentenceEnds.firstOrNull { it > position }
            val snap = if (next != null && next - position <= 30_000) next else position + 3_000
            _sleep.value = timer.copy(snapToMs = snap)
            snap
        }
        val left = target - position
        val fadeMs = 1_500L
        if (left <= 0) {
            c.pause()
            c.volume = 1f
            _sleep.value = null
        } else if (left < fadeMs) {
            c.volume = (left.toFloat() / fadeMs).coerceIn(0f, 1f)
        }
    }

    /** Pauses when playback crosses the end of a sentence. */
    private fun checkAutoPause(c: MediaController, position: Long) {
        if (!settings.autoPause.value) return
        val ends = sentenceEnds
        val previous = lastTickPosition
        if (position <= previous || position - previous > 1_000) return // seek, not normal playback
        val idx = ends.binarySearch(previous).let { if (it >= 0) it + 1 else -it - 1 }
        if (idx < ends.size && ends[idx] in (previous + 1)..position) {
            c.pause()
        }
    }

    private fun saveCurrentPosition() {
        val c = controller ?: return
        val id = c.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        val position = c.currentPosition
        lastSavedAt = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) { episodeDao.setPlayback(id, position, System.currentTimeMillis()) }
    }
}
