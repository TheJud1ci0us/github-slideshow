package com.ascolta.app

import com.ascolta.app.data.DayTotal
import com.ascolta.app.data.ListeningStats
import com.ascolta.app.whisper.SpeechModel
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class StatisticsTest {
    private fun day(d: LocalDate, minutes: Int, episodes: Int = 1) = DayTotal(d.toEpochDay().toInt(), minutes * 60_000L, episodes)

    @Test
    fun streaksWeeksAndRecords() {
        val today = LocalDate.of(2026, 11, 11) // a Wednesday
        val days = listOf(
            day(LocalDate.of(2026, 10, 20), 30),          // three weeks ago (Tuesday)
            day(LocalDate.of(2026, 11, 2), 111, 3),       // Monday last week: the longest day
            day(LocalDate.of(2026, 11, 9), 20),           // Monday
            day(LocalDate.of(2026, 11, 10), 15),          // Tuesday (yesterday)
        ).sortedBy { it.day }
        val s = ListeningStats.from(days, today)
        assertEquals(0, s.today.ms)
        assertEquals(2, s.dailyStreak)                    // Monday and Tuesday; today not started yet
        assertEquals(2, s.weeklyStreak)                   // this week and last week
        assertEquals(listOf(true, true, false, false, false, false, false), s.weekDays)
        assertEquals(35 * 60_000L, s.thisWeek.ms)
        assertEquals(111 * 60_000L, s.lastWeek.ms)
        assertEquals(LocalDate.of(2026, 11, 2), s.longestDay?.first)
        assertEquals(LocalDate.of(2026, 11, 2), s.biggestWeek?.first)
        assertEquals(YearMonth.of(2026, 11), s.busiestMonth?.first)
        assertEquals(listOf(YearMonth.of(2026, 11), YearMonth.of(2026, 10)), s.months.map { it.first })
        assertEquals(5, s.months.first().third)           // episodes in November
    }

    @Test
    fun threeModelsPerLanguageLightToBest() {
        assertEquals(listOf(SpeechModel.BASE, SpeechModel.SMALL, SpeechModel.TURBO), SpeechModel.tiers("it"))
        assertEquals(listOf(SpeechModel.SENSEVOICE, SpeechModel.REAZONSPEECH, SpeechModel.KOTOBA), SpeechModel.tiers("ja"))
        assertEquals(SpeechModel.OMNILINGUAL, SpeechModel.tiers("hy").last())
        for (lang in listOf("it", "ja", "zh", "hy", "tr", "fa")) {
            assert(SpeechModel.recommended(lang) in SpeechModel.tiers(lang)) { lang }
        }
    }
}
