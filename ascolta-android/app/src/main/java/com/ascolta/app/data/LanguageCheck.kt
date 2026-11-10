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
            "arm", "hye" -> "hy"
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
        "ka" to "პოდკასტი", "hy" to "փոդքասթ", "ru" to "подкаст", "uk" to "подкаст", "be" to "падкаст", "bg" to "подкаст", "mk" to "подкаст",
        "el" to "podcast", "he" to "פודקאסט", "ar" to "بودكاست", "fa" to "پادکست", "ur" to "پوڈکاسٹ",
        "hi" to "पॉडकास्ट", "mr" to "पॉडकास्ट", "bn" to "পডকাস্ট", "gu" to "પોડકાસ્ટ", "kn" to "ಪಾಡ್‌ಕಾಸ್ಟ್",
        "ta" to "பாட்காஸ்ட்", "te" to "పాడ్‌కాస్ట్", "th" to "พอดแคสต์", "ja" to "ポッドキャスト", "ko" to "팟캐스트", "zh" to "播客",
    )

    /** The language's own name(s), e.g. "हिंदी", "فارسی", "español". */
    fun ownNames(language: String): List<String> {
        val locale = java.util.Locale.forLanguageTag(language)
        return (listOf(locale.getDisplayLanguage(locale)) + OWN_NAMES[language].orEmpty()).filter { it.length >= 2 }.distinct()
    }

    /** Common spellings of a language's own name that Java's list doesn't give (it has हिन्दी). */
    private val OWN_NAMES = mapOf("hi" to listOf("हिंदी", "हिन्दी"), "fa" to listOf("فارسی"), "hy" to listOf("հայերեն"))

    /** Unicode scripts that identify a language (or a small group) by its alphabet. */
    private val scripts: Map<String, Set<Character.UnicodeScript>> = buildMap {
        put("ka", setOf(Character.UnicodeScript.GEORGIAN))
        put("hy", setOf(Character.UnicodeScript.ARMENIAN))
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
     * The feed's declared language, unless it is evidently wrong. Many hosting tools fill in
     * "en" by default, so Farsi or Hindi shows often claim to be English: an "en" whose
     * title and description are written in [target]'s own alphabet (or, for a Latin-alphabet
     * target, don't look English at all) isn't believed. Returns null when not believed.
     */
    fun trusted(declared: String?, text: String, target: String): String? {
        val lang = normalize(declared) ?: return null
        if (lang != "en" || target == "en") return lang
        val ownAlphabet = target in scripts && scriptMatches(text, target)
        // "… in Hindi", "podcast en español": named, and (for Latin-alphabet languages) not English-looking.
        val named = mentionsLanguage(text, target) && (target in scripts || !LevelGuess.looksEnglish(text))
        return if (ownAlphabet || named) null else lang
    }

    /**
     * True if [text] says it is in [language]: its own name anywhere ("हिंदी", "español"), or
     * its English name in a phrase like "in Hindi", "Hindi podcast", "(Hindi)" or "| Hindi"
     * (not just any mention: "The Georgian Impact Podcast" is in English).
     */
    fun mentionsLanguage(text: String, language: String): Boolean {
        val locale = java.util.Locale.forLanguageTag(language)
        val own = (listOf(locale.getDisplayLanguage(locale)) + OWN_NAMES[language].orEmpty())
            .filter { it.length >= 3 && !it.equals(locale.getDisplayLanguage(java.util.Locale.ENGLISH), ignoreCase = true) }
        if (own.any { text.contains(it, ignoreCase = true) }) return true
        val english = Regex.escape(locale.getDisplayLanguage(java.util.Locale.ENGLISH).takeIf { it.length >= 3 } ?: return false)
        return Regex("""(?i)(\bin\s+$english\b|\b$english\s+(podcast|stories|news|kahani|samachar)|\(\s*$english\s*\)|[|–-]\s*$english\b)""")
            .containsMatchIn(text)
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
        val lang = trusted(declared, text, target)
        if (lang != null) return lang in allowed
        // No declared language: judge by the alphabet (and drop English shows for other languages).
        return allowed.any { l ->
            // Shows in Hindi, Farsi… often have Latin-letter titles that name the language
            // ("Horror Stories in Hindi"): those count as well as titles in the language's alphabet.
            (scriptMatches(text, l) && (l == "en" || !LevelGuess.looksEnglish(text))) ||
                (l in scripts && mentionsLanguage(text, l))
        }
    }
}
