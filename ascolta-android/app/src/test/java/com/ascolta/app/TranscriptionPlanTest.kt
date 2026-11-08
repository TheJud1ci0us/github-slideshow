package com.ascolta.app

import com.ascolta.app.data.Span
import com.ascolta.app.whisper.SpeechDetection
import com.ascolta.app.work.BufferPolicy
import com.ascolta.app.work.Coverage
import com.ascolta.app.work.TranscriptionPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionPlanTest {
    private val min = 60_000L
    private val episode = 30 * min

    @Test
    fun mergesCoveredStretches() {
        var c = Coverage.add(emptyList(), Span(0, min))
        c = Coverage.add(c, Span(20 * min, 21 * min))
        c = Coverage.add(c, Span(min, 2 * min)) // touches the first one
        assertEquals(listOf(Span(0, 2 * min), Span(20 * min, 21 * min)), c)
        assertEquals(3 * min, Coverage.totalMs(c))
        assertEquals(2 * min, Coverage.runEnd(c, 30_000))
        assertEquals(5 * min, Coverage.runEnd(c, 5 * min)) // not covered: itself
    }

    @Test
    fun jumpToMinute20StartsThereAndKeepsEarlierWork() {
        // Minute 0–1 done; the listener jumps to minute 20.
        val done = listOf(Span(0, min))
        assertEquals(Span(20 * min, 21 * min), TranscriptionPlan.nextBlock(done, 20 * min, episode, min))
        // Later minute 20–25 is done too; back at minute 1, it continues at minute 1 and keeps 20–25.
        val more = Coverage.add(done, Span(20 * min, 25 * min))
        assertEquals(Span(min, 2 * min), TranscriptionPlan.nextBlock(more, 30_000, episode, min))
        // Inside a finished stretch, it continues where that stretch ends.
        assertEquals(Span(25 * min, 26 * min), TranscriptionPlan.nextBlock(more, 22 * min, episode, min))
        // A block never runs into finished work.
        assertEquals(Span(19 * min + 30_000, 20 * min), TranscriptionPlan.nextBlock(Coverage.add(more, Span(0, 19 * min + 30_000)), 0, episode, min))
    }

    @Test
    fun afterTheEndItFillsTheGapsFromTheStart() {
        val done = listOf(Span(0, 5 * min), Span(20 * min, episode))
        assertEquals(Span(5 * min, 6 * min), TranscriptionPlan.nextBlock(done, 29 * min, episode, min))
        assertNull(TranscriptionPlan.nextBlock(listOf(Span(0, episode)), 0, episode, min))
        assertTrue(Coverage.isComplete(listOf(Span(0, episode - 200)), episode)) // tiny tail gap ignored
    }

    @Test
    fun abandonsBlockOnlyWhenTheListenerNeedsSomethingElse() {
        val done = listOf(Span(0, 3 * min))
        val block = Span(3 * min, 4 * min)
        assertFalse(TranscriptionPlan.shouldAbandon(done, 2 * min, block))       // listening just before it
        assertFalse(TranscriptionPlan.shouldAbandon(done, 3 * min + 30_000, block)) // inside it
        assertTrue(TranscriptionPlan.shouldAbandon(done, 20 * min, block))       // jumped ahead
        assertTrue(TranscriptionPlan.shouldAbandon(listOf(Span(2 * min, 3 * min)), 30_000, block)) // jumped back to a gap
    }

    @Test
    fun bufferLimitsDependOnMeasuredSpeed() {
        // Unknown speed, or not faster than playback: never pause.
        assertNull(BufferPolicy.limits(null, 1f, min))
        assertNull(BufferPolicy.limits(1.1f, 1f, min))
        // Parakeet on the CPU (~10x): small buffer is enough.
        val fast = BufferPolicy.limits(10f, 1f, min)!!
        assertEquals(45_000L, fast.resumeBelowMs)
        assertEquals(165_000L, fast.pauseAboveMs)
        // Slower engine, or faster playback: keeps more in reserve.
        val slow = BufferPolicy.limits(2.4f, 1.5f, min)!!
        assertTrue(slow.resumeBelowMs > fast.resumeBelowMs)
        assertTrue(slow.pauseAboveMs > slow.resumeBelowMs)
    }

    @Test
    fun speechDetectionSplitsAcrossCores() {
        assertEquals(listOf(0L to 5_000L), SpeechDetection.partition(5_000, 3)) // short: one part
        val parts = SpeechDetection.partition(60_000, 3)
        assertEquals(listOf(0L to 21_000L, 20_000L to 41_000L, 40_000L to 60_000L), parts)
        assertEquals(
            listOf(0L to 4_000L, 10_000L to 25_000L),
            SpeechDetection.merge(listOf(10_000L to 21_000L, 0L to 4_000L, 20_050L to 25_000L)),
        )
    }
}
