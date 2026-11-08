package com.ascolta.app

import com.ascolta.app.whisper.SpeechAlignment
import com.ascolta.app.whisper.WhisperSegment
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechAlignmentTest {

    @Test
    fun movesSentenceOutOfMusicBreak() {
        // Same situation as measured with whisper.cpp + VAD: speech, 18 s of silence/music, speech.
        val speech = listOf(0L to 4_490L, 22_780L to 28_540L)
        val segments = listOf(
            WhisperSegment(0, 1_950, "Ciao a tutti e benvenuti!"),
            WhisperSegment(4_400, 26_100, "La pasta al pomodoro è semplice ma buonissima!"),
            WhisperSegment(26_100, 28_110, "Prima si fa bollire l'acqua."),
        )
        val aligned = SpeechAlignment.alignToSpeech(segments, speech)
        assertEquals(0L to 1_950L, aligned[0].startMs to aligned[0].endMs)
        assertEquals(22_780L to 26_100L, aligned[1].startMs to aligned[1].endMs)
        assertEquals(26_100L to 28_110L, aligned[2].startMs to aligned[2].endMs)
    }

    @Test
    fun shortPausesKeepSentencesTogether() {
        val speech = listOf(0L to 2_000L, 2_800L to 5_000L) // 0.8 s pause = same block
        val aligned = SpeechAlignment.alignToSpeech(listOf(WhisperSegment(500, 4_500, "frase")), speech)
        assertEquals(500L to 4_500L, aligned[0].startMs to aligned[0].endMs)
    }

    @Test
    fun splitsLongSpeechIntoPieces() {
        val pieces = SpeechAlignment.splitLong(listOf(0L to 60_000L, 70_000L to 80_000L), 25_000)
        assertEquals(listOf(0L to 20_000L, 20_000L to 40_000L, 40_000L to 60_000L, 70_000L to 80_000L), pieces)
    }

    @Test
    fun buildsSentencesFromParakeetTokens() {
        // Tokens/timestamps as returned by sherpa-onnx for Parakeet v3 (leading space = new word).
        val tokens = listOf(" C", "ia", "o", "!", " Og", "gi", " par", "liamo", ".")
        val times = listOf(0.0f, 0.08f, 0.24f, 0.4f, 0.6f, 0.8f, 1.0f, 1.2f, 1.5f)
        val out = SpeechAlignment.sentencesFromTokens(tokens, times, 10_000, 12_000, "")
        assertEquals(
            listOf(WhisperSegment(10_000, 10_600, "Ciao!"), WhisperSegment(10_600, 12_000, "Oggi parliamo.")),
            out,
        )
    }

    @Test
    fun fallsBackToWholeTextWithoutTimestamps() {
        val out = SpeechAlignment.sentencesFromTokens(emptyList(), emptyList(), 0, 3_000, " Buongiorno ")
        assertEquals(listOf(WhisperSegment(0, 3_000, "Buongiorno")), out)
    }
}
