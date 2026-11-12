package com.ascolta.app

import com.ascolta.app.data.Segment
import com.ascolta.app.data.Word
import com.ascolta.app.work.Holes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HolesTest {
    private fun seg(startS: Double, endS: Double, vararg wordStarts: Double) = Segment(
        (startS * 1000).toLong(), (endS * 1000).toLong(), wordStarts.joinToString(" ") { "w" },
        words = wordStarts.map { Word((it * 1000).toLong(), "w") }.takeIf { it.isNotEmpty() },
    )

    @Test
    fun aSkippedStretchInsideSpeechIsFound() {
        // Speech from 540 s to 560 s; words until 545.4 s, then again from 554 s (9 s skipped).
        val speech = listOf(540_000L to 560_000L)
        val segments = listOf(seg(540.0, 545.5, 540.0, 541.2, 542.4, 543.6, 544.8), seg(554.0, 560.0, 554.0, 556.0, 558.0, 559.4))
        val holes = Holes.find(speech, segments, 540_000, 560_000)
        assertEquals(1, holes.size)
        assertEquals(545_500L, holes[0].startMs)
        assertEquals(554_000L, holes[0].endMs)
    }

    @Test
    fun aGapInsideASentenceCountsToo() {
        // One sentence whose words jump from 83 s to 89 s although the speaker went on.
        val holes = Holes.find(listOf(80_000L to 92_000L), listOf(seg(80.0, 92.0, 80.0, 81.0, 82.0, 83.0, 89.0, 90.0, 91.3)), 80_000, 92_000)
        assertEquals(listOf(83_700L to 89_000L), holes.map { it.startMs to it.endMs })
    }

    @Test
    fun pausesMusicAndSilenceAreNotHoles() {
        val segments = listOf(seg(0.0, 4.0, 0.0, 1.0, 2.0, 3.3), seg(5.5, 9.0, 5.5, 7.0, 8.3))
        // A 1.5 s pause between sentences.
        assertTrue(Holes.find(listOf(0L to 9_000L), segments, 0, 9_000).isEmpty())
        // Ten seconds of music after the speech: no speech detected there.
        assertTrue(Holes.find(listOf(0L to 9_000L), segments, 0, 19_000).isEmpty())
        // Mostly silence with a little detected sound (under 1.5 s).
        assertTrue(Holes.find(listOf(0L to 9_000L, 12_000L to 13_000L), segments, 0, 19_000).isEmpty())
        // Without word times, the sentences' own spans are used.
        assertTrue(Holes.find(listOf(0L to 9_000L), listOf(seg(0.0, 4.0), seg(5.0, 9.0)), 0, 9_000).isEmpty())
    }

    @Test
    fun atMostThreeHolesPerBlockTheLongestFirst() {
        val speech = listOf(0L to 60_000L)
        val segments = (0 until 6).map { i -> seg(i * 10.0, i * 10.0 + 2.0, i * 10.0, i * 10.0 + 1.3) }
        val holes = Holes.find(speech, segments, 0, 60_000)
        assertEquals(3, holes.size)
        assertTrue(holes.zipWithNext().all { (a, b) -> a.startMs < b.startMs })
    }
}
