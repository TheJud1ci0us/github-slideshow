package com.ascolta.app.data

import android.content.Context
import com.ascolta.app.R
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

/** Learning statistics that badges are earned from. */
data class Stats(
    /** Milliseconds listened, per language. */
    val listenedMs: Map<String, Long> = emptyMap(),
    val streakDays: Int = 0,
    val bestStreak: Int = 0,
    val cardsReviewed: Int = 0,
    val wordsSaved: Int = 0,
    val transcribed: Int = 0,
    val languages: Int = 1,
    val earned: Map<String, Long> = emptyMap(),
) {
    val totalListenedMs: Long get() = listenedMs.values.sum()
    fun hours(language: String) = (listenedMs[language] ?: 0L) / 3_600_000.0
}

/**
 * A badge. Listening badges ("auditory milestones") count hours listened in one language,
 * the classic measure of comprehensible input.
 */
data class Badge(
    val id: String,
    val emoji: String,
    val titleRes: Int,
    /** Requirement, formatted with [amount]. */
    val descRes: Int,
    val amount: Int,
    val earned: (Stats) -> Boolean,
)

object Badges {
    /** Listening milestones in hours (per language). */
    val LISTENING_HOURS = listOf(1, 5, 10, 25, 50, 100, 250, 500, 1000)

    fun all(): List<Badge> =
        LISTENING_HOURS.map { h ->
            Badge("listen-$h", if (h < 25) "🎧" else if (h < 250) "👂" else "🏆", R.string.badge_listen_title, R.string.badge_listen_desc, h) { s ->
                s.listenedMs.values.any { it >= h * 3_600_000L }
            }
        } + listOf(3, 7, 30, 100).map { d ->
            Badge("streak-$d", "🔥", R.string.badge_streak_title, R.string.badge_streak_desc, d) { it.bestStreak >= d }
        } + listOf(1, 50, 200, 1000).map { n ->
            Badge("words-$n", "📝", R.string.badge_words_title, R.string.badge_words_desc, n) { it.wordsSaved >= n }
        } + listOf(1, 100, 1000).map { n ->
            Badge("reviews-$n", "🧠", R.string.badge_reviews_title, R.string.badge_reviews_desc, n) { it.cardsReviewed >= n }
        } + listOf(1, 10, 50).map { n ->
            Badge("transcripts-$n", "📜", R.string.badge_transcripts_title, R.string.badge_transcripts_desc, n) { it.transcribed >= n }
        } + listOf(2, 3).map { n ->
            Badge("polyglot-$n", "🌍", R.string.badge_polyglot_title, R.string.badge_polyglot_desc, n) { it.languages >= n }
        }

    /** The next listening milestone in hours above [hours], or null after the last. */
    fun nextListening(hours: Double): Int? = LISTENING_HOURS.firstOrNull { it > hours }
}

/**
 * Stores listening time, streaks and reviews (in SharedPreferences) and announces newly
 * earned badges on [unlocked].
 */
class ProgressStore(context: Context) {
    private val prefs = context.getSharedPreferences("progress", Context.MODE_PRIVATE)
    private val _stats = MutableStateFlow(load())
    val stats: StateFlow<Stats> = _stats
    private val _unlocked = MutableSharedFlow<Badge>(extraBufferCapacity = 8)
    val unlocked: SharedFlow<Badge> = _unlocked
    private var pendingMs = mutableMapOf<String, Long>()
    private var lastFlush = 0L

    private fun load(): Stats {
        val listened = prefs.all.filterKeys { it.startsWith("ms_") }.mapNotNull { (k, v) -> (v as? Long)?.let { k.removePrefix("ms_") to it } }.toMap()
        val earned = prefs.getString("earned", "").orEmpty().split(',').mapNotNull {
            it.split(':').takeIf { p -> p.size == 2 }?.let { p -> p[0] to (p[1].toLongOrNull() ?: 0L) }
        }.toMap()
        return Stats(
            listenedMs = listened,
            streakDays = currentStreak(),
            bestStreak = prefs.getInt("best_streak", 0),
            cardsReviewed = prefs.getInt("reviews", 0),
            earned = earned,
        )
    }

    private fun currentStreak(): Int {
        val last = prefs.getLong("last_day", -1)
        val today = LocalDate.now().toEpochDay()
        return if (last >= today - 1) prefs.getInt("streak", 0) else 0
    }

    /** Adds listening time (called while playing); saved every 15 s. */
    @Synchronized
    fun addListening(language: String, ms: Long) {
        if (ms <= 0) return
        pendingMs[language] = (pendingMs[language] ?: 0L) + ms
        val now = System.currentTimeMillis()
        if (now - lastFlush >= 15_000) flush()
    }

    @Synchronized
    fun flush() {
        lastFlush = System.currentTimeMillis()
        if (pendingMs.isEmpty()) return
        val edit = prefs.edit()
        pendingMs.forEach { (lang, ms) -> edit.putLong("ms_$lang", prefs.getLong("ms_$lang", 0) + ms) }
        // A day with at least a minute of listening continues the streak.
        val today = LocalDate.now().toEpochDay()
        val last = prefs.getLong("last_day", -1)
        if (last != today && pendingMs.values.sum() > 0) {
            val streak = if (last == today - 1) prefs.getInt("streak", 0) + 1 else 1
            edit.putInt("streak", streak).putLong("last_day", today)
            edit.putInt("best_streak", maxOf(streak, prefs.getInt("best_streak", 0)))
        }
        edit.apply()
        pendingMs = mutableMapOf()
        update { load().copy(wordsSaved = it.wordsSaved, transcribed = it.transcribed, languages = it.languages) }
    }

    fun recordReview() {
        prefs.edit().putInt("reviews", prefs.getInt("reviews", 0) + 1).apply()
        update { it.copy(cardsReviewed = it.cardsReviewed + 1) }
    }

    /** Counts kept in the database (saved words, transcripts) and languages being learned. */
    fun setCounts(wordsSaved: Int, transcribed: Int, languages: Int) =
        update { it.copy(wordsSaved = wordsSaved, transcribed = transcribed, languages = languages) }

    @Synchronized
    private fun update(change: (Stats) -> Stats) {
        val stats = change(_stats.value)
        val newly = Badges.all().filter { it.id !in stats.earned && it.earned(stats) }
        val earned = stats.earned + newly.associate { it.id to System.currentTimeMillis() }
        if (newly.isNotEmpty()) {
            prefs.edit().putString("earned", earned.entries.joinToString(",") { "${it.key}:${it.value}" }).apply()
        }
        _stats.value = stats.copy(earned = earned)
        newly.forEach { _unlocked.tryEmit(it) }
    }
}
