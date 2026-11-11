package com.ascolta.app

import com.ascolta.app.data.SentenceLanguage
import com.ascolta.app.data.WordLists
import com.ascolta.app.work.TranscriptionPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WrongLanguageTest {
    private val lists = WordLists { lang ->
        listOf(File("src/main/assets/words/$lang.txt.gz"), File("app/src/main/assets/words/$lang.txt.gz"))
            .firstOrNull { it.exists() }?.inputStream()
    }

    private fun wrong(text: String, lang: String) = SentenceLanguage.isEnglishInstead(text, lang, lists)

    @Test
    fun shortEnglishSlipsAreCaught() {
        for (lang in listOf("fr", "it", "de")) {
            assertTrue(lang, wrong("Exactly.", lang))
            assertTrue(lang, wrong("Yes, exactly.", lang))
            assertTrue(lang, wrong("Of course.", lang))
            assertTrue(lang, wrong("So what do you think?", lang))
        }
    }

    @Test
    fun loanwordsAndRealSentencesAreLeftAlone() {
        listOf(
            "OK, d'accord.", "Le parking est plein.", "On a un meeting demain.", "C'est le week-end.",
            "Exactement.", "Netflix et Google.", "Tu regardes The Voice ?", "Oui, voilà.",
        ).forEach { assertFalse(it, wrong(it, "fr")) }
        listOf("Esatto.", "Il weekend è finito.", "Okay, va bene.").forEach { assertFalse(it, wrong(it, "it")) }
        listOf("Genau.", "Das Meeting ist morgen.", "Ja, genau.").forEach { assertFalse(it, wrong(it, "de")) }
        // The episode's names don't count as English.
        assertFalse(SentenceLanguage.isEnglishInstead("Joe Biden.", "fr", lists, names = listOf("Joe Biden")))
        assertFalse(wrong("Exactly.", "en"))
    }

    @Test
    fun whisperSentencesGoBackToTheRightSlip() {
        val targets = listOf(1_000L to 2_000L, 5_000L to 7_000L)
        val parts = listOf(Triple(950L, 1_900L, "Exactement."), Triple(5_100L, 6_000L, "Bien sûr"), Triple(6_000L, 7_200L, "que oui."))
        assertEquals(listOf("Exactement.", "Bien sûr que oui."), TranscriptionPlan.assignByTime(targets, parts))
        assertEquals(listOf(""), TranscriptionPlan.assignByTime(listOf(0L to 1_000L), emptyList()))
    }
}
