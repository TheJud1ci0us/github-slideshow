package com.ascolta.app.data

/**
 * Parakeet can't be told the language: now and then it writes a French (Italian…) sentence in
 * English. This spots such sentences from their most common short words (pure, unit-tested).
 */
object SentenceLanguage {
    private val ENGLISH = setOf(
        "the", "is", "are", "and", "you", "that", "this", "what", "with", "have", "has", "it's", "i'm", "don't",
        "we're", "they", "was", "were", "of", "for", "be", "not", "but", "just", "like", "there", "about", "would",
        "will", "can't", "it", "my", "your", "he", "she", "our", "we", "i", "to", "so", "do", "if", "they're",
        "because", "really", "think", "know", "going", "very", "from", "at", "by", "all", "can", "how", "why",
    )

    private val MARKERS = mapOf(
        "fr" to setOf(
            "le", "la", "les", "des", "est", "et", "que", "qui", "un", "une", "pas", "je", "tu", "il", "elle", "nous",
            "vous", "ce", "c'est", "dans", "pour", "avec", "sur", "du", "au", "aux", "mais", "ou", "on", "en", "ne",
            "se", "sont", "j'ai", "ça", "de", "à", "y", "a", "l'", "d'", "qu'", "n'", "s'", "j'",
        ),
        "it" to setOf(
            "il", "lo", "la", "le", "gli", "è", "e", "che", "di", "un", "una", "non", "per", "con", "sono", "ma", "mi",
            "ci", "si", "questo", "anche", "come", "del", "della", "nel", "da", "a", "in", "ho", "l'",
        ),
        "es" to setOf(
            "el", "la", "los", "las", "es", "y", "que", "de", "un", "una", "no", "por", "con", "para", "pero", "se",
            "lo", "está", "muy", "como", "del", "en", "al", "yo", "mi", "a", "su", "me",
        ),
        "de" to setOf(
            "der", "die", "das", "und", "ist", "nicht", "ich", "du", "wir", "sie", "ein", "eine", "mit", "auf", "zu",
            "den", "dem", "es", "auch", "aber", "wie", "sich", "noch", "in", "von", "im", "mal", "ja",
        ),
        "pt" to setOf(
            "o", "a", "os", "as", "é", "e", "que", "de", "um", "uma", "não", "para", "com", "mas", "se", "do", "da",
            "no", "na", "eu", "você", "está", "isso", "muito", "em", "por",
        ),
    )

    private val WORD = Regex("""[\p{L}]+(?:['’][\p{L}]*)?""")

    /**
     * True when [text] is clearly English while the episode is in [language]: at least 4 words,
     * at least 2 English markers, and far more English markers than [language]'s own (so a
     * French sentence quoting "OK" or a song title is left alone).
     */
    fun isEnglishInstead(text: String, language: String): Boolean {
        val own = MARKERS[language] ?: return false
        val words = WORD.findAll(text.lowercase().replace('’', '\'')).map { it.value }.toList()
        if (words.size < 4) return false
        // French/Italian elisions: "l'homme" counts as "l'".
        fun ownWord(w: String) = w in own || (w.contains('\'') && w.substringBefore('\'') + "'" in own)
        val english = words.count { it in ENGLISH && it !in own }
        val native = words.count(::ownWord)
        return english >= 2 && english >= 2 * native + 1 && english * 4 >= words.size
    }
}
