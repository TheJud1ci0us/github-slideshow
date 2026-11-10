package com.ascolta.app.data

import com.ascolta.app.net.DirectoryResult
import com.ascolta.app.net.PodcastDirectory
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withLock
import java.util.Locale

/** A suggested podcast with its estimated level. */
data class Pick(val podcast: DirectoryResult, val level: LevelGuess.Estimate, val isCourse: Boolean = false)

/**
 * Podcast suggestions for a learner: courses for the language and the top podcasts of their
 * favourite themes in that language's store (English shows dropped), each with an estimated
 * CEFR level, those at the learner's level first. Used by onboarding, Personalize and Discover.
 */
object Recommendations {
    suspend fun picks(
        directory: PodcastDirectory,
        feedLanguage: com.ascolta.app.net.FeedLanguage,
        language: String,
        native: String,
        genres: List<Int>,
        band: LevelBand?,
        limit: Int = 30,
    ): List<Pick> = coroutineScope {
        val country = LANGUAGES.firstOrNull { it.code == language }?.country ?: "US"
        val wanted = genres.filter { it != GENRE_LEARNING }.take(4)
        val courses = async { runCatching { courses(directory, language, country) }.getOrDefault(emptyList()) }
        val themed = wanted.map { g ->
            async { g to runCatching { themed(directory, g, language, country, 25) }.getOrDefault(emptyList()) }
        }
        // No theme chosen: local podcasts found by the word "podcast" in the language (for
        // languages whose store charts are dominated by another language). With themes, the
        // themed lists already include those, limited to the theme.
        val local = LanguageCheck.PODCAST_WORD[language]?.takeIf { wanted.isEmpty() }?.let { word ->
            async { 0 to runCatching { directory.search(word, country) }.getOrDefault(emptyList()) }
        }
        val byGenre = themed.awaitAll() + listOfNotNull(local?.await())
        val candidates = merge(
            language = language,
            courses = courses.await(),
            byGenre = byGenre,
            band = band,
            limit = limit * 3,
            // Beginners mostly want courses; others mostly real podcasts.
            coursesFirst = band == LevelBand.BEGINNER || wanted.isEmpty(),
        )
        // Only podcasts really in the language (courses may also be in the learner's own).
        val declared = feedLanguage.ofAll(candidates.map { it.podcast.feedUrl })
        verify(candidates, declared, language, native).take(limit)
    }

    /**
     * Podcasts of one theme in [country]'s store: Apple's chart for the theme, then searches for
     * the theme's own name in the store's language (e.g. 科学 for Science in Japan) and for the
     * word "podcast", keeping only shows Apple files under the theme. (Apple's search ignores
     * its genre filter, so a plain search for "podcast" used to bring marketing or comedy shows
     * into Science.)
     */
    suspend fun themed(directory: PodcastDirectory, genreId: Int, language: String, country: String, chartSize: Int = 50): List<DirectoryResult> =
        coroutineScope {
            val chart = runCatching { directory.topByGenre(genreId, country, chartSize) }.getOrDefault(emptyList())
            val terms = listOfNotNull(genreName(chart, genreId), LanguageCheck.PODCAST_WORD[language]).distinct()
            val found = terms.map { t -> async { runCatching { directory.search(t, country, 100) }.getOrDefault(emptyList()) } }
                .awaitAll().flatten()
            (chart + inTheme(found, genreId)).distinctBy { it.feedUrl }
        }

    /** The theme's name in the store's language, read from a chart show whose main genre it is (pure). */
    fun genreName(chart: List<DirectoryResult>, genreId: Int): String? =
        chart.firstOrNull { it.genreIds.firstOrNull() == genreId }?.genre

    /** Only shows filed under [genreId] (as main genre, or a sub-genre of it) (pure). */
    fun inTheme(results: List<DirectoryResult>, genreId: Int): List<DirectoryResult> =
        results.filter { genreId in it.genreIds }

    /** Keeps the picks in the right language and records each one's declared language (pure). */
    fun verify(candidates: List<Pick>, declared: Map<String, String?>, language: String, native: String): List<Pick> =
        candidates.mapNotNull { p ->
            val lang = declared[p.podcast.feedUrl]
            if (LanguageCheck.keep(p.podcast.title, p.podcast.summary, lang, language, native, p.isCourse)) {
                p.copy(podcast = p.podcast.copy(language = lang))
            } else null
        }

