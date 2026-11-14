package com.ascolta.app.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.ascolta.app.MainActivity

/** Plays audio in the background with a media notification and lock-screen controls. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        // Exact seeking in MP3 files with a variable bit rate (most podcasts). Without an index,
        // ExoPlayer estimates the position after a seek, which can be seconds off and would
        // make the highlighted sentence not match what is being said.
        // The index is built while reading, so on a stream a jump forward would first read
        // everything up to there (seeking looked stuck, e.g. over an ad at the start): streams
        // use the MP3 header / bit rate instead, until the player switches to the downloaded file.
        val indexed = DefaultExtractorsFactory()
            .setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)
            .setConstantBitrateSeekingEnabled(true)
        val streamed = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
        val extractors = object : androidx.media3.extractor.ExtractorsFactory {
            override fun createExtractors() = indexed.createExtractors()
            override fun createExtractors(uri: android.net.Uri, responseHeaders: Map<String, List<String>>) =
                (if (uri.scheme == "file" || uri.scheme == "content") indexed else streamed).createExtractors(uri, responseHeaders)
        }
        val player = ExoPlayer.Builder(this, DefaultMediaSourceFactory(this, extractors))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        session = MediaSession.Builder(this, player).setSessionActivity(openApp).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
