package com.ascolta.app

import com.ascolta.app.whisper.SpeechDetection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextPiecesTest {
    @Test
    fun shortRepliesAreRecognisedWithTheSpeechAroundThem() {
        // "Tu sais ?" (1.5 s), a 0.6 s pause, an answer, a 1.8 s pause, another reply.
        val regions = listOf(0L to 1_500L, 2_100L to 6_000L, 7_800L to 9_000L)
        assertEquals(listOf(0L to 9_000L), SpeechDetection.groupForContext(regions))
    }

    @Test
    fun longPausesSeparatePiecesAndNoPieceGoesPast20Seconds() {
        // A 3 s pause (music, a jingle) keeps two pieces apart.
        assertEquals(
            listOf(0L to 4_000L, 7_000L to 9_000L),
            SpeechDetection.groupForContext(listOf(0L to 4_000L, 7_000L to 9_000L)),
        )
        // Many short bits one second apart: joined up to 20 s, then a new piece.
        val bits = (0 until 15).map { i -> i * 3_000L to i * 3_000L + 2_000L }
        val pieces = SpeechDetection.groupForContext(bits)
        assertTrue(pieces.all { it.second - it.first <= 20_000 })
        assertEquals(bits.first().first, pieces.first().first)
        assertEquals(bits.last().second, pieces.last().second)
        assertTrue(pieces.size in 2..4)
        // Overlapping regions (from parallel speech detection) are merged first.
        assertEquals(listOf(0L to 5_000L), SpeechDetection.groupForContext(listOf(0L to 3_000L, 2_500L to 5_000L)))
    }
}
