package com.ascolta.app.data

import java.text.Normalizer

/**
 * Whether a word the learner heard instead of the transcribed one is a plausible correction
 * (pure, unit-tested): a real word, close to what was transcribed (what the model heard),
 * different from it, and making sense in the whole sentence, judged with the translator both
 * ways (the new sentence translated, then translated back).
 */
object WordCheck {
    enum class Verdict { PLAUSIBLE, EMPTY, SAME, NOT_A_WORD, TOO_DIFFERENT, NOT_UNDERSTOOD, DOESNT_FIT }

    private val WORD = Regex("""\p{L}+(?:['’\-]\p{L}+)*""")
    private val MARKS = Regex("""\p{Mn}+""")

    fun words(text: String): List<String> = WORD.findAll(text).map { it.value }.toList()

    /** Lowercase, without accents (é → e), ’ → ', ß → ss. */
    fun plain(s: String): String =
        MARKS.replace(Normalizer.normalize(s.lowercase().replace('’', '\'').replace("ß", "ss"), Normalizer.Form.NFD), "")

    /** [sentence] with the word at [range] replaced by [suggested]. */
    fun replace(sentence: String, range: IntRange, suggested: String): String =
        sentence.replaceRange(range, suggested.trim())

    /** Spelled close enough to have been misheard as each other (accents and case aside). */
    fun close(heard: String, suggested: String): Boolean {
        val a = plain(heard).filter { it.isLetter() }
        val b = plain(suggested).filter { it.isLetter() }
        if (a.isEmpty() || b.isEmpty()) return false
        if (minOf(a.length, b.length) >= 3 && a.take(3) == b.take(3)) return true
        val longest = maxOf(a.length, b.length)
        // Short words ("and" heard for "en") may differ by two letters.
        return DoubtfulWords.distance(a, b, longest) <= if (longest <= 4) 2 else maxOf(1, longest / 2)
    }

    /**
     * The checks that need no translation, or null when they pass. Without a word list for
     * [language], any word is accepted as real.
     */
    fun quick(heard: String, suggested: String, language: String, lists: WordLists, inDictionary: (String) -> Boolean = { false }): Verdict? {
        val given = words(suggested)
        if (given.isEmpty()) return Verdict.EMPTY
        if (given.joinToString(" ") { plain(it) } == words(heard).joinToString(" ") { plain(it) }) return Verdict.SAME
        val known = lists.wide(language) ?: lists.ranks(language)
        if (known != null) {
            for (w in given) {
                val key = DoubtfulWords.key(w)
                val real = key in known || inDictionary(w) || key.substringAfterLast('\'') in known ||
                    (language in DoubtfulWords.LANGUAGES && DoubtfulWords.explained(key, known, language))
                if (!real) return Verdict.NOT_A_WORD
            }
        }
        if (!close(heard, suggested)) return Verdict.TOO_DIFFERENT
        return null
    }

    private fun sameWord(a: String, b: String): Boolean =
        a == b || (minOf(a.length, b.length) >= 4 && (a.take(4) == b.take(4) || DoubtfulWords.distance(a, b, 2) <= 2))

    /** How much of [a]'s wording [b] keeps (0…1): shared words over all words. */
    fun overlap(a: String, b: String): Double {
        val x = words(a).map(::plain).toSet()
        val y = words(b).map(::plain).toSet()
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return (x intersect y).size.toDouble() / (x union y).size
    }

    /**
     * The whole-sentence check, or null when it passes.
     * - [there]: the new sentence translated into the learner's language [target]. The
     *   suggested word copied unchanged (and not a word of [target]) means the translator
     *   found no meaning for it there.
     * - [back]: [there] translated back. A sentence that makes sense comes back with the
     *   suggested word (or a form of it); otherwise the word doesn't fit the sentence.
     * - [backOld]: the old sentence's own round trip. The new one must hold together at least
     *   as well as the old one did.
     */
    fun meaning(
        suggested: String,
        newSentence: String,
        oldSentence: String,
        there: String,
        back: String?,
        backOld: String?,
        target: String,
        lists: WordLists,
    ): Verdict? {
        val given = words(suggested).filter { it.length >= 3 }
        val targetWords = lists.ranks(target)
        val translated = words(there).map(::plain).toSet()
        if (given.any { w -> plain(w).length >= 4 && plain(w) in translated && targetWords?.containsKey(DoubtfulWords.key(w)) != true }) {
            return Verdict.NOT_UNDERSTOOD
        }
        if (back == null) return null
        val main = given.maxByOrNull { it.length } ?: return null
        val returned = words(back).map(::plain)
        if (returned.none { sameWord(it, plain(main)) }) return Verdict.DOESNT_FIT
        if (backOld != null && overlap(back, newSentence) < overlap(backOld, oldSentence)) return Verdict.DOESNT_FIT
        return null
    }
}
