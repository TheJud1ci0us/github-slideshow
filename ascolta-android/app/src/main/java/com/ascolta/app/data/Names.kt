package com.ascolta.app.data

import java.text.Normalizer

/**
 * Names a speech model can't know (guests, places, brands), taken from the episode's own
 * texts, and given to the model before it listens (pure, unit-tested):
 * - [extract]: capitalised words of the podcast and episode titles and the show notes;
 * - [prompt]: the hint for Whisper (names, then the previous sentences);
 * - [correct]: after recognition, a word spelled almost like one of the names becomes the name.
 */
object Names {
    /** At most this many names per episode (Whisper's hint is short; Parakeet slows with many). */
    const val MAX = 40
    /** Whisper reads at most ~224 tokens of hint; this many characters stays well inside. */
    private const val PROMPT_CHARS = 420

    private val TAGS = Regex("<[^>]*>")
    private val URLS = Regex("""(https?://|www\.)\S+|\S+@\S+\.\S+""")
    private val ENTITIES = mapOf("&amp;" to "&", "&nbsp;" to " ", "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'", "&lt;" to "<", "&gt;" to ">")
    private val WORD = Regex("""[\p{L}][\p{L}\p{M}'’\-]*""")
    // Not ":" — "Music: Bensound", "Guest: Anna Rossi" introduce names.
    private val SENTENCE_END = Regex("""[.!?…»"“”()\[\]|•·–—\n\r]""")

    /**
     * Words that are capitalised in titles and show notes without being names (months, days,
     * "Episode", the start of a sentence…), in the app's languages.
     */
    private val COMMON = setOf(
        // English
        "the", "a", "an", "and", "or", "but", "in", "on", "at", "of", "for", "with", "this", "that", "these", "what", "how", "why",
        "who", "when", "where", "our", "your", "my", "his", "her", "their", "we", "you", "they", "he", "she", "it", "is", "are",
        "episode", "season", "part", "podcast", "show", "today", "new", "listen", "follow", "subscribe", "support", "join", "music",
        "january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december",
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "i",
        // French
        "le", "la", "les", "un", "une", "des", "du", "de", "et", "ou", "mais", "dans", "sur", "pour", "avec", "ce", "cette", "ces",
        "il", "elle", "ils", "elles", "nous", "vous", "on", "je", "qui", "que", "quoi", "comment", "pourquoi", "quand", "où",
        "épisode", "saison", "partie", "émission", "aujourd'hui", "retrouvez", "écoutez", "suivez", "abonnez", "musique", "cela",
        "chaque", "dans", "après", "avant", "depuis", "entre", "comme", "selon", "leur", "leurs", "notre", "votre",
        "janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre",
        "lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche",
        // Italian
        "il", "lo", "gli", "le", "uno", "una", "di", "da", "e", "o", "ma", "in", "su", "per", "con", "questo", "questa", "come",
        "perché", "quando", "dove", "chi", "che", "puntata", "stagione", "ascolta", "segui",
        "gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno", "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre",
        "lunedì", "martedì", "mercoledì", "giovedì", "venerdì", "sabato", "domenica",
        // Spanish
        "el", "los", "las", "y", "pero", "en", "por", "para", "con", "este", "esta", "cómo", "qué", "quién", "cuándo", "dónde",
        "episodio", "temporada", "escucha", "sigue", "música", "hoy",
        "enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre",
        "lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo",
        // Portuguese
        "o", "os", "as", "um", "uma", "do", "da", "dos", "das", "em", "no", "na", "com", "para", "este", "esta", "como",
        "porque", "quando", "onde", "quem", "ouça", "siga", "hoje",
        "janeiro", "fevereiro", "março", "abril", "maio", "junho", "julho", "agosto", "setembro", "outubro", "novembro", "dezembro",
        "segunda", "terça", "quarta", "quinta", "sexta", "sábado", "domingo",
        // German (nouns are capitalised: only words that also appear lowercase are dropped,
        // plus the usual title words)
        "der", "die", "das", "den", "dem", "des", "ein", "eine", "einer", "und", "oder", "aber", "mit", "für", "von", "zu", "im",
        "am", "ist", "wir", "ihr", "sie", "er", "es", "wie", "was", "warum", "wann", "wo", "wer", "folge", "staffel", "heute",
        "januar", "februar", "märz", "april", "mai", "juni", "juli", "august", "september", "oktober", "november", "dezember",
        "montag", "dienstag", "mittwoch", "donnerstag", "freitag", "samstag", "sonntag",
    )

