package com.ascolta.app.data

/** How long an episode is, from the best source available (the timeline needs a length). */
object AudioLength {
    /**
     * The length to show, in ms (pure, unit-tested): the player's, else the feed's, else the
     * downloaded file's, else the end of the transcript; 0 when nothing is known.
     */
    fun best(playerMs: Long, feedSec: Long, fileMs: Long?, transcriptEndMs: Long?): Long = when {
        playerMs > 0 -> playerMs
        feedSec > 0 -> feedSec * 1000
        fileMs != null && fileMs > 0 -> fileMs
        transcriptEndMs != null && transcriptEndMs > 0 -> transcriptEndMs
        else -> 0
    }

    /** The length of an audio file (a path, or an http(s) address of a stream) in ms, or null. */
    fun of(path: String): Long? = runCatching {
        val r = android.media.MediaMetadataRetriever()
        try {
            if (path.startsWith("http")) r.setDataSource(path, mapOf("User-Agent" to com.ascolta.app.net.Http.USER_AGENT))
            else r.setDataSource(path)
            r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }
        } finally {
            r.release()
        }
    }.getOrNull()
}
