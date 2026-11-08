package com.ascolta.app

import com.ascolta.app.data.Word
import com.ascolta.app.whisper.SpeechAlignment
import com.ascolta.app.whisper.SpeechMap
import com.ascolta.app.whisper.CpuTopology
import com.ascolta.app.ui.wordRanges
import java.io.File
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
            listOf(
                WhisperSegment(10_000, 10_600, "Ciao!", listOf(Word(10_000, "Ciao!"))),
                WhisperSegment(10_600, 12_000, "Oggi parliamo.", listOf(Word(10_600, "Oggi"), Word(11_000, "parliamo."))),
            ),
            out,
        )
    }

    @Test
    fun fallsBackToWholeTextWithoutTimestamps() {
        val out = SpeechAlignment.sentencesFromTokens(emptyList(), emptyList(), 0, 3_000, " Buongiorno ")
        assertEquals(listOf(WhisperSegment(0, 3_000, "Buongiorno")), out)
    }

    @Test
    fun speechMapSkipsMusicAndMapsTimesBack() {
        // Speech 0–4.5 s, music/silence until 22.8 s, speech 22.8–28.5 s (30 s block).
        val map = SpeechMap.build(listOf(0L to 4_500L, 22_800L to 28_500L), 30_000, gapMs = 300)
        assertEquals(4_500L + 300 + 5_700, map.joinedLengthMs)
        val audio = FloatArray(30 * 16_000) { if (it / 16_000 in 23..27) 1f else 0f }
        val joined = SpeechMap.joinSpeech(audio, map)
        assertEquals(((4_500 + 300 + 5_700) * 16), joined.size)
        // A sentence the engine found at 5.0 s of joined audio is really at 23.0 s.
        assertEquals(23_000L, map.toOriginal(5_000))
        assertEquals(2_000L, map.toOriginal(2_000))
        // Times in the pause between parts: a start belongs to the next part, an end to the previous.
        assertEquals(22_800L, map.toOriginal(4_600))
        assertEquals(4_500L, map.toOriginal(4_600, isEnd = true))
    }

    @Test
    fun wordRangesFollowRepeatedWords() {
        val text = "La la pasta, la pasta."
        val ranges = wordRanges(text, listOf(Word(0, "La"), Word(1, "la"), Word(2, "pasta,"), Word(3, "la"), Word(4, "pasta.")))
        assertEquals(listOf(0..1, 3..4, 6..11, 13..14, 16..21), ranges)
    }

    @Test
    fun detectsFastAndEfficiencyCores() {
        // Snapdragon 7+ Gen 3 layout: 3×A520, 4×A720, 1×X4 (capacities as reported by Android).
        val root = kotlin.io.path.createTempDirectory("cpu").toFile()
        listOf(0 to 290, 1 to 290, 2 to 290, 3 to 860, 4 to 860, 5 to 860, 6 to 860, 7 to 1024).forEach { (id, cap) ->
            File(root, "cpu$id").mkdirs()
            File(root, "cpu$id/cpu_capacity").writeText("$cap\n")
        }
        File(root, "cpufreq").mkdirs() // not a cpuN folder, ignored
        val topo = CpuTopology.read(root)
        assertEquals(listOf(3, 4, 5, 6, 7), topo.fast)
        assertEquals(listOf(0, 1, 2), topo.efficiency)
        assertEquals(listOf(3, 4, 5, 6, 7), topo.recognitionCores(4))
        assertEquals((0..7).toList(), topo.recognitionCores(8))
        assertEquals(listOf(0, 1, 2), topo.lightCores())
    }

    @Test
    fun packsSpeechIntoFullWindows() {
        val regions = listOf(0L to 3_000L, 4_000L to 8_000L, 9_000L to 12_000L, 20_000L to 35_000L)
        val windows = SpeechAlignment.packWindows(regions, 13_000, 300)
        // 3 s + 0.3 + 4 s + 0.3 + 3 s = 10.6 s fit in one window; the 15 s region is split in two.
        assertEquals(listOf(0L to 3_000L, 4_000L to 8_000L, 9_000L to 12_000L), windows[0])
        assertEquals(listOf(20_000L to 27_500L), windows[1])
        assertEquals(listOf(27_500L to 35_000L), windows[2])
    }
}
