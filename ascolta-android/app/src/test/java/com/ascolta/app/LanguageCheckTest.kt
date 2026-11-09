package com.ascolta.app

import com.ascolta.app.data.LanguageCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real titles from Apple's Georgia store and searches (November 2026). */
class LanguageCheckTest {
    private fun keep(title: String, declared: String?, target: String = "ka", native: String = "en", course: Boolean = false) =
        LanguageCheck.keep(title, null, declared, target, native, course)

    @Test
    fun declaredLanguageCodes() {
        assertEquals("ka", LanguageCheck.normalize("ka-GE"))
        assertEquals("ka", LanguageCheck.normalize("ge"))
        assertEquals("ru", LanguageCheck.normalize(" ru-ru "))
        assertEquals("he", LanguageCheck.normalize("iw"))
        assertEquals(null, LanguageCheck.normalize(""))
    }

    @Test
    fun russianShowsInGeorgiasChartsAreDropped() {
        assertFalse(keep("Эхо Москвы", null))
        assertFalse(keep("Радио Медуза", "ru"))
        assertFalse(keep("Global News Podcast", "en"))
        assertFalse(keep("Das Thema", null)) // Latin letters aren't Georgian
        assertTrue(keep("ნიკოს პოდკასტი", null))
        assertTrue(keep("ლიტერატურული პოდკასტი \"წიგნები\"", "ka"))
        assertFalse(keep("ნიკოს პოდკასტი", "ru")) // the feed's own declaration wins
    }

    @Test
    fun coursesInTheLearnersLanguageMustBeCourses() {
        assertTrue(keep("Learn Georgian with Fexingo", "en", course = true))
        assertFalse(keep("The Georgian Impact Podcast | AI, ML & More", "en", course = true))
        assertFalse(keep("Грузинский язык для начинающих", "ru", course = true)) // Russian-medium, for an English speaker
        assertTrue(keep("Грузинский язык для начинающих", "ru", native = "ru", course = true))
    }

    @Test
    fun alphabets() {
        assertTrue(LanguageCheck.scriptMatches("Подкаст о жизни", "uk"))
        assertFalse(LanguageCheck.scriptMatches("Подкаст о жизни", "ka"))
        assertTrue(LanguageCheck.scriptMatches("ポッドキャスト 日本語", "ja"))
        assertTrue(LanguageCheck.scriptMatches("Il Post", "it"))
        assertFalse(LanguageCheck.scriptMatches("Новости дня", "it"))
    }
}