    /** Courses for [language] (Language Learning genre), in its store and the US store. */
    suspend fun courses(directory: PodcastDirectory, language: String, country: String): List<DirectoryResult> = coroutineScope {
        val names = languageNames(language)
        val found = names.flatMap { name -> listOf(country, "US").distinct().map { c -> async { runCatching { directory.searchGenre(name, GENRE_LEARNING, c) }.getOrDefault(emptyList()) } } }
            .awaitAll().flatten().distinctBy { it.feedUrl }
        // Only courses that name this language (not "Learn Spanish" when learning Georgian).
        found.filter { r -> names.any { n -> "${r.title} ${r.summary.orEmpty()}".contains(n, ignoreCase = true) } }
    }

    fun languageNames(language: String): List<String> {
        val locale = Locale.forLanguageTag(language)
        return listOf(locale.getDisplayLanguage(locale), locale.getDisplayLanguage(Locale.ENGLISH)).filter { it.isNotBlank() }.distinct()
    }

    /**
     * Combines courses and themed charts (pure, unit-tested): English shows dropped for other
     * languages, duplicates removed, themes interleaved, podcasts at the learner's level first.
     */
    fun merge(
        language: String,
        courses: List<DirectoryResult>,
        byGenre: List<Pair<Int, List<DirectoryResult>>>,
        band: LevelBand?,
        limit: Int,
        coursesFirst: Boolean,
    ): List<Pick> {
        val coursePicks = courses.map { Pick(it, LevelGuess.estimate(it.title, it.summary, GENRE_LEARNING, it.genre), isCourse = true) }
        // Round-robin across themes so one theme doesn't fill the list.
        val lists = byGenre.map { (g, list) ->
            list.filter { language == "en" || !LevelGuess.looksEnglish("${it.title} ${it.summary.orEmpty()}") }
                .map { Pick(it, LevelGuess.estimate(it.title, it.summary, g, it.genre)) }
        }
        val themed = mutableListOf<Pick>()
        var i = 0
        while (lists.any { i < it.size }) {
            lists.forEach { l -> l.getOrNull(i)?.let(themed::add) }
            i++
        }
        val ordered = if (coursesFirst) coursePicks + themed else themed + coursePicks
        val unique = ordered.distinctBy { it.podcast.feedUrl }
        val sorted = if (band == null) unique else unique.sortedBy { if (it.level.band == band) 0 else 1 } // stable
        return sorted.take(limit)
    }

    /** Apple's top-level "Podcasts" genre, listed in every show's genres. */
    private const val GENRE_ALL = 26

    /**
     * The themes to look in for shows like [podcast]: its own main genre first, then the
     * broader ones it belongs to (e.g. Natural Sciences, then Science) (pure).
     */
    fun themesOf(podcast: DirectoryResult): List<Int> = podcast.genreIds.filter { it != GENRE_ALL }.distinct()
}

/**
 * "More like this": each podcast picked during setup brings a few similar ones (same theme,
 * same language), as many times as you like, until the store runs out. Candidates are fetched
 * once per theme and checked for language a few at a time.
 */
class MoreLikeThis(
    private val directory: PodcastDirectory,
    private val feedLanguage: com.ascolta.app.net.FeedLanguage,
    private val language: String,
    private val native: String,
) {
    private val country = LANGUAGES.firstOrNull { it.code == language }?.country ?: "US"
    private val pools = mutableMapOf<Int, ArrayDeque<DirectoryResult>>()
    private val lock = kotlinx.coroutines.sync.Mutex()

    /** Up to [count] podcasts like [podcast] in the language, none of them in [exclude]. */
    suspend fun next(podcast: DirectoryResult, exclude: Set<String>, count: Int = 3): List<Pick> = lock.withLock {
        val out = mutableListOf<Pick>()
        for (g in Recommendations.themesOf(podcast)) {
            val pool = pools.getOrPut(g) {
                ArrayDeque(runCatching { Recommendations.themed(directory, g, language, country, 100) }.getOrDefault(emptyList()))
            }
            while (out.size < count && pool.isNotEmpty()) {
                // Check languages a handful at a time (each one reads the start of a feed).
                val batch = generateSequence { pool.removeFirstOrNull() }
                    .filter { it.feedUrl !in exclude && out.none { o -> o.podcast.feedUrl == it.feedUrl } }
                    .take(count * 3).toList()
                if (batch.isEmpty()) break
                val declared = feedLanguage.ofAll(batch.map { it.feedUrl })
                val picks = batch.map { Pick(it, LevelGuess.estimate(it.title, it.summary, g, it.genre)) }
                val kept = Recommendations.verify(picks, declared, language, native)
                // Put back what wasn't needed this time, for the next tap.
                val take = kept.take(count - out.size)
                out += take
                kept.drop(take.size).asReversed().forEach { pool.addFirst(it.podcast) }
            }
            if (out.size >= count) break
        }
        out
    }
}
