package com.ascolta.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/** A stretch of the episode, in milliseconds. */
@Serializable
data class Span(val startMs: Long, val endMs: Long)

/** A word of a sentence and when it starts, for word-by-word highlighting. */
@Serializable
data class Word(val startMs: Long, val text: String)

@Serializable
data class Segment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val translation: String? = null,
    /** Word timings when the speech engine provides them. */
    val words: List<Word>? = null,
)

@Serializable
data class Transcript(
    val language: String,
    /** "whisper" (generated on the phone) or "feed" (published by the podcast). */
    val source: String,
    val segments: List<Segment>,
    /** Older versions: transcribed from the start up to here. Kept in sync with [covered]. */
    val processedMs: Long = 0,
    /** Stretches of the episode already transcribed (they need not be contiguous). */
    val covered: List<Span> = emptyList(),
    val complete: Boolean = false,
    val model: String? = null,
    val translationLanguage: String? = null,
) {
    /** [covered], or for transcripts from older versions, 0..processedMs. */
    fun coveredSpans(): List<Span> =
        covered.ifEmpty { if (processedMs > 0) listOf(Span(0, processedMs)) else emptyList() }

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

    private fun editsFile(episodeId: Long) = File(dir, "$episodeId.edits.json")

    /** Sentences corrected by the learner, by start time (kept even while transcription is still writing). */
    private fun edits(episodeId: Long): Map<Long, String> = runCatching {
        json.decodeFromString(kotlinx.serialization.builtins.MapSerializer(Long.serializer(), String.serializer()), editsFile(episodeId).readText())
    }.getOrDefault(emptyMap())

    /**
     * The learner corrects a sentence (a misheard name…). The correction is kept apart too, so a
     * transcription still running doesn't write the old text back over it.
     */
    suspend fun edit(episodeId: Long, startMs: Long, text: String) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val all = edits(episodeId) + (startMs to text.trim())
        editsFile(episodeId).writeText(
            json.encodeToString(kotlinx.serialization.builtins.MapSerializer(Long.serializer(), String.serializer()), all),
        )
        load(episodeId)?.let { save(episodeId, it) }
    }

    suspend fun save(episodeId: Long, transcript: Transcript) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val tmp = File(dir, "$episodeId.json.tmp")
        tmp.writeText(json.encodeToString(Transcript.serializer(), applyEdits(transcript, edits(episodeId))))
        tmp.renameTo(file(episodeId))
        updates.tryEmit(episodeId)
    }

    fun delete(episodeId: Long) {
        file(episodeId).delete()
        editsFile(episodeId).delete()
    }
}

/** The learner's corrections put into a transcript (pure). */
fun applyEdits(transcript: Transcript, edits: Map<Long, String>): Transcript {
    if (edits.isEmpty()) return transcript
    return transcript.copy(
        segments = transcript.segments.map { s ->
            val text = edits[s.startMs]
            if (text == null || text == s.text) s
            else s.copy(text = text, translation = null, words = retime(s, text))
        },
    )
}

/**
 * Word times for a corrected sentence: the same times when the number of words is unchanged,
 * otherwise spread evenly over the sentence (null for languages written without spaces).
 */
fun retime(old: Segment, text: String): List<Word>? {
    val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.size <= 1 && text.length > 12) return null // Japanese, Chinese…: no word boundaries
    val before = old.words
    if (before != null && before.size == words.size) return before.zip(words) { w, t -> w.copy(text = t) }
    val span = (old.endMs - old.startMs).coerceAtLeast(1)
    return words.mapIndexed { i, t -> Word(old.startMs + span * i / words.size, t) }
}
