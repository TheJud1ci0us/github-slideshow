package com.ascolta.app

import com.ascolta.app.data.Word
import com.ascolta.app.data.WordSplitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordSplitterTest {
    @Test
    fun spacedLanguagesKeepTheirWords() {
        val text = "Bevo un bicchiere dell'acqua, grazie!"
        val words = WordSplitter.ranges(text, "it").map { text.substring(it.first, it.last + 1) }
        assertEquals(listOf("Bevo", "un", "bicchiere", "dell'acqua", "grazie"), words)
        assertEquals("bicchiere", WordSplitter.wordAt(text, 10, "it"))
        assertEquals("grazie", WordSplitter.wordAt(text, text.indexOf('!'), "it")) // just after the word
    }

    @Test
    fun japaneseIsNotOneBigWord() {
        val text = "今日はいい天気ですね。"
        val words = WordSplitter.ranges(text, "ja")
        // On the phone ICU's dictionary gives 今日 / は / いい / 天気 / です / ね; the desktop JDK
        // used for this test splits more coarsely, but never into a single sentence-long word.
        assertTrue("got ${words.map { text.substring(it.first, it.last + 1) }}", words.size >= 3)
        assertTrue(words.none { it.last - it.first + 1 >= text.length - 1 })
        val tapped = WordSplitter.wordAt(text, 5, "ja")!!
        assertTrue(tapped.length < 6)
    }

    @Test
    fun wordsGetTimesFromTokens() {
        // Tokens as whisper gives them: leading spaces, a word split in two tokens.
        val pieces = listOf(Word(0, " Bevo"), Word(400, " un"), Word(600, " bic"), Word(800, "chiere"))
        val words = WordSplitter.timedWords("Bevo un bicchiere", pieces, 0, 1_200, "it")!!
        assertEquals(listOf("Bevo", "un", "bicchiere"), words.map { it.text })
        assertEquals(listOf(0L, 400L, 600L), words.map { it.startMs })
    }

    @Test
    fun oldSentenceLongTimingIsSpreadOverItsWords() {
        // Transcripts made before this fix: the whole Japanese sentence was one timed "word".
        val text = "今日はいい天気です"
        val words = WordSplitter.timedWords(text, listOf(Word(1_000, text)), 1_000, 1_900, "ja")!!
        assertTrue(words.size >= 3)
        assertEquals(1_000L, words.first().startMs)
        assertTrue(words.zipWithNext().all { (a, b) -> b.startMs > a.startMs })
        assertTrue(words.last().startMs < 1_900)
    }
}
