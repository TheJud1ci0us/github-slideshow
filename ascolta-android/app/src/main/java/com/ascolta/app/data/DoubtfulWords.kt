package com.ascolta.app.data

/**
 * Spots words a speech model probably misheard: words that don't exist (pure, unit-tested).
 *
 * A word is doubtful when it has six letters or more, isn't among the language's 100,000
 * most common words, isn't such a word with a short ending, isn't a compound of them
 * ("klädstilar" = kläd + stilar, parts of four letters or more), isn't a name (capitalised,
 * or in the episode's names) and isn't common English. Measured on word-frequency data, this
 * wrongly doubts 0.1–0.8 % of correct words in the languages of [LANGUAGES] and catches about
 * 93 % of invented ones. German nouns are capitalised, so they are never doubted.
 */
object DoubtfulWords {
    /** Languages where the rule is reliable enough (others have more word forms than the lists hold). */
    val LANGUAGES = setOf("sv", "de", "fr", "es", "it", "nl", "pt", "en")
    private const val MIN_LETTERS = 6
    /** Shortest part of a compound: with three letters, "kläddsträger" passed as "klädd + sträger". */
    private const val MIN_PART = 4

    /** Letters that may join the parts of a compound. */
    private val LINKS = mapOf(
        "sv" to listOf("", "s"),
        "de" to listOf("", "s", "es", "n", "en", "e"),
        "nl" to listOf("", "s", "en"),
    )
    private val WORD = Regex("""\p{L}+(?:['’\-]\p{L}+)*""")

    /** How the word lists spell a word: lowercase, and "ss" for "ß" (the German list has no ß). */
    fun key(word: String): String = word.lowercase().replace('’', '\'').replace("ß", "ss")

    fun supports(language: String, lists: WordLists): Boolean = language in LANGUAGES && lists.wide(language) != null

    /** Whether [word] (lowercase) is a known word, one with a short ending, or a compound of known words. */
    fun explained(word: String, known: Map<String, Int>, language: String, parts: Int = 3): Boolean {
        if (word in known) return true
        for (cut in 1..3) if (word.length - cut >= 5 && word.dropLast(cut) in known) return true
        val links = LINKS[language] ?: return false
        if (parts <= 1) return false
        for (i in MIN_PART..word.length - MIN_PART) {
            if (word.substring(0, i) !in known) continue
            for (link in links) {
                if (!word.startsWith(link, i)) continue
                val rest = word.substring(i + link.length)
                if (rest.length >= MIN_PART && explained(rest, known, language, parts - 1)) return true
            }
        }
        return false
    }

    /** Two known words a compound is made of ("hundkläder" → hund, kläder), or null (to look them up in the dictionary). */
    fun parts(word: String, language: String, lists: WordLists): List<String>? {
        val known = lists.wide(language) ?: return null
        val links = LINKS[language] ?: return null
        val w = key(word)
        for (i in MIN_PART..w.length - MIN_PART) {
            val head = w.substring(0, i)
            if (head !in known) continue
            for (link in links) {
                if (!w.startsWith(link, i)) continue
                val rest = w.substring(i + link.length)
                if (rest.length >= MIN_PART && explained(rest, known, language, 1)) return listOf(head, rest)
            }
        }
        return null
    }

    private fun doubtful(token: String, known: Map<String, Int>, english: Map<String, Int>?, language: String, names: Set<String>): Boolean {
        if (token[0].isUpperCase()) return false // a name, or the start of a sentence
        val lower = key(token)
        if (lower in names || lower in known) return false
        // "l'élasticité", "arc-en-ciel": each piece on its own.
        return lower.split('\'', '-').any { piece ->
            piece.length >= MIN_LETTERS && piece !in names && !explained(piece, known, language) && (language == "en" || english?.containsKey(piece) != true)
        }
    }

    /** Where the doubtful words of [text] are. Empty for a language that isn't covered. */
    fun find(text: String, language: String, lists: WordLists, names: List<String> = emptyList()): List<IntRange> {
        if (language !in LANGUAGES) return emptyList()
        val known = lists.wide(language) ?: return emptyList()
        val english = lists.ranks("en")
        val skip = names.flatMap { key(it).split(' ', '-') }.toSet()
        return WORD.findAll(text).filter { doubtful(it.value, known, english, language, skip) }.map { it.range }.toList()
    }

    /** Edit distance (a swap of neighbours counts one), given up above [max]. */
    internal fun distance(a: String, b: String, max: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > max) return max + 1
        var before = IntArray(b.length + 1)
        var row = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val next = IntArray(b.length + 1)
            next[0] = i
            var best = next[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var d = minOf(row[j] + 1, next[j - 1] + 1, row[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d = minOf(d, before[j - 2] + 1)
                next[j] = d
                if (d < best) best = d
            }
            if (best > max) return max + 1
            before = row; row = next
        }
        return row[b.length]
    }

    /** The real words nearest to [word] (two letters away at most), the nearest and most common first. */
    fun suggestions(word: String, language: String, lists: WordLists, count: Int = 3): List<String> {
        val known = lists.wide(language) ?: return emptyList()
        val w = key(word)
        return known.entries.asSequence()
            .filter { kotlin.math.abs(it.key.length - w.length) <= 2 }
            .mapNotNull { e -> distance(w, e.key, 2).takeIf { it in 1..2 }?.let { Triple(it, e.value, e.key) } }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .take(count).map { it.third }.toList()
    }

    /**
     * Corrects the doubtful words of [text] that are one letter away from exactly one common
     * word (nearly always the word that was said). Returns the text and what was changed.
     */
    fun correct(text: String, language: String, lists: WordLists, names: List<String> = emptyList()): Pair<String, Map<String, String>> {
        val ranges = find(text, language, lists, names)
        if (ranges.isEmpty()) return text to emptyMap()
        val common = lists.ranks(language) ?: return text to emptyMap()
        val fixed = LinkedHashMap<String, String>()
        var out = text
        for (range in ranges.asReversed()) {
            val word = text.substring(range)
            if ('\'' in word || '’' in word || '-' in word) continue
            val near = common.keys.filter { distance(key(word), it, 1) == 1 }
            if (near.size != 1 || (language == "de" && "ss" in near[0])) continue // ß or ss: the list can't tell
            fixed[word] = near[0]
            out = out.replaceRange(range, near[0])
        }
        return out to fixed
    }

    /**
     * Why a second model's version [second] of a sentence should NOT replace [original], or
     * null when it should: it must have fewer doubtful words and be about as long.
     */
    fun rejection(original: String, second: String, language: String, lists: WordLists, names: List<String> = emptyList()): String? {
        if (second.isBlank()) return "nothing heard"
        val before = find(original, language, lists, names).size
        val after = find(second, language, lists, names).size
        val ratio = second.length.toDouble() / original.length.coerceAtLeast(1)
        return when {
            ratio < 0.6 || ratio > 1.6 -> "a different length (${second.length} letters for ${original.length})"
            after >= before -> "no fewer doubtful words ($after for $before)"
            else -> null
        }
    }
}
