package com.ascolta.app

import com.ascolta.app.data.DoubtfulWords
import com.ascolta.app.data.WordLists
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DoubtfulWordsTest {
    private fun asset(name: String) = listOf(File("src/main/assets/$name"), File("app/src/main/assets/$name")).firstOrNull { it.exists() }?.inputStream()
    private val lists = WordLists({ asset(WordLists.assetName(it)) }, { asset(WordLists.moreName(it)) })

    private fun doubts(text: String, language: String, names: List<String> = emptyList()) =
        DoubtfulWords.find(text, language, lists, names).map { text.substring(it) }

    @Test
    fun aWordThatDoesNotExistIsDoubtfulAndRealCompoundsAreNot() {
        assertEquals(listOf("kläddsträger"), doubts("Vad som gör att folk får olika kläddsträger.", "sv"))
        assertTrue(doubts("Vad som gör att folk får olika klädstilar.", "sv").isEmpty())
        assertTrue(doubts("Vi gick till omklädningsrummet efter fotbollsmatchen och pratade om klimatförändringarna.", "sv").isEmpty())
        assertTrue(doubts("Die Geschwindigkeitsbegrenzung auf der Autobahn bleibt ein Wahlkampfthema.", "de").isEmpty())
        assertTrue(doubts("Das ist außerhalb der größten Stadt, einschließlich dreißig Häuser.", "de").isEmpty()) // ß
        assertEquals(listOf("verschtändlich"), doubts("Das ist doch selbstverständlich und verschtändlich.", "de"))
        assertTrue(doubts("Il faut absolument qu'on parle de l'élasticité des prix aujourd'hui.", "fr").isEmpty())
        assertEquals(listOf("incroillable"), doubts("C'est vraiment incroillable, cette histoire.", "fr"))
    }

    @Test
    fun namesShortWordsEnglishAndOtherLanguagesAreLeftAlone() {
        assertTrue(doubts("Jag pratade med Zlatanovich i Skellefteå.", "sv").isEmpty()) // capitalised
        assertTrue(doubts("Det var en riktig blorp.", "sv").isEmpty()) // under six letters
        assertTrue(doubts("Vi lyssnar på en podcast om streaming och software.", "sv").isEmpty()) // English
        assertTrue(doubts("det är zlatanovich igen", "sv", names = listOf("Zlatanovich")).isEmpty())
        assertTrue(doubts("To je naprosto nesmyslovité.", "cs").isEmpty()) // not covered
        assertTrue(DoubtfulWords.supports("sv", lists))
        assertTrue(!DoubtfulWords.supports("cs", lists) && !DoubtfulWords.supports("ja", lists))
    }

    @Test
    fun onlyAOneLetterSlipWithASingleCandidateIsCorrected() {
        val (text, fixed) = DoubtfulWords.correct("Det är en viktig förändrring för alla.", "sv", lists)
        assertEquals("Det är en viktig förändring för alla.", text)
        assertEquals(mapOf("förändrring" to "förändring"), fixed)
        // Too far from any word: left for the second model.
        assertEquals("olika kläddsträger" to emptyMap<String, String>(), DoubtfulWords.correct("olika kläddsträger", "sv", lists))
        assertEquals("C'est incroillable." to emptyMap<String, String>(), DoubtfulWords.correct("C'est incroillable.", "fr", lists))
        assertEquals(emptyMap<String, String>(), DoubtfulWords.correct("Vi pratade om klädstilar.", "sv", lists).second)
    }

    @Test
    fun suggestionsAreTheNearestRealWords() {
        assertEquals("incroyable", DoubtfulWords.suggestions("incroyabble", "fr", lists).first())
        assertEquals("förändring", DoubtfulWords.suggestions("förändrring", "sv", lists).first())
        assertTrue(DoubtfulWords.suggestions("kläddsträger", "sv", lists).size <= 3)
        assertEquals(1, DoubtfulWords.distance("abcd", "abdc", 2))
        assertEquals(3, DoubtfulWords.distance("abcdef", "xyzdef", 2))
    }

    @Test
    fun aSecondVersionReplacesTheSentenceOnlyWhenItIsBetter() {
        val original = "Vad som gör att folk får olika kläddsträger."
        assertNull(DoubtfulWords.rejection(original, "Vad som gör att folk får olika klädstilar.", "sv", lists))
        assertNotNull(DoubtfulWords.rejection(original, "Vad som gör att folk får olika kläddsträgar.", "sv", lists))
        assertNotNull(DoubtfulWords.rejection(original, "Tack.", "sv", lists))
        assertNotNull(DoubtfulWords.rejection(original, "", "sv", lists))
    }

    @Test
    fun compoundsCanBeTranslatedByTheirParts() {
        assertEquals(listOf("hund", "kläder"), DoubtfulWords.parts("hundkläder", "sv", lists))
        assertNull(DoubtfulWords.parts("kläder", "sv", lists))
        assertNull(DoubtfulWords.parts("incroyable", "fr", lists))
    }
}
