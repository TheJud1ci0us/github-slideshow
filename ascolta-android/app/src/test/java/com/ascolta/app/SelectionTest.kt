package com.ascolta.app

import com.ascolta.app.data.Segment
import com.ascolta.app.data.Selection
import com.ascolta.app.data.ShadowCheck
import com.ascolta.app.data.Word
import com.ascolta.app.data.WordPos
import com.ascolta.app.ui.wordRanges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionTest {
    private val s1 = Segment(10_000, 13_000, "Oggi il tempo è bello.", "Today the weather is nice.",
        listOf(Word(10_000, "Oggi"), Word(10_400, "il"), Word(10_600, "tempo"), Word(11_000, "è"), Word(11_300, "bello")))
    private val s2 = Segment(13_500, 15_000, "Andiamo al mare!", "Let's go to the sea!",
        listOf(Word(13_500, "Andiamo"), Word(14_000, "al"), Word(14_300, "mare")))
    private val segs = listOf(s1, s2)
    private val timed = segs.map { it.words }
    private val ranges = segs.map { wordRanges(it.text, it.words) }

    @Test
    fun partOfASentence() {
        val sel = Selection.of(segs, timed, ranges, WordPos(0, 1), WordPos(0, 2))
        assertEquals("il tempo", sel.text)
        assertEquals(10_400, sel.startMs)
        assertEquals(11_000, sel.endMs) // where "è" starts
        assertEquals("Oggi il tempo è bello.", sel.context)
        assertFalse(sel.single)
    }

    @Test
    fun acrossTwoSentencesInEitherOrder() {
        val sel = Selection.of(segs, timed, ranges, WordPos(1, 1), WordPos(0, 3))
        assertEquals("è bello. Andiamo al", sel.text)
        assertEquals(11_000, sel.startMs)
        assertEquals(14_300, sel.endMs)
        assertEquals("Today the weather is nice. Let's go to the sea!", sel.contextTranslation)
    }

    @Test
    fun lastWordEndsWithItsSentenceAndOneWordIsAWordCard() {
        val sel = Selection.of(segs, timed, ranges, WordPos(1, 2), WordPos(1, 2))
        assertEquals("mare", sel.text)
        assertEquals(15_000, sel.endMs)
        assertTrue(sel.single)
    }

    @Test
    fun publishedTranscriptsWithoutWordTimesUseWholeSentences() {
        val plain = listOf(s1.copy(words = null))
        val sel = Selection.of(plain, listOf(null), listOf(emptyList()), WordPos(0, 0), WordPos(0, 0))
        assertEquals("Oggi il tempo è bello.", sel.text)
        assertEquals(10_000, sel.startMs)
        assertEquals(13_000, sel.endMs)
    }

    @Test
    fun checkWhatISaid() {
        val r = ShadowCheck.compare("Oggi il tempo è bello.", "oggi tempo è molto bello")
        assertEquals(listOf("Oggi" to true, "il" to false, "tempo" to true, "è" to true, "bello" to true), r.words)
        assertEquals(0.8f, r.score, 0.001f)
        assertEquals(3, ShadowCheck.compare("今日はいい天気", "今日はいい").matched - 2) // character by character
    }

    @Test
    fun punctuationBetweenSentencesCanBeRemoved() {
        val sel = Selection.of(segs, timed, ranges, WordPos(0, 3), WordPos(1, 1))
        assertEquals("è bello. Andiamo al", sel.text)
        assertTrue(sel.canJoin)
        val joined = sel.joined()
        assertEquals("è bello Andiamo al", joined.text)
        assertEquals("Oggi il tempo è bello Andiamo al mare!", joined.context) // the real end is kept
        assertEquals(sel.startMs, joined.startMs)
        // Within one sentence there is nothing to join.
        assertFalse(Selection.of(segs, timed, ranges, WordPos(0, 1), WordPos(0, 2)).canJoin)
    }

    @Test
    fun joinKeepsInnerAndFinalPunctuationAndCapitals() {
        assertEquals("Je vais au marché", Selection.join(listOf("Je vais.", "au marché")))
        assertEquals("Ich gehe, glaube ich Haus?", Selection.join(listOf("Ich gehe, glaube ich…", "Haus?")))
        assertEquals("¿Vas al cine?", Selection.join(listOf("¿Vas?", "¿al cine?")))
        assertEquals("今日は天気がいいですね。", Selection.join(listOf("今日は。", "天気がいいですね。")))
        assertEquals("Ես գնում եմ տուն", Selection.join(listOf("Ես գնում եմ։", "տուն")))
        assertEquals("a b c", Selection.join(listOf("a!", " b ?", "c")))
    }
}
