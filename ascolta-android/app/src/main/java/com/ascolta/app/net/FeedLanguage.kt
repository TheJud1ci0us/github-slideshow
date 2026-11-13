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
class FeedLanguage(private val http: OkHttpClient, private val transcriptStore: java.io.File? = null) {
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

    /** Feeds checked for official transcripts (kept on the phone, so each is read only once). */
    private val transcripts: ConcurrentHashMap<String, Boolean> by lazy {
        ConcurrentHashMap<String, Boolean>().also { map ->
            runCatching {
                transcriptStore?.takeIf { it.exists() }?.forEachLine { line ->
                    val tab = line.lastIndexOf('\t')
                    if (tab > 0) map[line.substring(0, tab)] = line.endsWith("1")
                }
            }
        }
    }

    private fun remember(feedUrl: String, has: Boolean) {
        if (transcripts.put(feedUrl, has) == null) runCatching {
            transcriptStore?.appendText("$feedUrl\t${if (has) 1 else 0}\n")
        }
    }

    /** Whether the feed is known to give official transcripts; null when not checked yet. */
    fun knownTranscripts(feedUrl: String): Boolean? = TranscriptHosts.quickAnswer(feedUrl) ?: transcripts[feedUrl]

    /**
     * Whether the feed's latest episode comes with an official transcript (`<podcast:transcript>`).
     * Hosts that never publish them (Spotify for Creators…) are answered without any download;
     * other feeds are read up to the end of their latest episode (at most 192 KB), once.
     */
    suspend fun hasTranscripts(feedUrl: String): Boolean {
        knownTranscripts(feedUrl)?.let { return it }
        val found = limit.withPermit {
            withTimeoutOrNull(10_000) {
                withContext(Dispatchers.IO) {
                    try {
                        readTranscripts(feedUrl)
                    } catch (e: DeadFeed) {
                        dead += feedUrl
                        null
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        }
        // Not kept when the feed couldn't be read: it's checked again next time.
        found?.let { remember(feedUrl, it) }
        return found == true
    }

    /** The shows of [batch] that give official transcripts (checked in parallel). */
    suspend fun withTranscripts(batch: List<com.ascolta.app.net.DirectoryResult>): List<com.ascolta.app.net.DirectoryResult> = coroutineScope {
        batch.map { r -> async { r.takeIf { hasTranscripts(it.feedUrl) } } }.awaitAll().filterNotNull()
    }

    /** One read for both answers: the transcript tag, and the language on the way (cached too). */
    private fun readTranscripts(feedUrl: String): Boolean? {
        http.newCall(Request.Builder().url(feedUrl).build()).execute().use { response ->
            if (response.code in 400..499 && response.code != 429) throw DeadFeed()
            if (!response.isSuccessful) return null
            val stream = response.body?.source()?.inputStream() ?: return null
            val text = StringBuilder()
            val buffer = ByteArray(8_192)
            while (text.length < 196_608) {
                val n = stream.read(buffer)
                if (n <= 0) break
                text.append(String(buffer, 0, n, Charsets.UTF_8))
                if (!cache.containsKey(feedUrl)) TAG.find(text)?.let { cache[feedUrl] = LanguageCheck.normalize(it.groupValues[1]).orEmpty() }
                if (TRANSCRIPT.containsMatchIn(text)) return true
                if (ITEM_END.containsMatchIn(text)) return false // the latest episode has none
            }
            if (text.isNotEmpty() && !FEED.containsMatchIn(text)) throw DeadFeed()
            return false
        }
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
        val TRANSCRIPT = Regex("""<podcast:transcript[\s>]""", RegexOption.IGNORE_CASE)
        val ITEM_END = Regex("""</item>""", RegexOption.IGNORE_CASE)
        val TAG = Regex("""<language>\s*([A-Za-z]{2,3}(?:[-_][A-Za-z]{2,4})?)\s*</language>""", RegexOption.IGNORE_CASE)
    }
}
