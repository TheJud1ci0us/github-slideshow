package com.ascolta.app.data

import android.content.Context
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * The most common words of English and of the European languages Parakeet covers (wordfreq,
 * CC BY-SA 4.0), most frequent first: word → rank. Loaded once per language from
 * assets/words/<lang>.txt (built by tools/build_wordlists.py). Plain text: the build unpacks
 * and renames ".gz" assets, and the APK compresses them anyway.
 */
class WordLists(private val open: (String) -> InputStream?) {
    constructor(context: Context) : this({ lang -> kotlin.runCatching { context.assets.open(assetName(lang)) }.getOrNull() })

    companion object {
        /** The asset holding [language]'s list (the same name in the app and in the tests). */
        fun assetName(language: String) = "words/$language.txt"
    }

    private val cache = ConcurrentHashMap<String, Map<String, Int>>()

    fun ranks(language: String): Map<String, Int>? = cache[language] ?: run {
        val stream = open(language) ?: return null
        val map = HashMap<String, Int>(32_768)
        stream.bufferedReader().useLines { lines -> lines.forEachIndexed { i, w -> if (w.isNotEmpty()) map.putIfAbsent(w, i) } }
        cache[language] = map
        map
    }
}
