package com.ascolta.app

import com.ascolta.app.data.LevelBand
import com.ascolta.app.data.Recommendations
import com.ascolta.app.net.DirectoryResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationsTest {
    private fun pod(title: String, summary: String? = null) =
        DirectoryResult(title = title, author = null, feedUrl = "https://feeds/${title.hashCode()}", artworkUrl = null, genre = null, summary = summary)

    private val courses = listOf(pod("Italiano facile per principianti"), pod("Learn Italian A2"))
    private val news = 1489 to listOf(
        pod("Il Post", "Le notizie del giorno raccontate da Milano"),
        pod("6 Minute English", "Learn and practise useful English with the BBC every week for your work"),
        pod("Learn Italian A2"), // duplicate of a course
    )
    private val history = 1487 to listOf(pod("Storie di storia", "Racconti dal passato"), pod("Wars", "Le guerre del Novecento"))

    @Test
    fun englishShowsAndDuplicatesAreDropped() {
        val picks = Recommendations.merge("it", courses, listOf(news, history), null, 30, coursesFirst = true)
        val titles = picks.map { it.podcast.title }
        assertFalse("6 Minute English" in titles)
        assertEquals(titles.size, titles.distinct().size)
        assertEquals("Italiano facile per principianti", titles.first()) // courses first for beginners
    }

    @Test
    fun themesAreInterleavedAndLevelComesFirst() {
        val picks = Recommendations.merge("it", courses, listOf(news, history), LevelBand.ADVANCED, 30, coursesFirst = false)
        val titles = picks.map { it.podcast.title }
        // Native podcasts (C1–C2) first for an advanced learner, alternating between themes.
        assertEquals(listOf("Il Post", "Storie di storia", "Wars"), titles.take(3))
        assertTrue(picks.take(3).all { it.level.band == LevelBand.ADVANCED })
        assertEquals(2, Recommendations.merge("it", courses, listOf(news, history), null, 2, coursesFirst = true).size)
    }
}
