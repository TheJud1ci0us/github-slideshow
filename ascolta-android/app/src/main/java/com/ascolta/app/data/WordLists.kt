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
    private var openMore: (String) -> InputStream? = { null }

    constructor(open: (String) -> InputStream?, openMore: (String) -> InputStream?) : this(open) { this.openMore = openMore }

    constructor(context: Context) : this(
        { lang -> kotlin.runCatching { context.assets.open(assetName(lang)) }.getOrNull() },
        { lang -> kotlin.runCatching { context.assets.open(moreName(lang)) }.getOrNull() },
    )

    companion object {
        /** The asset holding [language]'s list (the same name in the app and in the tests). */
        fun assetName(language: String) = "words/$language.txt"
        /** The next 75,000 words of [language], for the languages [DoubtfulWords] covers. */
        fun moreName(language: String) = "words-more/$language.txt"
    }

    private val cache = ConcurrentHashMap<String, Map<String, Int>>()

    fun ranks(language: String): Map<String, Int>? = cache[language] ?: run {
        val stream = open(language) ?: return null
        val map = HashMap<String, Int>(32_768)
        stream.bufferedReader().useLines { lines -> lines.forEachIndexed { i, w -> if (w.isNotEmpty()) map.putIfAbsent(w, i) } }
        cache[language] = map
        map
    }

    private val wideCache = ConcurrentHashMap<String, Map<String, Int>>()

    /**
     * The 100,000 most common words of [language] (word → rank), or null for a language
     * without the longer list. Used to tell a real word from a misheard one.
     */
    fun wide(language: String): Map<String, Int>? = wideCache[language] ?: run {
        val common = ranks(language) ?: return null
        val stream = openMore(language) ?: return null
        val map = HashMap<String, Int>(140_000)
        map.putAll(common)
        val start = common.size
        stream.bufferedReader().useLines { lines -> lines.forEachIndexed { i, w -> if (w.isNotEmpty()) map.putIfAbsent(w, start + i) } }
        wideCache[language] = map
        map
    }
}