    /**
     * Likely names in [texts] (podcast title, episode title, show notes), most frequent first.
     * A word counts when it is capitalised somewhere other than the start of a sentence and
     * never appears in lowercase in the same texts; [extra] (the learner's own list for this
     * podcast) always comes first. Languages without capital letters give only [extra].
     */
    fun extract(texts: List<String?>, extra: List<String> = emptyList()): List<String> {
        val clean = texts.filterNotNull().map(::plain)
        val lowercase = HashSet<String>()
        val counts = LinkedHashMap<String, Int>()
        for (text in clean) {
            for (chunk in text.split(SENTENCE_END)) {
                WORD.findAll(chunk).forEachIndexed { i, m ->
                    val w = m.value.trim('-', '\'', '’')
                    if (w.isEmpty()) return@forEachIndexed
                    if (!w[0].isUpperCase()) { lowercase += w.lowercase(); return@forEachIndexed }
                    if (i == 0) return@forEachIndexed // the first word of a sentence or a title
                    counts[w] = (counts[w] ?: 0) + 1
                }
            }
        }
        // A word only capitalised at the start of sentences, seen capitalised mid-sentence elsewhere, still counts.
        val found = counts.keys.filter { w ->
            val lw = w.lowercase()
            w.length >= 3 && lw !in COMMON && lw !in lowercase && w.any { it.isLowerCase() || it.isDigit() } || isAcronym(w, lowercase)
        }.sortedByDescending { counts[it] ?: 0 }
        return (extra.map { it.trim() }.filter { it.isNotEmpty() } + found).distinctBy { it.lowercase() }.take(MAX)
    }

    private fun isAcronym(w: String, lowercase: Set<String>) =
        w.length in 2..6 && w.all { it.isUpperCase() || it.isDigit() } && w.lowercase() !in lowercase && w.lowercase() !in COMMON

    /** Show notes as plain text: no HTML, links or e-mail addresses. */
    fun plain(text: String): String {
        var s = TAGS.replace(text, " ")
        ENTITIES.forEach { (k, v) -> s = s.replace(k, v) }
        return URLS.replace(s, " ")
    }

    /**
     * Whisper's hint: the names (so it spells them as written), then the end of the previous
     * sentences (so it continues in the same style), within Whisper's limit.
     */
    fun prompt(names: List<String>, previous: String?): String? {
        val tail = previous?.trim().orEmpty().takeLast(200)
        val room = PROMPT_CHARS - tail.length - 2
        val list = StringBuilder()
        for (n in names) {
            if (list.length + n.length + 2 > room) break
            if (list.isNotEmpty()) list.append(", ")
            list.append(n)
        }
        val head = if (list.isEmpty()) "" else "$list."
        return listOf(head, tail).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { null }
    }

    /**
     * Whisper sometimes repeats its hint over music or silence: a "sentence" that is only a
     * list of the names (2+ words, nearly all of them names) is dropped.
     */
    fun echoesPrompt(text: String, names: List<String>): Boolean {
        if (names.isEmpty()) return false
        val known = names.flatMap { it.split(' ') }.map { it.lowercase() }.toSet()
        val words = WORD.findAll(text).map { it.value.lowercase() }.toList()
        if (words.size < 2) return false
        return words.count { it in known } >= words.size * 0.8
    }

    /**
     * Parakeet's hint words: each name, and each part of a multi-word name, once.
     * (sherpa-onnx separates them with "/").
     */
    fun hotwords(names: List<String>): String =
        names.flatMap { n -> listOf(n) + n.split(' ').filter { it.length >= 4 } }
            .map { it.replace('/', ' ').trim() }.filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }.joinToString("/")

    /**
     * Replaces words spelled almost like one of [names] by the name: "Selenski" → "Zelensky".
     * Strict, so ordinary words are left alone: the word must be capitalised (the model took
     * it for a name), at least 4 letters, not a common word, not just a longer or shorter form
     * of the name, and within 1 letter (2 for names of 8+ letters, accents counting as equal)
     * of a single-word name or a part of a longer one.
     * Returns the corrected text and the replaced words (old → new).
     */
    fun correct(text: String, names: List<String>): Pair<String, Map<String, String>> {
        val parts = names.flatMap { it.split(' ') }.map { it.trim() }
            .filter { it.length >= 4 && it[0].isUpperCase() }.distinct()
        if (parts.isEmpty()) return text to emptyMap()
        val keys = parts.map { fold(it) }
        val replaced = LinkedHashMap<String, String>()
        val out = WORD.replace(text) { m ->
            val w = m.value
            val match = bestMatch(w, parts, keys)
            if (match != null && match != w) { replaced[w] = match; match } else w
        }
        return out to replaced
    }

    private fun bestMatch(word: String, parts: List<String>, keys: List<String>): String? {
        if (word.length < 4 || !word[0].isUpperCase() || word.lowercase() in COMMON) return null
        val k = fold(word)
        var best: String? = null
        var bestD = Int.MAX_VALUE
        for (i in parts.indices) {
            val target = keys[i]
            if (target == k) return if (parts[i] == word) null else parts[i] // same letters, other accents
            val allowed = if (target.length >= 8) 2 else if (target.length >= 5) 1 else 0
            if (allowed == 0 || kotlin.math.abs(target.length - k.length) > allowed) continue
            // Another form of the same word ("Kanzlers" for "Kanzler", "Macrons"): not a misspelling.
            if (k.startsWith(target) || target.startsWith(k)) continue
            val d = distance(k, target, allowed)
            if (d <= allowed && d < bestD) { bestD = d; best = parts[i] }
        }
        return best
    }

    /** Lowercase without accents. */
    private fun fold(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")

    /** Edit distance, stopping early above [limit]. */
    private fun distance(a: String, b: String, limit: Int): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                rowMin = minOf(rowMin, cur[j])
            }
            if (rowMin > limit) return limit + 1
            prev = cur
        }
        return prev[b.length]
    }
}
