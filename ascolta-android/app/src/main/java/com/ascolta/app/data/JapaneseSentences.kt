package com.ascolta.app.data

import com.ascolta.app.whisper.WhisperSegment

/**
 * Sentences for speech models that give Japanese text without punctuation (ReazonSpeech).
 *
 * The text is analysed with the Japanese dictionary (Kuromoji) and split where a spoken
 * sentence ends: a sentence-final form (です, ます, た, だ, ない… or a particle such as ね, よ,
 * か) followed by a short pause. Speech that runs on without ending (clauses chained with
 * けど, から, て…) is cut after about [MAX_CHARS] characters or [MAX_MS] at the best point:
 * after a clause connector, otherwise at the longest pause. 。 or ？ is added at sentence ends
 * and 、 at clause cuts, which also helps the translation.
 */
object JapaneseSentences {
    const val MAX_CHARS = 40
    const val MAX_MS = 9_000L
    private const val MIN_CHARS = 6
    /** A character lasts about this long, so a pause is the gap between two starts minus it. */
    private const val CHAR_MS = 120L

    /** A sentence: characters [start, end) of the text, and the punctuation to add. */
    data class Cut(val start: Int, val end: Int, val punctuation: String)

    /**
     * Sentences of [tokens] (text pieces) with start times [timesMs] (one per token, in the
     * audio), from a piece of speech ending at [endMs]. Each sentence's words are its
     * characters with their times (grouped into real words afterwards).
     */
    fun build(tokens: List<String>, timesMs: List<Long>, endMs: Long): List<WhisperSegment> {
        if (tokens.isEmpty()) return emptyList()
        // Time of every character (a token's characters share its time, spread a little).
        val text = StringBuilder()
        val charTimes = ArrayList<Long>()
        tokens.forEachIndexed { i, tok ->
            val next = timesMs.getOrNull(i + 1) ?: endMs
            tok.forEachIndexed { j, c ->
                text.append(c)
                charTimes += timesMs[i] + (next - timesMs[i]) * j / tok.length.coerceAtLeast(1)
            }
        }
        val s = text.toString()
        val cuts = split(s, charTimes, endMs) ?: listOf(Cut(0, s.length, ""))
        return cuts.mapIndexedNotNull { k, cut ->
            val body = s.substring(cut.start, cut.end).trim()
            if (body.isEmpty()) return@mapIndexedNotNull null
            val start = charTimes[cut.start]
            val end = cuts.getOrNull(k + 1)?.let { charTimes[it.start] } ?: endMs
            val words = (cut.start until cut.end).filter { !s[it].isWhitespace() }.map { Word(charTimes[it], s[it].toString()) }
            WhisperSegment(start, maxOf(start, end), body + cut.punctuation, words)
        }
    }

    /** Where to split [text]; null if the dictionary isn't loaded. */
    fun split(text: String, charTimes: List<Long>, endMs: Long): List<Cut>? {
        val tokens = Japanese.analyze(text) ?: return null
        if (tokens.isEmpty()) return listOf(Cut(0, text.length, ""))
        fun pauseAfter(k: Int): Long {
            val b = tokens[k].position + tokens[k].surface.length
            if (b >= text.length) return Long.MAX_VALUE
            return charTimes[b] - charTimes[b - 1] - CHAR_MS
        }

        val cuts = mutableListOf<Cut>()
        var start = 0
        var startToken = 0
        var k = 0
        while (k < tokens.size) {
            val tok = tokens[k]
            val end = tok.position + tok.surface.length
            val next = tokens.getOrNull(k + 1)
            val pause = pauseAfter(k)
            // Before a noun, …た / …です may instead describe it (持ち主とはぐれた傘 = "the umbrella
            // that got separated from its owner"): only a sentence that already has its topic
            // or subject (は, が, も) ends there, unless the pause is long.
            val hasTopic = (startToken..k).any { i -> tokens[i].pos1 == "助詞" && tokens[i].surface in setOf("は", "が", "も") }
            val ending = sentenceEnd(tok, next, pause)
                ?.takeIf { next?.pos1 != "名詞" || hasTopic || pause >= 700 }
            if (ending != null && end - start >= MIN_CHARS) {
                cuts += Cut(start, end, ending)
                start = end
                startToken = k + 1
            } else if (next != null && (end - start > MAX_CHARS || charTimes[minOf(end, text.length - 1)] - charTimes[start] > MAX_MS)) {
                // Too long without an ending: cut at the best place so far.
                val best = (startToken until k).filter { i ->
                    val b = tokens[i].position + tokens[i].surface.length
                    b - start >= MIN_CHARS && end - b >= 2
                }.maxByOrNull { i -> pauseAfter(i).coerceAtMost(2_000) + if (isClauseConnector(tokens[i])) 400 else 0 }
                if (best != null) {
                    val b = tokens[best].position + tokens[best].surface.length
                    cuts += Cut(start, b, if (isClauseConnector(tokens[best]) || pauseAfter(best) >= 150) "、" else "")
                    start = b
                    startToken = best + 1
                }
            }
            k++
        }
        if (start < text.length) {
            val last = tokens.last()
            cuts += Cut(start, text.length, sentenceEnd(last, null, Long.MAX_VALUE) ?: "")
        }
        // A tiny leftover joins the sentence before it.
        val merged = mutableListOf<Cut>()
        for (c in cuts) {
            val prev = merged.lastOrNull()
            if (prev != null && c.end - c.start < 3) merged[merged.lastIndex] = Cut(prev.start, c.end, c.punctuation.ifEmpty { prev.punctuation })
            else merged += c
        }
        return merged
    }

    /** "。", "？" if [tok] ends a sentence (given the next token and the pause after it), else null. */
    private fun sentenceEnd(tok: JaAnalysis, next: JaAnalysis?, pauseMs: Long): String? {
        val nextGlues = next != null && (next.pos1 == "助動詞" || next.pos1 == "助詞")
        val question = tok.surface in setOf("か", "かな", "かね", "っけ")
        val mark = if (question) "？" else "。"
        // Sentence-final particles: ね, よ, か, な, わ, ぞ, さ…
        if (tok.pos1 == "助詞" && tok.pos2 == "終助詞" && !(next?.pos1 == "助詞" && next.pos2 == "終助詞")) {
            return if (next == null || pauseMs >= 80) mark else null
        }
        // です, ます, た, だ, ない, ん (ません), う (ましょう) in their final form.
        if (tok.pos1 == "助動詞" && tok.conjugation == "基本形" && !nextGlues) {
            val needed = if (next?.pos1 == "名詞") 250 else 100 // a noun may be a relative clause (…した人)
            return if (next == null || pauseMs >= needed) mark else null
        }
        // Plain verbs and adjectives can end casual sentences, but often don't: need a clear pause.
        if ((tok.pos1 == "動詞" || tok.pos1 == "形容詞") && tok.conjugation == "基本形" && !nextGlues && next?.pos1 != "名詞") {
            return if (next == null || pauseMs >= 350) mark else null
        }
        return null
    }

    private fun isClauseConnector(tok: JaAnalysis) =
        (tok.pos1 == "助詞" && tok.pos2 == "接続助詞") || tok.surface in setOf("一方", "ともに", "また", "そして")
}
