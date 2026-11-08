package com.ascolta.app

import com.ascolta.app.data.Segment
import com.ascolta.app.data.Span
import com.ascolta.app.whisper.SpeechDetection
import com.ascolta.app.work.BlockCut
import com.ascolta.app.work.BufferPolicy
import com.ascolta.app.work.SentenceMerge
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
    fun onlyTwentyPercentAheadOfTheListener() {
        // 60-minute episode: the zone is 12 minutes.
        val total = 60 * min
        assertEquals(12 * min, BufferPolicy.zoneMs(total))
        // Listener at minute 10, nothing done: work starts at minute 10.
        assertEquals(Span(10 * min, 11 * min), BufferPolicy.nextBlock(emptyList(), 10 * min, total, min))
        // Minutes 10–21.5 done: the last block stops at the zone's end (minute 22).
        val done = listOf(Span(10 * min, 21 * min + 30_000))
        assertEquals(Span(21 * min + 30_000, 22 * min), BufferPolicy.nextBlock(done, 10 * min, total, min))
        // Zone full: nothing more, even though the rest of the episode isn't transcribed.
        assertNull(BufferPolicy.nextBlock(listOf(Span(10 * min, 22 * min)), 10 * min, total, min))
        assertEquals(12 * min, BufferPolicy.aheadMs(listOf(Span(10 * min, 22 * min)), 10 * min, total))
        // It resumes after the listener has used up one block of it.
        assertEquals(11 * min, BufferPolicy.resumeBelowMs(total, min))
        // Never behind the listener: minute 0–10 stays untouched.
        assertNull(BufferPolicy.nextBlock(listOf(Span(10 * min, total)), 10 * min, total, min))
        // Short episode: at least 30 s ahead.
        assertEquals(30_000L, BufferPolicy.zoneMs(2 * min))
    }

    @Test
    fun blocksEndInAPause() {
        // Speech runs into the end of a 60 s block; the last pause is at 55.0–55.4 s.
        val speech = listOf(0L to 30_000L, 30_500L to 55_000L, 55_400L to 60_000L)
        assertEquals(55_200L, BlockCut.atPause(speech, 60_000))
        // Already ends in silence: no cut.
        assertNull(BlockCut.atPause(listOf(0L to 50_000L), 60_000))
        // Quietest point of the signal when there are no speech regions.
        val samples = FloatArray(16_000 * 30) { if (it in 16_000 * 25 until 16_000 * 25 + 1_600) 0f else 0.5f }
        val cut = BlockCut.quietest(samples)!!
        assertTrue("cut at $cut", cut in 25_000L..25_100L)
    }

    @Test
    fun repeatedSpeechIsKeptOnce() {
        val first = Segment(0, 5_000, "Oggi parliamo di cucina italiana.")
        // The same sentence recognised again (overlapping): dropped.
        assertEquals(SentenceMerge.Result.Drop, SentenceMerge.resolve(listOf(first), Segment(200, 5_100, "oggi parliamo di cucina italiana")))
        // A word cut in two at a block boundary: the repeated words are removed.
        val prev = Segment(5_000, 10_000, "La pasta si cuoce in acqua salata")
        val r = SentenceMerge.resolve(listOf(first, prev), Segment(10_000, 14_000, "acqua salata per dieci minuti."))
        assertEquals("per dieci minuti.", (r as SentenceMerge.Result.Add).segment.text)
        // A sentence repeated right after itself (recogniser loop): dropped.
        assertEquals(
            SentenceMerge.Result.Drop,
            SentenceMerge.resolve(listOf(first), Segment(6_000, 11_000, "Oggi parliamo di cucina italiana.")),
        )
        // Different speech is kept; short repeated words are fine.
        assertTrue(SentenceMerge.resolve(listOf(prev), Segment(10_000, 12_000, "E poi?")) is SentenceMerge.Result.Add)
        assertEquals(0, SentenceMerge.overlapWords(listOf("vado", "e"), listOf("e", "torno")))
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
