package com.ascolta.app

import com.ascolta.app.data.AdDetector
import com.ascolta.app.data.KnownWords
import com.ascolta.app.data.Segment
import com.ascolta.app.work.TranscriptionPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AdsAndKnownWordsTest {
    private fun seg(s: Long, e: Long, t: String) = Segment(s * 1000, e * 1000, t)

    @Test
    fun anAdReadIsFoundAndStretchedToTheMusicAroundIt() {
        val segments = listOf(
            seg(0, 6, "Bienvenue dans ce nouvel épisode."),
            seg(10, 16, "Cet épisode est présenté par NordVPN."),
            seg(17, 24, "Avec le code promo LEGEND, profitez de 60 % de réduction."),
            seg(25, 32, "Rendez-vous sur nordvpn point com slash legend."),
            seg(36, 42, "Alors, aujourd'hui, on reçoit Lou."),
            seg(43, 50, "Elle va nous raconter son histoire."),
        )
        val ads = AdDetector.detect(segments)
        assertEquals(1, ads.size)
        assertEquals(7_000L, ads[0].startMs) // 3 s of the gap before
        assertEquals(35_000L, ads[0].endMs)
        assertEquals(ads[0], AdDetector.at(ads, 20_000))
        assertEquals(null, AdDetector.at(ads, 40_000))
    }

    @Test
    fun theShowMentioningAWebsiteOnceIsNotAnAd() {
        val segments = listOf(
            seg(0, 5, "J'ai lu ça sur lemonde point fr hier."),
            seg(6, 12, "Et franchement, ça m'a fait réfléchir."),
            seg(13, 20, "On en parle avec notre invitée."),
        )
        assertTrue(AdDetector.detect(segments).isEmpty())
        assertEquals(0, AdDetector.score("Elle va nous raconter son histoire."))
    }

    @Test
    fun nextSentenceAlwaysMovesForward() {
        val starts = listOf(0L, 5_000L, 70_000L)
        assertEquals(5_000L, TranscriptionPlan.nextTarget(starts, 1_000, 600_000))
        assertEquals(70_000L, TranscriptionPlan.nextTarget(starts, 10_000, 600_000)) // over an ad in one tap
        assertEquals(95_000L, TranscriptionPlan.nextTarget(starts, 80_000, 600_000)) // nothing ahead: +15 s
        assertEquals(599_000L, TranscriptionPlan.nextTarget(starts, 590_000, 600_000))
    }

    @Test
    fun newWordsAreHighlightedUntilFamiliarAndNamesAndCommonWordsAreLeftAlone() {
        val text = "Le chat de Marie mange l'herbe. Ensuite il dort."
        val levels = mapOf("chat" to KnownWords.KNOWN, "mange" to KnownWords.SEEN, "dort" to KnownWords.FAMILIAR)
        val common = setOf("le", "de", "il")
        val words = KnownWords.newWordRanges(text, levels, common).map { text.substring(it) }
        assertEquals(listOf("mange", "l'herbe", "Ensuite"), words) // Marie: a name; Ensuite starts a sentence
        assertEquals("homme", KnownWords.key("L'homme,"))
        val (share, newWords) = KnownWords.coverage(listOf(text, "Le chat mange."), levels, common)
        assertEquals(listOf("mange" to 2, "herbe" to 1, "ensuite" to 1), newWords)
        assertTrue(share > 0.5f && share < 0.8f)
    }

    @Test
    fun levelsAreRememberedPerLanguage() {
        val dir = Files.createTempDirectory("known").toFile()
        val store = KnownWords(dir)
        store.set("fr", "L'herbe", KnownWords.KNOWN)
        store.set("fr", "Marie", KnownWords.IGNORED)
        assertEquals(KnownWords.KNOWN, KnownWords(dir).level("fr", "herbe"))
        assertEquals(null, KnownWords(dir).level("it", "herbe"))
        assertFalse(KnownWords.highlighted(KnownWords(dir).level("fr", "marie")))
        assertTrue(KnownWords.highlighted(null))
        dir.deleteRecursively()
    }
}
