package com.ascolta.app.translate

import org.json.JSONObject
import java.io.File
import java.text.Normalizer

/**
 * The word-piece splitter of the Helsinki (Marian) translation models: SentencePiece
 * "unigram" pieces read from the model's tokenizer.json. A word is split into the pieces
 * whose scores add up best (Viterbi); a "▁" marks the start of a word. Pure Kotlin, so it
 * can be tested on the computer.
 */
class MarianTokenizer(
    private val pieces: List<String>,
    private val scores: FloatArray,
    val eos: Int,
    val pad: Int,
) {
    private val ids: Map<String, Int> = pieces.withIndex().associate { (i, p) -> p to i }
    private val unk: Int = ids["<unk>"] ?: 1 // (the file's own unk_id field is wrong in some conversions)
    private val longest = pieces.maxOf { it.length }

    /** Text → piece ids, ending with the end-of-sentence id. */
    fun encode(text: String): IntArray {
        // NFKC as SentencePiece does: e.g. the Armenian ligature "և" becomes "եւ".
        val normal = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val out = ArrayList<Int>()
        for (word in normal.split(WHITESPACE)) if (word.isNotEmpty()) out += split("▁$word")
        out += eos
        return out.toIntArray()
    }

    /** Piece ids → text (end and padding ids skipped). */
    fun decode(tokens: IntArray): String =
        tokens.filter { it != eos && it != pad && it in pieces.indices }
            .joinToString("") { pieces[it] }
            .replace('▁', ' ')
            .trim()

    private fun split(word: String): List<Int> {
        val n = word.length
        val best = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        val from = IntArray(n + 1) { -1 }
        val piece = IntArray(n + 1) { -1 }
        best[0] = 0.0
        for (i in 0 until n) {
            if (best[i] == Double.NEGATIVE_INFINITY) continue
            var found = false
            for (j in i + 1..minOf(n, i + longest)) {
                val id = ids[word.substring(i, j)] ?: continue
                if (id == eos || id == pad) continue
                found = true
                val score = best[i] + scores[id]
                if (score > best[j]) { best[j] = score; from[j] = i; piece[j] = id }
            }
            // A character no piece covers: an "unknown" piece, with a penalty.
            if (!found && best[i] - 10 > best[i + 1]) { best[i + 1] = best[i] - 10; from[i + 1] = i; piece[i + 1] = unk }
        }
        val out = ArrayList<Int>()
        var j = n
        while (j > 0) { out += piece[j]; j = from[j] }
        return out.asReversed()
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")

        /** Reads tokenizer.json (Unigram model) and the ids of config.json. */
        fun load(tokenizerJson: File, eos: Int, pad: Int): MarianTokenizer {
            val vocab = JSONObject(tokenizerJson.readText()).getJSONObject("model").getJSONArray("vocab")
            val pieces = ArrayList<String>(vocab.length())
            val scores = FloatArray(vocab.length())
            for (i in 0 until vocab.length()) {
                val entry = vocab.getJSONArray(i)
                pieces += entry.getString(0)
                scores[i] = entry.getDouble(1).toFloat()
            }
            return MarianTokenizer(pieces, scores, eos, pad)
        }
    }
}
