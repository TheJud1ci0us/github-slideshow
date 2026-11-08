package com.ascolta.app

import com.ascolta.app.whisper.WordAccuracy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordAccuracyTest {
    private val reference = "a piè della scala il dottore accolse la barella condotta da quattro militi della carità " +
        "mentre due questurini ajutati dal portinajo impedivano a una folla di curiosi d'entrare"

    @Test
    fun identicalTextIsPerfect() {
        assertEquals(1f, WordAccuracy.accuracy(reference, "A piè della scala, il dottore accolse la barella condotta da " +
            "quattro militi della carità, mentre due questurini, aiutati dal portinaio, impedivano a una folla di curiosi d’entrare."), 0.001f)
    }

    @Test
    fun countsWrongWords() {
        // Real Whisper Base output on the bundled sample: several words wrong.
        val whisper = "A piede la scala, il dottore accolse la barrella con dotta da quattro militi della carità, " +
            "mentre due questurini aiutati dal portinaio impedivano a una folla di curiosi di entrare."
        val acc = WordAccuracy.accuracy(reference, whisper)
        assertTrue("accuracy $acc", acc in 0.70f..0.90f)
    }

    @Test
    fun emptyTranscriptScoresZero() {
        assertEquals(0f, WordAccuracy.accuracy(reference, ""), 0.001f)
    }
}
