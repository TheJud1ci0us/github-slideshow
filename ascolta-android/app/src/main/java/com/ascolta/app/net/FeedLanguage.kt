package com.ascolta.app.net

import com.ascolta.app.data.LanguageCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * The language a podcast's feed declares (<language> in its RSS), read from just the start of
 * the feed so checking a list of suggestions stays quick and light on data.
 */
class FeedLanguage(private val http: OkHttpClient) {
    private val cache = ConcurrentHashMap<String, String>()
    private val limit = Semaphore(8)

    /** Two-letter code, or null if the feed doesn't say (or can't be read in time). */
    suspend fun of(feedUrl: String): String? {
        cache[feedUrl]?.let { return it.ifEmpty { null } }
        val lang = limit.withPermit {
            withTimeoutOrNull(6_000) { withContext(Dispatchers.IO) { runCatching { read(feedUrl) }.getOrNull() } }
        }
        cache[feedUrl] = lang.orEmpty()
        return lang
    }

    suspend fun ofAll(feedUrls: List<String>): Map<String, String?> = coroutineScope {
        feedUrls.distinct().map { url -> async { url to of(url) } }.awaitAll().toMap()
    }

    private fun read(feedUrl: String): String? {
        http.newCall(Request.Builder().url(feedUrl).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val source = response.body?.source() ?: return null
            // The language comes near the top of the channel; stop after 64 KB.
            val head = StringBuilder()
            val buffer = ByteArray(8_192)
            val stream = source.inputStream()
            while (head.length < 65_536) {
                val n = stream.read(buffer)
                if (n <= 0) break
                head.append(String(buffer, 0, n, Charsets.UTF_8))
                TAG.find(head)?.let { return LanguageCheck.normalize(it.groupValues[1]) }
            }
            return null
        }
    }

    private companion object {
        val TAG = Regex("""<language>\s*([A-Za-z]{2,3}(?:[-_][A-Za-z]{2,4})?)\s*</language>""", RegexOption.IGNORE_CASE)
    }
}
