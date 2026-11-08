package com.ascolta.app.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "podcasts", indices = [Index(value = ["feedUrl"], unique = true)])
data class PodcastEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val feedUrl: String,
    val title: String,
    val author: String?,
    val description: String?,
    val imageUrl: String?,
    val language: String?,
    val autoDownload: Boolean = true,
    val subscribedAt: Long,
    val lastRefreshedAt: Long = 0,
    /** Which language's library this podcast is in (a learning language). */
    val libraryLanguage: String? = null,
    /** Favourites are listed first and notify about new episodes. */
    @ColumnInfo(defaultValue = "0") val favourite: Boolean = false,
)

object DownloadState {
    const val NONE = 0
    const val QUEUED = 1
    const val DOWNLOADING = 2
    const val DONE = 3
    const val FAILED = 4
}

object TranscriptState {
    const val NONE = 0
    const val QUEUED = 1
    const val RUNNING = 2
    const val DONE = 3
    const val FAILED = 4
}

/** Episodes with podcastId == null are audio files the user imported. */
@Entity(
    tableName = "episodes",
    indices = [Index(value = ["podcastId", "guid"], unique = true), Index("podcastId")],
    foreignKeys = [
        ForeignKey(
            entity = PodcastEntity::class,
            parentColumns = ["id"],
            childColumns = ["podcastId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class EpisodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val podcastId: Long?,
    val guid: String,
    val title: String,
    val description: String? = null,
    val audioUrl: String? = null,
    val mimeType: String? = null,
    val pubDate: Long = 0,
    val durationSec: Long = 0,
    val imageUrl: String? = null,
    val feedTranscriptUrl: String? = null,
    val feedTranscriptType: String? = null,
    val localPath: String? = null,
    val downloadState: Int = DownloadState.NONE,
    val downloadProgress: Float = 0f,
    val transcriptState: Int = TranscriptState.NONE,
    val transcriptProgress: Float = 0f,
    /** Human-readable step, e.g. "Transcribing" or "Translating". */
    val transcriptStatus: String? = null,
    val transcriptLanguage: String? = null,
    val playbackPositionMs: Long = 0,
    val lastPlayedAt: Long = 0,
    val error: String? = null,
    /** When the audio was downloaded (for deleting old downloads). */
    @ColumnInfo(defaultValue = "0") val downloadedAt: Long = 0,
)

data class PodcastWithStats(
    @Embedded val podcast: PodcastEntity,
    val episodeCount: Int,
    val transcribedCount: Int,
)

@Entity(tableName = "vocab")
data class VocabEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val word: String,
    val translation: String,
    val sentence: String?,
    val sentenceTranslation: String?,
    val language: String,
    val episodeId: Long?,
    val episodeTitle: String?,
    val createdAt: Long,
    // Flashcard scheduling (spaced repetition): next review time, interval and ease.
    @ColumnInfo(defaultValue = "0") val dueAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val intervalDays: Float = 0f,
    @ColumnInfo(defaultValue = "2.5") val ease: Float = 2.5f,
    @ColumnInfo(defaultValue = "0") val reps: Int = 0,
    /** A whole sentence saved as a card (rather than a single word). */
    @ColumnInfo(defaultValue = "0") val isSentence: Boolean = false,
    /** Clip of the original audio of the sentence (a WAV file in the app's storage). */
    val audioPath: String? = null,
)
