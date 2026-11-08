package com.ascolta.app

import com.ascolta.app.data.Flashcards
import com.ascolta.app.data.Flashcards.Grade
import com.ascolta.app.data.VocabEntity
import com.ascolta.app.data.flagEmoji
import com.ascolta.app.data.languageFlag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlashcardsTest {
    private val day = 24L * 3600 * 1000
    private val card = VocabEntity(word = "casa", translation = "house", sentence = null, sentenceTranslation = null,
        language = "it", episodeId = null, episodeTitle = null, createdAt = 0)

    @Test
    fun goodAnswersSpaceReviewsFurtherApart() {
        val first = Flashcards.schedule(card, Grade.GOOD, 0)
        assertEquals(day, first.dueAt)
        val second = Flashcards.schedule(first, Grade.GOOD, first.dueAt)
        assertEquals(first.dueAt + 3 * day, second.dueAt)
        val third = Flashcards.schedule(second, Grade.GOOD, second.dueAt)
        assertEquals(7.5f, third.intervalDays, 0.01f) // 3 days × ease 2.5
    }

    @Test
    fun forgottenCardsComeBackSoonAndGetHarder() {
        val learned = Flashcards.schedule(Flashcards.schedule(card, Grade.GOOD, 0), Grade.GOOD, day)
        val forgotten = Flashcards.schedule(learned, Grade.AGAIN, 10 * day)
        assertEquals(10 * day + 60_000, forgotten.dueAt)
        assertEquals(0, forgotten.reps)
        assertTrue(forgotten.ease < learned.ease)
        assertTrue(Flashcards.schedule(card, Grade.EASY, 0).dueAt > Flashcards.schedule(card, Grade.GOOD, 0).dueAt)
        // Ease never drops below 1.3.
        var c = card
        repeat(20) { c = Flashcards.schedule(c, Grade.AGAIN, 0) }
        assertEquals(1.3f, c.ease, 0.001f)
    }

    @Test
    fun flags() {
        assertEquals("🇮🇹", flagEmoji("it"))
        assertEquals(flagEmoji("BR"), languageFlag("pt"))
        assertEquals(flagEmoji("CN"), languageFlag("zh"))
    }
}
