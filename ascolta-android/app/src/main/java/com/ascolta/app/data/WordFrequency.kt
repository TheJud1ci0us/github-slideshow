package com.ascolta.app.data

/**
 * How common the words of a sentence are in their language (pure, unit-tested): band 0 for
 * the 1,000 most common words, 1 up to 5,000, 2 up to 15,000; rarer words and names get none.
 * The bands are coloured in the transcript, so learners see which words are worth learning first.
 */
object WordFrequency {
    val LIMITS = intArrayOf(1_000, 5_000, 15_000)

    fun band(rank: Int?): Int? = rank?.let { r -> LIMITS.indexOfFirst { r < it }.takeIf { it >= 0 } }

    /** The rank of [word] in [ranks] (0 = most common), or null. Elisions ("l'homme") count by their word. */
    fun rank(word: String, ranks: Map<String, Int>): Int? {
        val key = DoubtfulWords.key(word)
        return ranks[key] ?: key.substringAfterLast('\'').takeIf { it != key }?.let { ranks[it] }
    }

    /** The words of [text] that are in a band, with their band. */
    fun of(text: String, language: String?, ranks: Map<String, Int>): List<Pair<IntRange, Int>> =
        WordSplitter.ranges(text, language).mapNotNull { r ->
            val word = text.substring(r.first, r.last + 1)
            if (word.first().isUpperCase() && r.first > 0) return@mapNotNull null // a name
            band(rank(word, ranks))?.let { r to it }
        }
}
