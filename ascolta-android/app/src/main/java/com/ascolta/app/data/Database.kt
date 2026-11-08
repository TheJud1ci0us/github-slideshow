package com.ascolta.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface PodcastDao {
    @Query(
        """
        SELECT p.*,
          (SELECT COUNT(*) FROM episodes e WHERE e.podcastId = p.id) AS episodeCount,
          (SELECT COUNT(*) FROM episodes e WHERE e.podcastId = p.id AND e.transcriptState = 3) AS transcribedCount
        FROM podcasts p WHERE p.libraryLanguage = :language
        ORDER BY p.favourite DESC, p.title COLLATE NOCASE
        """,
    )
    fun observeWithStats(language: String): Flow<List<PodcastWithStats>>

    @Query("UPDATE podcasts SET favourite = :favourite WHERE id = :id")
    suspend fun setFavourite(id: Long, favourite: Boolean)

    @Query("UPDATE podcasts SET libraryLanguage = :language WHERE id = :id")
    suspend fun setLibraryLanguage(id: Long, language: String)

    @Query("SELECT COUNT(*) FROM podcasts WHERE libraryLanguage = :language")
    fun observeCount(language: String): Flow<Int>

    @Query("SELECT * FROM podcasts WHERE id = :id")
    fun observe(id: Long): Flow<PodcastEntity?>

    @Query("SELECT * FROM podcasts WHERE id = :id")
    suspend fun get(id: Long): PodcastEntity?

    @Query("SELECT * FROM podcasts WHERE feedUrl = :feedUrl")
    suspend fun getByFeedUrl(feedUrl: String): PodcastEntity?

    @Query("SELECT feedUrl FROM podcasts")
    fun observeFeedUrls(): Flow<List<String>>

    @Query("SELECT * FROM podcasts")
    suspend fun all(): List<PodcastEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(podcast: PodcastEntity): Long

    @Update
    suspend fun update(podcast: PodcastEntity)

    @Query("DELETE FROM podcasts WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface EpisodeDao {
    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId ORDER BY pubDate DESC")
    fun observeForPodcast(podcastId: Long): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE podcastId IS NULL AND (transcriptLanguage = :language OR transcriptLanguage IS NULL) ORDER BY pubDate DESC")
    fun observeImported(language: String): Flow<List<EpisodeEntity>>

    @Query("SELECT COUNT(*) FROM episodes WHERE podcastId IS NULL AND (transcriptLanguage = :language OR transcriptLanguage IS NULL)")
    fun observeImportedCount(language: String): Flow<Int>

    @Query("SELECT * FROM episodes WHERE localPath IS NOT NULL AND podcastId IS NOT NULL")
    suspend fun withDownloads(): List<EpisodeEntity>

    @Query(
        """SELECT * FROM episodes WHERE downloadState IN (1, 2) OR transcriptState IN (1, 2)
           ORDER BY transcriptState DESC, id""",
    )
    fun observeActive(): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE downloadState IN (1, 2) OR transcriptState IN (1, 2)")
    suspend fun activeList(): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE localPath IS NOT NULL ORDER BY lastPlayedAt DESC, pubDate DESC LIMIT 20")
    suspend fun downloaded(): List<EpisodeEntity>

    @Query(
        """SELECT e.* FROM episodes e LEFT JOIN podcasts p ON p.id = e.podcastId
           WHERE e.lastPlayedAt > 0 AND (p.libraryLanguage = :language
             OR (e.podcastId IS NULL AND (e.transcriptLanguage = :language OR e.transcriptLanguage IS NULL)))
           ORDER BY e.lastPlayedAt DESC LIMIT :limit""",
    )
    fun observeRecentlyPlayed(language: String, limit: Int): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE id = :id")
    fun observe(id: Long): Flow<EpisodeEntity?>

    @Query("SELECT * FROM episodes WHERE id = :id")
    suspend fun get(id: Long): EpisodeEntity?

    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId")
    suspend fun forPodcast(podcastId: Long): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId ORDER BY pubDate DESC LIMIT 1")
    suspend fun latest(podcastId: Long): EpisodeEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(episodes: List<EpisodeEntity>): List<Long>

    @Insert
    suspend fun insert(episode: EpisodeEntity): Long

    @Query("UPDATE episodes SET downloadState = :state, downloadProgress = :progress, error = :error WHERE id = :id")
    suspend fun setDownload(id: Long, state: Int, progress: Float, error: String? = null)

    @Query("UPDATE episodes SET localPath = :path, downloadState = :state, downloadProgress = 1, downloadedAt = :at WHERE id = :id")
    suspend fun setLocalPath(id: Long, path: String?, state: Int, at: Long = System.currentTimeMillis())

    @Query(
        """UPDATE episodes SET transcriptState = :state, transcriptProgress = :progress,
           transcriptStatus = :status, error = :error WHERE id = :id""",
    )
    suspend fun setTranscript(id: Long, state: Int, progress: Float, status: String?, error: String? = null)

    @Query("UPDATE episodes SET transcriptLanguage = :language WHERE id = :id")
    suspend fun setTranscriptLanguage(id: Long, language: String)

    @Query("UPDATE episodes SET playbackPositionMs = :positionMs, lastPlayedAt = :playedAt WHERE id = :id")
    suspend fun setPlayback(id: Long, positionMs: Long, playedAt: Long)

    @Query(
        """UPDATE episodes SET feedTranscriptUrl = :url, feedTranscriptType = :type
           WHERE podcastId = :podcastId AND guid = :guid AND feedTranscriptUrl IS NULL""",
    )
    suspend fun fillFeedTranscript(podcastId: Long, guid: String, url: String, type: String?)

    @Query("DELETE FROM episodes WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM episodes WHERE transcriptState = 3")
    suspend fun countTranscribed(): Int
}

@Dao
interface VocabDao {
    @Query("SELECT * FROM vocab WHERE language = :language ORDER BY createdAt DESC")
    fun observeAll(language: String): Flow<List<VocabEntity>>

    @Query("SELECT * FROM vocab WHERE language = :language AND dueAt <= :now ORDER BY dueAt, createdAt LIMIT :limit")
    suspend fun due(language: String, now: Long, limit: Int): List<VocabEntity>

    @Query("SELECT COUNT(*) FROM vocab WHERE language = :language AND dueAt <= :now")
    fun observeDueCount(language: String, now: Long): Flow<Int>

    @Update
    suspend fun update(entry: VocabEntity)

    @Query("SELECT * FROM vocab WHERE id = :id")
    suspend fun get(id: Long): VocabEntity?

    @Query("SELECT COUNT(*) FROM vocab")
    suspend fun countAll(): Int

    @Query("SELECT * FROM vocab ORDER BY createdAt DESC")
    suspend fun all(): List<VocabEntity>

    @Query("SELECT COUNT(*) FROM vocab WHERE word = :word AND language = :language")
    suspend fun count(word: String, language: String): Int

    @Insert
    suspend fun insert(entry: VocabEntity): Long

    @Query("DELETE FROM vocab WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(
    entities = [PodcastEntity::class, EpisodeEntity::class, VocabEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun podcastDao(): PodcastDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun vocabDao(): VocabDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "ascolta.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()

        /** v0.7: audio clips of saved words. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE vocab ADD COLUMN audioPath TEXT")
            }
        }

        /** v0.6: favourites, per-language libraries, download dates, flashcard scheduling. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE podcasts ADD COLUMN libraryLanguage TEXT")
                db.execSQL("ALTER TABLE podcasts ADD COLUMN favourite INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE episodes ADD COLUMN downloadedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE vocab ADD COLUMN dueAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE vocab ADD COLUMN intervalDays REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE vocab ADD COLUMN ease REAL NOT NULL DEFAULT 2.5")
                db.execSQL("ALTER TABLE vocab ADD COLUMN reps INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE vocab ADD COLUMN isSentence INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
