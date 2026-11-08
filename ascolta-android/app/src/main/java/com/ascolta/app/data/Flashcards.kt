package com.ascolta.app.data

import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Built-in flashcards with spaced repetition (a simplified SM-2, as in Anki): each answer
 * sets when the card comes back. Cards answered well come back after longer and longer
 * intervals; forgotten cards come back within the same session.
 */
object Flashcards {
    enum class Grade { AGAIN, HARD, GOOD, EASY }

    private const val DAY_MS = 24L * 3600 * 1000
    private const val MIN_EASE = 1.3f

    fun schedule(card: VocabEntity, grade: Grade, now: Long): VocabEntity {
        val interval = card.intervalDays
        return when (grade) {
            Grade.AGAIN -> card.copy(
                reps = 0,
                intervalDays = 0f,
                ease = max(MIN_EASE, card.ease - 0.2f),
                dueAt = now + 60_000, // again in a minute
            )
            Grade.HARD -> {
                val days = max(1f, interval * 1.2f)
                card.copy(reps = card.reps + 1, intervalDays = days, ease = max(MIN_EASE, card.ease - 0.15f), dueAt = now + (days * DAY_MS).roundToLong())
            }
            Grade.GOOD -> {
                val days = goodInterval(card)
                card.copy(reps = card.reps + 1, intervalDays = days, dueAt = now + (days * DAY_MS).roundToLong())
            }
            Grade.EASY -> {
                val days = goodInterval(card) * 1.3f
                card.copy(reps = card.reps + 1, intervalDays = days, ease = card.ease + 0.15f, dueAt = now + (days * DAY_MS).roundToLong())
            }
        }
    }

    private fun goodInterval(card: VocabEntity): Float = when (card.reps) {
        0 -> 1f
        1 -> 3f
        else -> max(card.intervalDays * card.ease, card.intervalDays + 1f)
    }
}
