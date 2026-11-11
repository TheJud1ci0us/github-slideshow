package com.ascolta.app

import com.ascolta.app.data.SentenceLanguage
import com.ascolta.app.whisper.SpeechDetection
import com.ascolta.app.work.TranscribeWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GapsAndLanguageTest {
    @Test
    fun longStretchesWithoutDetectedSpeechAreCheckedToo() {
        // A minute of talk over music (10–70 s) the detector missed, short pauses kept as they are.
        val speech = listOf(0L to 9_000L, 12_000L to 10_000L + 2_000L + 1_000L, 70_000L to 80_000L)
        val filled = SpeechDetection.fillGaps(speech, 90_000)
        assertEquals(listOf(0L to 9_000L, 12_000L to 13_000L, 13_000L to 70_000L, 70_000L to 80_000L, 80_000L to 90_000L), filled)
        assertEquals(57_000L + 10_000L, SpeechDetection.gapMs(speech, filled))
        // Gaps under 6 s, and a block with no speech found at all.
        assertEquals(listOf(1_000L to 4_000L, 9_000L to 12_000L), SpeechDetection.fillGaps(listOf(1_000L to 4_000L, 9_000L to 12_000L), 14_000))
        assertEquals(listOf(0L to 30_000L), SpeechDetection.fillGaps(emptyList(), 30_000))
    }

    @Test
    fun englishInsteadOfTheLearningLanguage() {
        assertTrue(SentenceLanguage.isEnglishInstead("And that is what we were talking about yesterday.", "fr"))
        assertTrue(SentenceLanguage.isEnglishInstead("I think it's really important for the city.", "it"))
        assertFalse(SentenceLanguage.isEnglishInstead("Et c'est ce dont on parlait hier avec les invités.", "fr"))
        // French with English words, short phrases, titles.
        assertFalse(SentenceLanguage.isEnglishInstead("C'est le week-end, OK, on va voir The Voice ce soir.", "fr"))
        assertFalse(SentenceLanguage.isEnglishInstead("Thank you.", "fr"))
        // Real English in a French episode is flagged too; Whisper then hears English and keeps it.
        assertTrue(SentenceLanguage.isEnglishInstead("Thank you so much.", "fr"))
        assertFalse(SentenceLanguage.isEnglishInstead("Ich glaube, das ist wirklich wichtig für die Stadt.", "de"))
        assertFalse(SentenceLanguage.isEnglishInstead("Creo que es muy importante para la ciudad.", "es"))
        assertFalse(SentenceLanguage.isEnglishInstead("Eu acho que isso é muito importante para a cidade.", "pt"))
        assertTrue(SentenceLanguage.isEnglishInstead("You know, they don't have the money for this.", "pt"))
        // Languages it doesn't check, and English learners.
        assertFalse(SentenceLanguage.isEnglishInstead("And that is what we were talking about.", "en"))
        assertFalse(SentenceLanguage.isEnglishInstead("And that is what we were talking about.", "ja"))
    }

    @Test
    fun whisperGhostLinesInOtherLanguagesAreFiltered() {
        assertTrue(TranscribeWorker.isHallucination("Sous-titrage ST' 501"))
        assertTrue(TranscribeWorker.isHallucination("Sous-titres réalisés par la communauté d'Amara.org"))
        assertTrue(TranscribeWorker.isHallucination("Untertitel im Auftrag des ZDF, 2021"))
        assertFalse(TranscribeWorker.isHallucination("Les sous-titres du film étaient en anglais."))
    }
}
