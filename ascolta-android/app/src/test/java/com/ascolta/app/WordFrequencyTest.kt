package com.ascolta.app

import com.ascolta.app.data.WordFrequency
import com.ascolta.app.data.WordLists
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WordFrequencyTest {
    private val lists = WordLists { lang ->
        listOf(File("src/main/assets/" + WordLists.assetName(lang)), File("app/src/main/assets/" + WordLists.assetName(lang))).firstOrNull { it.exists() }?.inputStream()
    }

    @Test
    fun commonWordsGetTheFirstBandAndRareOnesNone() {
        assertEquals(0, WordFrequency.band(0)); assertEquals(0, WordFrequency.band(999))
        assertEquals(1, WordFrequency.band(1_000)); assertEquals(2, WordFrequency.band(14_999))
        assertNull(WordFrequency.band(15_000)); assertNull(WordFrequency.band(null))
        val fr = lists.ranks("fr")!!
        val text = "Le chat regarde l'horizon avec mélancolie, dit Marie."
        val bands = WordFrequency.of(text, "fr", fr).associate { text.substring(it.first) to it.second }
        assertEquals(0, bands["Le"]); assertEquals(0, bands["avec"])
        assertTrue(bands.containsKey("l'horizon")) // counted as "horizon"
        assertTrue("Marie" !in bands) // a name
    }

    @Test
    fun japaneseHasAListToo() {
        val ja = lists.ranks("ja")!!
        assertEquals(0, WordFrequency.band(WordFrequency.rank("です", ja)))
        assertEquals(0, WordFrequency.band(WordFrequency.rank("日本", ja)))
    }
}
