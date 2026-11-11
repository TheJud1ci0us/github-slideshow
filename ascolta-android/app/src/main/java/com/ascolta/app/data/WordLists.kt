package com.ascolta.app.data

import android.content.Context
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

/**
 * The most common words of English and of the European languages Parakeet covers (wordfreq,
 * CC BY-SA 4.0), most frequent first: word → rank. Loaded once per language from
 * assets/words/<lang>.txt.gz (built by tools/build_wordlists.py).
 */
class WordLists(private val open: (String) -> InputStream?) {
    constructor(context: Context) : this({ lang -> kotlin.runCatching { context.assets.open("words/$lang.txt.gz") }.getOrNull() })

    private val cache = ConcurrentHashMap<String, Map<String, Int>>()

    fun ranks(language: String): Map<String, Int>? = cache[language] ?: run {
        val stream = open(language) ?: return null
        val map = HashMap<String, Int>(32_768)
        GZIPInputStream(stream).bufferedReader().useLines { lines -> lines.forEachIndexed { i, w -> if (w.isNotEmpty()) map.putIfAbsent(w, i) } }
        cache[language] = map
        map
    }
}
