package com.ascolta.app

import com.ascolta.app.data.WordSplitter
import com.ascolta.app.whisper.SpeechAlignment
import com.ascolta.app.whisper.SpeechModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {
    @Test
    fun recommendedModelPerLanguage() {
        assertEquals(SpeechModel.PARAKEET, SpeechModel.recommended("it"))
        assertEquals(SpeechModel.SENSEVOICE, SpeechModel.recommended("ja"))
        assertEquals(SpeechModel.SENSEVOICE, SpeechModel.recommended("zh"))
        assertEquals(SpeechModel.BASE, SpeechModel.recommended("th"))
        assertTrue(SpeechModel.REAZONSPEECH.supports("ja") && !SpeechModel.REAZONSPEECH.supports("it"))
        assertTrue(SpeechModel.KOTOBA.supports("ja") && SpeechModel.SMALL_GPU.gpu)
        // Old choices still resolve.
        assertEquals(SpeechModel.PARAKEET, SpeechModel.byId("parakeet-v3-npu"))
    }

    @Test
    fun japaneseTokensKeepTheirOwnTimes() {
        // SenseVoice-style tokens: no spaces, one time per token, "。" ends the sentence.
        val tokens = listOf("今日", "は", "いい", "天気", "です", "。", "明日", "は", "雨")
        val times = listOf(0.0f, 0.4f, 0.6f, 0.9f, 1.3f, 1.6f, 2.5f, 2.9f, 3.1f)
        val out = SpeechAlignment.sentencesFromTokens(tokens, times, 0, 3_500, "")
        assertEquals(listOf("今日はいい天気です。", "明日は雨"), out.map { it.text })
        val first = out[0]
        val words = WordSplitter.timedWords(first.text, first.words, first.startMs, first.endMs, "ja")!!
        assertTrue(words.size >= 4)
        assertEquals(900L, words.first { it.text.startsWith("天") }.startMs)
    }

    @Test
    fun longPauseEndsASentenceWithoutPunctuation() {
        // ReazonSpeech gives no punctuation: a 1 s pause splits sentences.
        val tokens = listOf("きょう", "は", "はれ", "です", "あした", "は", "あめ")
        val times = listOf(0.0f, 0.3f, 0.5f, 0.8f, 2.0f, 2.3f, 2.5f)
        val out = SpeechAlignment.sentencesFromTokens(tokens, times, 0, 3_000, "")
        assertEquals(2, out.size)
    }
}
