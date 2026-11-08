package com.ascolta.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class Segment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val translation: String? = null,
)

@Serializable
data class Transcript(
    val language: String,
    /** "whisper" (generated on the phone) or "feed" (published by the podcast). */
    val source: String,
    val segments: List<Segment>,
    /** How much of the audio has been transcribed so far (for resuming). */
    val processedMs: Long = 0,
    val complete: Boolean = false,
    val model: String? = null,
    val translationLanguage: String? = null,
) {
    companion object {
        const val SOURCE_WHISPER = "whisper"
        const val SOURCE_FEED = "feed"
    }
}

/** Index of the segment playing at [positionMs], or -1 before the first one. */
fun List<Segment>.indexAt(positionMs: Long): Int {
    var lo = 0
    var hi = size - 1
    var result = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (this[mid].startMs <= positionMs) {
            result = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return result
}

/** Transcripts are stored as one JSON file per episode. */
class TranscriptStore(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Emits an episode id every time its transcript is saved, so screens can refresh at once. */
    val updates = MutableSharedFlow<Long>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private fun file(episodeId: Long) = File(dir, "$episodeId.json")

    suspend fun load(episodeId: Long): Transcript? = withContext(Dispatchers.IO) {
        val f = file(episodeId)
        if (!f.exists()) return@withContext null
        runCatching { json.decodeFromString(Transcript.serializer(), f.readText()) }.getOrNull()
    }

    suspend fun save(episodeId: Long, transcript: Transcript) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val tmp = File(dir, "$episodeId.json.tmp")
        tmp.writeText(json.encodeToString(Transcript.serializer(), transcript))
        tmp.renameTo(file(episodeId))
        updates.tryEmit(episodeId)
    }

    fun delete(episodeId: Long) {
        file(episodeId).delete()
    }
}
