package com.ascolta.app.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * Everything the Statistics screen shows, worked out from the daily totals of the listening
 * log (pure, unit-tested). Days are local calendar days; weeks start on Monday.
 */
data class ListeningStats(
    val today: DayTotal,
    val yesterday: DayTotal,
    val thisWeek: DayTotal,
    val lastWeek: DayTotal,
    /** Monday … Sunday of this week: listened that day? */
    val weekDays: List<Boolean>,
    /** Days in a row with listening, ending today (or yesterday, if today hasn't started yet). */
    val dailyStreak: Int,
    /** Weeks in a row with listening, ending this week (or last week). */
    val weeklyStreak: Int,
    val longestDay: Pair<LocalDate, Long>?,
    /** Monday of the biggest week, and its time. */
    val biggestWeek: Pair<LocalDate, Long>?,
    val busiestMonth: Pair<YearMonth, Long>?,
    val bestYear: Pair<Int, Long>?,
    /** Every month since the log began, newest first: (month, time, episodes). */
    val months: List<Triple<YearMonth, Long, Int>>,
    /** Time in the log (since v0.15). */
    val loggedMs: Long,
) {
    companion object {
        fun from(days: List<DayTotal>, today: LocalDate): ListeningStats {
            val byDay = days.associateBy { it.day }
            fun on(d: LocalDate) = byDay[d.toEpochDay().toInt()] ?: DayTotal(d.toEpochDay().toInt(), 0, 0)
            fun sum(from: LocalDate, to: LocalDate): DayTotal {
                val a = from.toEpochDay().toInt()
                val b = to.toEpochDay().toInt()
                val inRange = days.filter { it.day in a..b }
                return DayTotal(a, inRange.sumOf { it.ms }, inRange.sumOf { it.episodes })
            }
            val monday = today.with(DayOfWeek.MONDAY)
            val listened = days.filter { it.ms > 0 }.map { it.day }.toSet()

            var streakEnd = today.toEpochDay().toInt().let { if (it in listened) it else it - 1 }
            var daily = 0
            while (streakEnd in listened) { daily++; streakEnd-- }

            val weeks = days.filter { it.ms > 0 }.groupBy { LocalDate.ofEpochDay(it.day.toLong()).with(DayOfWeek.MONDAY) }
                .mapValues { (_, v) -> v.sumOf { it.ms } }
            var week = if (monday in weeks) monday else monday.minusWeeks(1)
            var weekly = 0
            while (week in weeks) { weekly++; week = week.minusWeeks(1) }

            val months = days.filter { it.ms > 0 }.groupBy { YearMonth.from(LocalDate.ofEpochDay(it.day.toLong())) }
                .map { (m, v) -> Triple(m, v.sumOf { it.ms }, v.sumOf { it.episodes }) }
                .sortedByDescending { it.first }
            val years = days.filter { it.ms > 0 }.groupBy { LocalDate.ofEpochDay(it.day.toLong()).year }
                .mapValues { (_, v) -> v.sumOf { it.ms } }

            return ListeningStats(
                today = on(today),
                yesterday = on(today.minusDays(1)),
                thisWeek = sum(monday, monday.plusDays(6)),
                lastWeek = sum(monday.minusWeeks(1), monday.minusDays(1)),
                weekDays = (0..6).map { (monday.plusDays(it.toLong()).toEpochDay().toInt()) in listened },
                dailyStreak = daily,
                weeklyStreak = weekly,
                longestDay = days.filter { it.ms > 0 }.maxByOrNull { it.ms }?.let { LocalDate.ofEpochDay(it.day.toLong()) to it.ms },
                biggestWeek = weeks.maxByOrNull { it.value }?.toPair(),
                busiestMonth = months.maxByOrNull { it.second }?.let { it.first to it.second },
                bestYear = years.maxByOrNull { it.value }?.toPair(),
                months = months,
                loggedMs = days.sumOf { it.ms },
            )
        }
    }
}
