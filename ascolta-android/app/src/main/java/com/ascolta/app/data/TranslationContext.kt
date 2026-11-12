package com.ascolta.app.data

/**
 * Short fragments ("Nej.", "Svaret.") translated with the sentence before them (pure,
 * unit-tested): alone, a translator has no context for them. The pair is translated, and the
 * fragment's part is what follows the earlier sentence's own translation.
 */
object TranslationContext {
    private val WORD = Regex("""[\p{L}\p{N}]+""")

    /** A sentence of one or two words. */
    fun isFragment(text: String): Boolean = WORD.findAll(text).count() in 1..2

    /** What the pair is translated as: the earlier sentence, then the fragment. */
    fun pair(previous: String, fragment: String): String = previous.trim() + " " + fragment.trim()

    /**
     * The fragment's part of [combined] (the translation of [pair]), or null when it doesn't
     * start with [previousTranslation] (letters and digits compared, case aside): the
     * translator changed the earlier sentence, so the fragment is translated alone instead.
     */
    fun ownPart(previousTranslation: String, combined: String): String? {
        val want = previousTranslation.filter { it.isLetterOrDigit() }.lowercase()
        if (want.isEmpty()) return null
        var matched = 0
        var i = 0
        while (i < combined.length && matched < want.length) {
            val c = combined[i]
            if (c.isLetterOrDigit()) {
                if (c.lowercaseChar() != want[matched]) return null
                matched++
            }
            i++
        }
        if (matched < want.length) return null
        val rest = combined.substring(i).trimStart { !it.isLetterOrDigit() && it != '¿' && it != '¡' }.trim()
        if (rest.none { it.isLetterOrDigit() }) return null
        return rest.replaceFirstChar { it.uppercaseChar() }
    }
}
