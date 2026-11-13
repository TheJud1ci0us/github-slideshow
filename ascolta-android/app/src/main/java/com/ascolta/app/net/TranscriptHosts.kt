package com.ascolta.app.net

/**
 * Podcast hosts and official transcripts (`<podcast:transcript>` in the feed). Some hosts never
 * publish them, so their feeds needn't be downloaded to know; a show there is answered at once.
 */
object TranscriptHosts {
    /** Hosts whose feeds never carry transcripts (Spotify for Creators, SoundCloud). */
    private val NEVER = setOf("anchor.fm", "podcasters.spotify.com", "feeds.soundcloud.com")

    /** False for a feed on a host that never publishes transcripts; null when it must be read. */
    fun quickAnswer(feedUrl: String): Boolean? {
        val host = runCatching { java.net.URI(feedUrl.trim()).host?.lowercase() }.getOrNull() ?: return null
        return if (NEVER.any { host == it || host.endsWith(".$it") }) false else null
    }
}
