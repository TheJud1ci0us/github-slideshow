package com.ascolta.app

import com.ascolta.app.data.FreeLimits
import com.ascolta.app.whisper.SpeechModel
import com.ascolta.app.work.TranscribeWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LargeV3Test {
    @Test
    fun largeV3IsOfferedForEveryLanguageWithItsExactSize() {
        val m = SpeechModel.MOST_ACCURATE
        assertEquals(SpeechModel.LARGE_V3, m)
        listOf("fr", "it", "ja", "hy", "zh", "en").forEach { assertTrue(it, m.supports(it)) }
        assertEquals(1_081_140_203L, m.files.single().approxBytes)
        assertEquals("ggml-large-v3-q5_0.bin", m.files.single().name)
        assertEquals(m, SpeechModel.byId("large-v3"))
    }

    @Test
    fun largeV3IsProWhenThePaywallIsOnAndEnglishFixingIsOff() {
        assertEquals(SpeechModel.tiers("fr")[1], FreeLimits.allowedModel(SpeechModel.LARGE_V3, "fr", pro = false))
        assertEquals(SpeechModel.LARGE_V3, FreeLimits.allowedModel(SpeechModel.LARGE_V3, "fr", pro = true))
        // English slips are redone with Canary 180M, told the language (French, Spanish, German).
        assertTrue(TranscribeWorker.FIX_WRONG_LANGUAGE)
        val canary = SpeechModel.CANARY_180M
        listOf("fr", "es", "de").forEach { assertTrue(it, canary.supports(it)) }
        assertFalse(canary.supports("it"))
        assertEquals(207_170_046L, canary.files.sumOf { it.approxBytes })
        assertFalse(canary in SpeechModel.tiers("fr")) // a helper, not offered as the main model
    }
}
