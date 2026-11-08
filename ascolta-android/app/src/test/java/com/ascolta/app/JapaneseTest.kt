package com.ascolta.app

import com.ascolta.app.data.Japanese
import com.ascolta.app.data.Romaji
import com.ascolta.app.data.WordSplitter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JapaneseTest {
    @Test
    fun romajiIsHepburn() {
        assertEquals("tenki", Romaji.of("テンキ"))
        assertEquals("shinbun", Romaji.of("シンブン"))
        assertEquals("kitte", Romaji.of("キッテ"))
        assertEquals("matcha", Romaji.of("マッチャ"))
        assertEquals("toukyou", Romaji.of("トウキョウ"))
        assertEquals("ko-hi-".replace("-", ""), "kohi")
        assertEquals("koohii", Romaji.of("コーヒー"))
        assertEquals("kin'en", Romaji.of("キンエン"))
        assertEquals("wa", Romaji.of("ハ", particle = true))
        assertEquals("ha", Romaji.of("ハ"))
        assertEquals("chotto", Romaji.of("ちょっと"))
    }

    @Test
    fun readingsFuriganaAndWords() = runBlocking {
        Japanese.load()
        val tokens = Japanese.tokens("今日はいい天気ですね。")!!
        val tenki = tokens.first { it.surface == "天気" }
        assertEquals("てんき", tenki.reading)
        assertEquals("tenki", tenki.romaji)
        assertTrue(tenki.hasKanji)
        assertEquals("wa", tokens.first { it.surface == "は" }.romaji)
        assertEquals("kyou wa ii tenki desu ne 。", Japanese.romaji("今日はいい天気ですね。"))
        // Word-by-word uses the same words.
        val text = "東京へ行きたいと思います。"
        val words = WordSplitter.ranges(text, "ja").map { text.substring(it.first, it.last + 1) }
        assertEquals(listOf("東京", "へ", "行き", "たい", "と", "思い", "ます"), words)
    }
}
