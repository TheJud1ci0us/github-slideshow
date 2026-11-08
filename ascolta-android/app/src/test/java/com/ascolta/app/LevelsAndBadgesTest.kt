package com.ascolta.app

import com.ascolta.app.data.Badges
import com.ascolta.app.data.GENRE_LEARNING
import com.ascolta.app.data.LevelBand
import com.ascolta.app.data.LevelGuess
import com.ascolta.app.data.Stats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelsAndBadgesTest {
    @Test
    fun levelFromTitleAndTheme() {
        assertEquals("A1–A2", LevelGuess.estimate("Corso di italiano A1 - A2", null).label)
        assertEquals(LevelBand.BEGINNER, LevelGuess.estimate("Italiano facile e lento", "Per stranieri che vogliono imparare").band)
        assertEquals(LevelBand.BEGINNER, LevelGuess.estimate("Coffee Break Italian", null, GENRE_LEARNING).band)
        assertEquals("B1–B2", LevelGuess.estimate("Italian podcast for intermediate learners", null).label)
        assertEquals(LevelBand.ADVANCED, LevelGuess.estimate("Il Post — Morning", "Le notizie del giorno", 1489).band)
        assertEquals(LevelBand.INTERMEDIATE, LevelGuess.estimate("Storie per bambini", null, 1305).band)
    }

    @Test
    fun englishShowsAreRecognised() {
        assertTrue(LevelGuess.looksEnglish("6 Minute English: learn and practise useful English with the BBC every week"))
        assertFalse(LevelGuess.looksEnglish("Le notizie del giorno raccontate con calma e chiarezza da Roma"))
    }

    @Test
    fun listeningMilestonesAndBadges() {
        val hour = 3_600_000L
        val stats = Stats(listenedMs = mapOf("it" to 6 * hour, "es" to hour / 2), bestStreak = 7, wordsSaved = 60)
        val earned = Badges.all().filter { it.earned(stats) }.map { it.id }.toSet()
        assertTrue("listen-5" in earned)
        assertFalse("listen-10" in earned)
        assertTrue("streak-7" in earned)
        assertFalse("streak-30" in earned)
        assertTrue("words-50" in earned)
        assertEquals(10, Badges.nextListening(6.0))
        assertEquals(null, Badges.nextListening(1500.0))
    }
}
