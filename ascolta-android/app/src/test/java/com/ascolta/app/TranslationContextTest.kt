package com.ascolta.app

import com.ascolta.app.data.TranslationContext
import com.ascolta.app.work.BufferPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationContextTest {
    @Test
    fun aFragmentKeepsOnlyItsOwnPartOfThePairsTranslation() {
        assertTrue(TranslationContext.isFragment("Nej."))
        assertTrue(TranslationContext.isFragment("Ja, precis."))
        assertFalse(TranslationContext.isFragment("Vad är svaret på frågan?"))
        assertEquals("Vad är svaret? Nej.", TranslationContext.pair("Vad är svaret?", "Nej."))
        assertEquals("No.", TranslationContext.ownPart("What is the answer?", "What is the answer? No."))
        assertEquals("Exactly.", TranslationContext.ownPart("Is that the answer?", "Is that the answer?  — exactly."))
        // The translator changed the earlier sentence: translate the fragment alone.
        assertNull(TranslationContext.ownPart("What is the answer?", "What's the answer? No."))
        // Nothing left for the fragment.
        assertNull(TranslationContext.ownPart("What is the answer?", "What is the answer?"))
    }

    @Test
    fun theLookAheadFollowsTheSettingAndStaysLongEnoughForSlowModels() {
        val min = 60_000L
        val hour = 60 * min
        assertEquals(2 * min, BufferPolicy.zoneFor(2, hour, slow = false))
        assertEquals(5 * min, BufferPolicy.zoneFor(2, hour, slow = true))
        assertEquals(10 * min, BufferPolicy.zoneFor(10, hour, slow = true))
        assertEquals(hour, BufferPolicy.zoneFor(0, hour, slow = false)) // the whole episode
        assertEquals(3 * min, BufferPolicy.zoneFor(5, 3 * min, slow = false)) // never past the end
        val zone = BufferPolicy.zoneFor(2, hour, slow = false)
        assertEquals(com.ascolta.app.data.Span(10 * min, 11 * min), BufferPolicy.nextBlock(emptyList(), 10 * min, hour, min, zone))
        assertNull(BufferPolicy.nextBlock(listOf(com.ascolta.app.data.Span(10 * min, 12 * min)), 10 * min, hour, min, zone))
        assertEquals(90_000L, BufferPolicy.resumeBelowMs(hour, min, zone))
    }
}
