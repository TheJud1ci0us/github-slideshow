package com.ascolta.app.data

/**
 * Is a podcast really in the language being learned? Stores of small languages are full of
 * shows in a neighbouring big one (Georgia's store is mostly Russian), so suggestions are
 * checked against the language the feed declares and, failing that, the alphabet of its
 * title and description.
 */
object LanguageCheck {
    /** Two-letter code from a feed's <language> (e.g. "ka-GE" → "ka"); "ge" is a common mistake for Georgian. */
    fun normalize(declared: String?): String? {
        val code = declared?.trim()?.lowercase()?.takeWhile { it.isLetter() }?.take(3)?.takeIf { it.length >= 2 } ?: return null
        return when (code) {
            "ge", "geo", "kat" -> "ka"
            "iw" -> "he"
            "in" -> "id"
            "nb", "nn", "nob" -> "no"
            "jp" -> "ja"
            "kr" -> "ko"
            "cn" -> "zh"
            "ua" -> "uk"
            else -> code.take(2)
        }
    }

    /**
     * The word "podcast" in languages with their own alphabet: searching it in the country's
     * store finds local podcasts where the charts are dominated by a bigger neighbour (Apple's
     * charts in Georgia hold no Georgian podcast; a search for პოდკასტი finds many).
     */
    val PODCAST_WORD = mapOf(
        "ka" to "პოდკასტი", "ru" to "подкаст", "uk" to "подкаст", "be" to "падкаст", "bg" to "подкаст", "mk" to "подкаст",
        "el" to "podcast", "he" to "פודקאסט", "ar" to "بودكاست", "fa" to "پادکست", "ur" to "پوڈکاسٹ",
        "hi" to "पॉडकास्ट", "mr" to "पॉडकास्ट", "bn" to "পডকাস্ট", "gu" to "પોડકાસ્ટ", "kn" to "ಪಾಡ್‌ಕಾಸ್ಟ್",
        "ta" to "பாட்காஸ்ட்", "te" to "పాడ్‌కాస్ట్", "th" to "พอดแคสต์", "ja" to "ポッドキャスト", "ko" to "팟캐스트", "zh" to "播客",
    )

    /** Unicode scripts that identify a language (or a small group) by its alphabet. */
    private val scripts: Map<String, Set<Character.UnicodeScript>> = buildMap {
        put("ka", setOf(Character.UnicodeScript.GEORGIAN))
        put("el", setOf(Character.UnicodeScript.GREEK))
        put("he", setOf(Character.UnicodeScript.HEBREW))
        for (l in listOf("ar", "fa", "ur")) put(l, setOf(Character.UnicodeScript.ARABIC))
        for (l in listOf("hi", "mr")) put(l, setOf(Character.UnicodeScript.DEVANAGARI))
        put("bn", setOf(Character.UnicodeScript.BENGALI))
        put("gu", setOf(Character.UnicodeScript.GUJARATI))
        put("kn", setOf(Character.UnicodeScript.KANNADA))
        put("ta", setOf(Character.UnicodeScript.TAMIL))
        put("te", setOf(Character.UnicodeScript.TELUGU))
        put("th", setOf(Character.UnicodeScript.THAI))
        put("ja", setOf(Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HAN))
        put("ko", setOf(Character.UnicodeScript.HANGUL))
        put("zh", setOf(Character.UnicodeScript.HAN))
        for (l in listOf("ru", "uk", "be", "bg", "mk")) put(l, setOf(Character.UnicodeScript.CYRILLIC))
    }

    /**
     * True if [text] is mostly written in [language]'s alphabet (at least half of its letters).
     * Languages written in Latin letters need mostly Latin letters.
     */
    fun scriptMatches(text: String, language: String): Boolean {
        val letters = text.filter { it.isLetter() }
        if (letters.length < 3) return true // nothing to judge
        val wanted = scripts[language] ?: setOf(Character.UnicodeScript.LATIN)
        val hits = letters.count { Character.UnicodeScript.of(it.code) in wanted }
        return hits * 2 >= letters.length
    }

    /**
     * Keep a suggestion? [declared] is the feed's own language (null if unknown). Podcasts must
     * be in [target]; courses ([isCourse]) may also be in the learner's own language [native].
     */
    fun keep(title: String, summary: String?, declared: String?, target: String, native: String, isCourse: Boolean): Boolean {
        val text = "$title ${summary.orEmpty()}"
        // A course in the learner's own language must really be a course (not "The Georgian
        // Impact Podcast | AI"); one in the target language is fine either way.
        val allowed = if (isCourse && LevelGuess.looksLikeCourse(text)) setOf(target, native) else setOf(target)
        val lang = normalize(declared)
        if (lang != null) return lang in allowed
        // No declared language: judge by the alphabet (and drop English shows for other languages).
        return allowed.any { l -> scriptMatches(text, l) && (l == "en" || !LevelGuess.looksEnglish(text)) }
    }
}
