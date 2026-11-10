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
 *
 * It also notices dead feeds: Apple keeps listing shows whose feed is gone (404), moved to a
 * web page, or refuses apps; following one of those only gives an error, so they are hidden
 * from suggestions. A feed that is just slow isn't counted as dead.
 */
class FeedLanguage(private val http: OkHttpClient) {
    private val cache = ConcurrentHashMap<String, String>()
    private val dead = ConcurrentHashMap.newKeySet<String>()
    private val limit = Semaphore(8)

    /** True if this feed was found not to work (checked by [of] / [ofAll]). */
    fun isDead(feedUrl: String): Boolean = feedUrl in dead

    /** Thrown inside [read] for a feed that can't be followed. */
    private class DeadFeed : Exception()

    /** Two-letter code, or null if the feed doesn't say (or can't be read in time). */
    suspend fun of(feedUrl: String): String? {
        cache[feedUrl]?.let { return it.ifEmpty { null } }
        val lang = limit.withPermit {
            withTimeoutOrNull(6_000) {
                withContext(Dispatchers.IO) {
                    try {
                        read(feedUrl)
                    } catch (e: DeadFeed) {
                        dead += feedUrl
                        null
                    } catch (e: java.net.UnknownHostException) {
                        dead += feedUrl // the site no longer exists
                        null
                    } catch (e: javax.net.ssl.SSLException) {
                        dead += feedUrl // broken certificate: following would fail too
                        null
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        }
        cache[feedUrl] = lang.orEmpty()
        return lang
    }

    suspend fun ofAll(feedUrls: List<String>): Map<String, String?> = coroutineScope {
        feedUrls.distinct().map { url -> async { url to of(url) } }.awaitAll().toMap()
    }

    private fun read(feedUrl: String): String? {
        http.newCall(Request.Builder().url(feedUrl).build()).execute().use { response ->
            // Gone, forbidden or not found: following it would fail. (Server errors may pass.)
            if (response.code in 400..499 && response.code != 429) throw DeadFeed()
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
            // A web page (or anything else) instead of a podcast feed.
            if (head.isNotEmpty() && !FEED.containsMatchIn(head)) throw DeadFeed()
            return null
        }
    }

    private companion object {
        val FEED = Regex("""<(rss|feed|rdf:RDF|channel)[\s>]""", RegexOption.IGNORE_CASE)
        val TAG = Regex("""<language>\s*([A-Za-z]{2,3}(?:[-_][A-Za-z]{2,4})?)\s*</language>""", RegexOption.IGNORE_CASE)
    }
}
