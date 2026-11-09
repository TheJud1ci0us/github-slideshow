package com.ascolta.app.data

import com.ascolta.app.R

/** CEFR levels (Common European Framework of Reference for languages). */
val CEFR_LEVELS = listOf("A1", "A2", "B1", "B2", "C1", "C2")

/** Broad level groups used to filter podcasts. */
enum class LevelBand(val label: String, val nameRes: Int) {
    BEGINNER("A1–A2", R.string.level_beginner),
    INTERMEDIATE("B1–B2", R.string.level_intermediate),
    ADVANCED("C1–C2", R.string.level_advanced);

    companion object {
        fun of(level: String?): LevelBand? = when (level?.firstOrNull()?.uppercaseChar()) {
            'A' -> BEGINNER
            'B' -> INTERMEDIATE
            'C' -> ADVANCED
            else -> null
        }
    }
}

/** Podcast themes, as Apple Podcasts genres. */
data class Genre(val id: Int, val nameRes: Int)

val GENRES = listOf(
    Genre(1498, R.string.genre_learning),
    Genre(1489, R.string.genre_news),
    Genre(1324, R.string.genre_culture),
    Genre(1487, R.string.genre_history),
    Genre(1303, R.string.genre_comedy),
    Genre(1533, R.string.genre_science),
    Genre(1318, R.string.genre_technology),
    Genre(1545, R.string.genre_sports),
    Genre(1483, R.string.genre_fiction),
    Genre(1321, R.string.genre_business),
    Genre(1512, R.string.genre_health),
    Genre(1305, R.string.genre_kids),
    Genre(1488, R.string.genre_true_crime),
    Genre(1301, R.string.genre_arts),
    Genre(1309, R.string.genre_film),
    Genre(1310, R.string.genre_music),
)
const val GENRE_LEARNING = 1498
const val GENRE_KIDS = 1305

/**
 * Estimated CEFR level of a podcast from its title, description and theme. Podcasts rarely
 * state a level, so this is a guess, always shown as "≈":
 *  - an explicit level ("A2", "B1-B2") is used as is;
 *  - words like beginner / facile / lento / débutant → A1–A2, intermediate → B1–B2,
 *    advanced → C1–C2;
 *  - other podcasts for learners (or in the Language Learning genre) → A2–B1;
 *  - children's podcasts → B1–B2;
 *  - everything else is made for native speakers → C1–C2.
 */
object LevelGuess {
    data class Estimate(val label: String, val band: LevelBand)

    private val explicit = Regex("""\b([ABC][12])\b""")
    private val beginner = listOf(
        "beginner", "beginners", "easy", "slow", "slowly", "simple", "basic", "absolute",
        "principiant", "facile", "lento", "lentamente", "semplice", "débutant", "lent", "simplifié",
        "principiante", "fácil", "despacio", "sencillo", "anfänger", "einfach", "langsam",
        "iniciante", "devagar", "básico", "begin",
    )
    private val intermediate = listOf("intermediate", "intermedio", "intermédiaire", "intermediário", "mittelstufe", "fortgeschrittene anfänger")
    private val advanced = listOf("advanced", "avanzato", "avancé", "avanzado", "avançado", "fortgeschritten", "native speaker")
    private val learner = listOf(
        "learn", "learning", "lesson", "learners", "students", "impara", "imparare", "per stranieri",
        "apprendre", "apprenants", "aprender", "aprende", "estudiantes", "lernen", "deutschkurs",
        "aprenda", "aprendizado", "course", "corso", "cours", "curso", "kurs", "vocabulary", "grammar",
        // Other languages' words for learning / lessons / course.
        "язык", "урок", "учим", "изуч", "для начинающих", "мова", "вивча", "nauka", "lekcj", "leren", "lessen",
        "öğren", "ders", "lära", "lektion", "oppia", "μάθ", "μάθημα", "ללמוד", "تعلم", "یادگیری", "सीख",
        "学ぶ", "レッスン", "講座", "学习", "课程", "배우", "강의", "เรียน",
    )

    fun estimate(title: String, summary: String?, genreId: Int? = null, genreName: String? = null): Estimate {
        val text = "$title ${summary.orEmpty()}"
        val levels = explicit.findAll(text).map { it.value }.distinct().sorted().toList()
        if (levels.isNotEmpty()) {
            val label = if (levels.size == 1) levels[0] else "${levels.first()}–${levels.last()}"
            return Estimate(label, LevelBand.of(levels.first())!!)
        }
        val lower = text.lowercase()
        fun has(words: List<String>) = words.any { lower.contains(it) }
        val forLearners = genreId == GENRE_LEARNING || genreName?.contains("Language", ignoreCase = true) == true || has(learner)
        return when {
            has(advanced) -> Estimate("C1–C2", LevelBand.ADVANCED)
            has(intermediate) -> Estimate("B1–B2", LevelBand.INTERMEDIATE)
            has(beginner) && forLearners || has(beginner) && lower.contains("news") -> Estimate("A1–A2", LevelBand.BEGINNER)
            forLearners -> Estimate("A2–B1", LevelBand.BEGINNER)
            genreId == GENRE_KIDS || genreName?.contains("Kids", ignoreCase = true) == true -> Estimate("B1–B2", LevelBand.INTERMEDIATE)
            else -> Estimate("C1–C2", LevelBand.ADVANCED)
        }
    }

    /** Looks like a course or lessons for learners (not just a show that mentions a language). */
    fun looksLikeCourse(text: String): Boolean {
        val lower = text.lowercase()
        return learner.any { lower.contains(it) } || explicit.containsMatchIn(text)
    }

    private val englishWords = setOf("the", "and", "of", "to", "you", "with", "your", "is", "for", "this", "that", "we", "our", "about", "every", "from")

    /** True if the text is clearly English (to drop English shows from another language's charts). */
    fun looksEnglish(text: String): Boolean {
        val words = text.lowercase().split(Regex("[^a-z']+")).filter { it.isNotBlank() }
        if (words.size < 4) return false
        val hits = words.count { it in englishWords }
        return hits >= 3 && hits.toFloat() / words.size >= 0.12f
    }
}
