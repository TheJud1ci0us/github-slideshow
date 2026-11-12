package com.ascolta.app

import com.ascolta.app.data.WordSplitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EstimatedWordsTest {
    @Test
    fun wordsWithoutTimesCanStillBeSelected() {
        // A model that gave no word times: each word gets a time from its place in the sentence.
        val words = WordSplitter.estimatedWords("Bonjour à tous, ça va ?", 10_000, 12_200, "fr")
        assertEquals(listOf("Bonjour", "à", "tous", "ça", "va"), words.map { it.text })
        assertEquals(10_000L, words.first().startMs)
        assertTrue(words.zipWithNext().all { (a, b) -> a.startMs < b.startMs })
        assertTrue(words.all { it.startMs in 10_000L..12_200L })
    }
}
