package com.ascolta.app.data

import com.ascolta.app.net.DirectoryResult
import com.ascolta.app.net.PodcastDirectory
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.Locale

/** A suggested podcast with its estimated level. */
data class Pick(val podcast: DirectoryResult, val level: LevelGuess.Estimate)

/**
 * Podcast suggestions for a learner: courses for the language and the top podcasts of their
 * favourite themes in that language's store (English shows dropped), each with an estimated
 * CEFR level, those at the learner's level first. Used by onboarding, Personalize and Discover.
 */
object Recommendations {
    suspend fun picks(
        directory: PodcastDirectory,
        language: String,
        genres: List<Int>,
        band: LevelBand?,
        limit: Int = 30,
    ): List<Pick> = coroutineScope {
        val country = LANGUAGES.firstOrNull { it.code == language }?.country ?: "US"
        val wanted = genres.filter { it != GENRE_LEARNING }.take(4)
        val courses = async { runCatching { courses(directory, language, country) }.getOrDefault(emptyList()) }
        val themed = wanted.map { g ->
            async { g to runCatching { directory.topByGenre(g, country, 25) }.getOrDefault(emptyList()) }
        }
        val byGenre = themed.awaitAll()
        merge(
            language = language,
            courses = courses.await(),
            byGenre = byGenre,
            band = band,
            limit = limit,
            // Beginners mostly want courses; others mostly real podcasts.
            coursesFirst = band == LevelBand.BEGINNER || wanted.isEmpty(),
        )
    }

    /** Courses for [language] (Language Learning genre), in its store and the US store. */
    suspend fun courses(directory: PodcastDirectory, language: String, country: String): List<DirectoryResult> = coroutineScope {
        val names = languageNames(language)
        val found = names.flatMap { name -> listOf(country, "US").distinct().map { c -> async { runCatching { directory.searchGenre(name, GENRE_LEARNING, c) }.getOrDefault(emptyList()) } } }
            .awaitAll().flatten().distinctBy { it.feedUrl }
        found.filter { r -> names.any { n -> "${r.title} ${r.summary.orEmpty()}".contains(n, ignoreCase = true) } }.ifEmpty { found }
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
        val coursePicks = courses.map { Pick(it, LevelGuess.estimate(it.title, it.summary, GENRE_LEARNING, it.genre)) }
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
}
