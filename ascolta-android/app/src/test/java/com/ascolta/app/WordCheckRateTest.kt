package com.ascolta.app

import com.ascolta.app.data.WordCheck
import com.ascolta.app.data.WordLists
import com.ascolta.app.translate.MarianTranslator
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * How the "Wrong word?" check behaves with a real translator on real sentences (FLEURS):
 * a transcript with one word swapped for a close real word, where the learner suggests the
 * original (should be accepted), and a correct transcript where the learner suggests a close
 * but wrong word (should be refused). Runs only with OPUS_DIR and FLEURS_DIR set.
 */
class WordCheckRateTest {
    private fun asset(name: String) = listOf(File("src/main/assets/$name"), File("app/src/main/assets/$name")).firstOrNull { it.exists() }?.inputStream()
    private val lists = WordLists({ asset(WordLists.assetName(it)) }, { asset(WordLists.moreName(it)) })

    @Test
    fun rates() {
        val opus = System.getProperty("opus.dir")?.let(::File)
        val fleurs = System.getenv("FLEURS_DIR")?.let(::File)
        assumeTrue(opus?.isDirectory == true && fleurs?.isDirectory == true)
        val n = (System.getenv("WORDCHECK_N") ?: "40").toInt()
        for ((lang, file) in listOf("fr" to "fr_fr", "sv" to "sv_se")) {
            val there = MarianTranslator(File(opus, "$lang-en"), threads = 4)
            val back = MarianTranslator(File(opus, "en-$lang"), threads = 4)
            val sentences = File(fleurs, "$file.tsv").readLines().mapNotNull { it.split('\t').getOrNull(2) }.distinct().take(n)
            val words = lists.ranks(lang)!!.entries.sortedBy { it.value }.map { it.key }.take(25_000)
            val random = Random(5)
            var fixes = 0; var fixesOk = 0; var bad = 0; var badOk = 0
            val why = mutableMapOf<String, Int>(); val examples = mutableListOf<String>()
            val rows = StringBuilder()
            fun check(heard: String, suggested: String, oldSentence: String, range: IntRange): WordCheck.Verdict {
                val q = WordCheck.quick(heard, suggested, lang, lists)
                val newSentence = WordCheck.replace(oldSentence, range, suggested)
                val t = there.translate(newSentence)
                val b = back.translate(t)
                val bOld = back.translate(there.translate(oldSentence))
                val kept = WordCheck.meaning(suggested, newSentence, oldSentence, t, b, null, "en", lists)
                rows.append("ROW\t$lang\t$heard\t$suggested\t$q\t$kept\t${WordCheck.overlap(b, newSentence)}\t${WordCheck.overlap(bOld, oldSentence)}\t${com.ascolta.app.data.DoubtfulWords.distance(WordCheck.plain(heard), WordCheck.plain(suggested), 20)}\n")
                q?.let { return it }
                return WordCheck.meaning(suggested, newSentence, oldSentence, t, b, bOld, "en", lists) ?: WordCheck.Verdict.PLAUSIBLE
            }
            for (s in sentences) {
                val candidates = Regex("""\p{L}{4,}""").findAll(s).filter { it.value[0].isLowerCase() }.toList()
                if (candidates.isEmpty()) continue
                val m = candidates.random(random)
                val w = m.value
                val near = words.filter { it != w.lowercase() && it.length >= 3 && WordCheck.close(w, it) && kotlin.math.abs(it.length - w.length) <= 2 && it[0] == w[0] }
                if (near.isEmpty()) continue
                val wrong = near.take(200).random(random)
                // 1) The transcript has the wrong word; the learner suggests the right one.
                val transcript = s.replaceRange(m.range, wrong)
                val r1 = check(wrong, w, transcript, m.range.first until m.range.first + wrong.length)
                fixes++; if (r1 == WordCheck.Verdict.PLAUSIBLE) fixesOk++ else { why["fix:" + r1] = (why["fix:" + r1] ?: 0) + 1 }
                // 2) The transcript is right; the learner suggests a wrong word.
                val r2 = check(w, wrong, s, m.range)
                bad++; if (r2 == WordCheck.Verdict.PLAUSIBLE) { badOk++; if (examples.size < 8) examples += "$w→$wrong" } else { why["bad:" + r2] = (why["bad:" + r2] ?: 0) + 1 }
            }
            File(System.getProperty("java.io.tmpdir"), "wordcheck_$lang.tsv").writeText(rows.toString())
            println("WORDCHECK $lang: right word accepted ${fixesOk * 100 / maxOf(1, fixes)}% of $fixes; wrong word accepted ${badOk * 100 / maxOf(1, bad)}% of $bad; reasons $why; wrong ones let through: $examples")
            there.close(); back.close()
        }
    }
}
