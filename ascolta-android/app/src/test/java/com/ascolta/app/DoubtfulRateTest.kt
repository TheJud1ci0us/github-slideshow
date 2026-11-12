package com.ascolta.app

import com.ascolta.app.data.DoubtfulWords
import com.ascolta.app.data.WordLists
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * How many correct words would be underlined, on real transcripts of read speech (Google
 * FLEURS test sets, one per language). Runs only when FLEURS_DIR points at the .tsv files.
 */
class DoubtfulRateTest {
    private fun asset(name: String) = listOf(File("src/main/assets/$name"), File("app/src/main/assets/$name")).firstOrNull { it.exists() }?.inputStream()
    private val lists = WordLists({ asset(WordLists.assetName(it)) }, { asset(WordLists.moreName(it)) })

    @Test
    fun falseAlarmsOnRealTranscripts() {
        val dir = System.getenv("FLEURS_DIR")?.let(::File)
        assumeTrue("set FLEURS_DIR to run", dir?.isDirectory == true)
        val files = mapOf("sv" to "sv_se", "fr" to "fr_fr", "de" to "de_de", "es" to "es_419", "it" to "it_it", "nl" to "nl_nl", "pt" to "pt_br", "en" to "en_us")
        val word = Regex("""\p{L}+(?:['’\-]\p{L}+)*""")
        for ((lang, name) in files) {
            val file = File(dir, "$name.tsv").takeIf { it.exists() } ?: continue
            val texts = file.readLines().mapNotNull { it.split('\t').getOrNull(2) }.distinct()
            var words = 0; var flagged = 0; var sentences = 0
            val examples = mutableListOf<String>()
            for (t in texts) {
                words += word.findAll(t).count()
                val d = DoubtfulWords.find(t, lang, lists)
                if (d.isNotEmpty()) sentences++
                flagged += d.size
                d.forEach { if (examples.size < 25) examples += t.substring(it) }
            }
            println("RATE $lang: ${texts.size} sentences, $words words, $flagged flagged (${"%.2f".format(100.0 * flagged / words)}% of words, ${"%.1f".format(100.0 * sentences / texts.size)}% of sentences): $examples")
        }
    }
}
