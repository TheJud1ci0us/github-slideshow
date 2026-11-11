package com.ascolta.app.data

import android.content.Context
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * What the free version allows (LingoPod Pro has no limits):
 *  - 30 minutes of transcription a week, all languages together (resets on Monday)
 *  - 50 saved words and sentences
 *  - 5 saved selections or shadowing sessions a day
 *  - the Light and Balanced speech models (Best is Pro)
 * Counters are stored with a checksum, so editing the settings file doesn't reset them.
 */
class FreeLimits(context: Context, private val salt: String) {
    companion object {
        const val WEEKLY_TRANSCRIPTION_MS = 30 * 60_000L
        const val MAX_CARDS = 50
        const val DAILY_PRACTICE = 5

        /** The week a day belongs to: its Monday (pure). */
        fun weekOf(day: LocalDate): Long = day.with(DayOfWeek.MONDAY).toEpochDay()

        /** The model allowed: the chosen one, or the Balanced one when the chosen is a Pro-only model (pure). */
        fun allowedModel(chosen: com.ascolta.app.whisper.SpeechModel, language: String, pro: Boolean): com.ascolta.app.whisper.SpeechModel {
            if (pro) return chosen
            val tiers = com.ascolta.app.whisper.SpeechModel.tiers(language)
            return if (chosen == tiers.last() || chosen == com.ascolta.app.whisper.SpeechModel.MOST_ACCURATE) tiers[1] else chosen
        }
    }

    private val prefs = context.getSharedPreferences("limits", Context.MODE_PRIVATE)

    private fun sum(key: String, period: Long, value: Long): String =
        MessageDigest.getInstance("SHA-256").digest("$salt|$key|$period|$value".toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    /** A counter for the current period; a tampered or older value counts as used up / reset accordingly. */
    private fun read(key: String, period: Long): Long {
        if (prefs.getLong("$key.period", -1) != period) return 0
        val v = prefs.getLong("$key.value", 0)
        return if (prefs.getString("$key.sum", null) == sum(key, period, v)) v else Long.MAX_VALUE / 4
    }

    private fun write(key: String, period: Long, value: Long) {
        prefs.edit().putLong("$key.period", period).putLong("$key.value", value).putString("$key.sum", sum(key, period, value)).apply()
    }

    private fun week() = weekOf(LocalDate.now())
    private fun today() = LocalDate.now().toEpochDay()

    fun transcriptionLeftMs(): Long = (WEEKLY_TRANSCRIPTION_MS - read("tx", week())).coerceAtLeast(0)
    fun addTranscribed(ms: Long) { val w = week(); write("tx", w, read("tx", w) + ms) }

    fun practiceLeft(): Int = (DAILY_PRACTICE - read("practice", today())).coerceAtLeast(0).toInt()
    /** Uses one of today's free selections / shadowing sessions; false when none is left. */
    fun usePractice(): Boolean {
        val d = today()
        val used = read("practice", d)
        if (used >= DAILY_PRACTICE) return false
        write("practice", d, used + 1)
        return true
    }
}
