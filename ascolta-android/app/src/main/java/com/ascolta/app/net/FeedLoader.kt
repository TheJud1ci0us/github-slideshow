package com.ascolta.app.net

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Reuses recently parsed feeds and coalesces simultaneous opens/refreshes of the same show. */
class FeedLoader(
    private val http: OkHttpClient,
    private val now: () -> Long = System::currentTimeMillis,
    private val freshForMs: Long = 5 * 60_000,
) {
    private data class Entry(val feed: ParsedFeed, val at: Long, val characters: Int)
    private val entries = LinkedHashMap<String, Entry>(4, 0.75f, true)
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun load(url: String, refresh: Boolean = false): ParsedFeed = locks.getOrPut(url) { Mutex() }.withLock {
        val cached = synchronized(entries) { entries[url] }
        if (!refresh && cached != null && now() - cached.at < freshForMs) return@withLock cached.feed
        val request = Request.Builder().url(url).apply {
            // Revalidate explicitly requested refreshes. OkHttp keeps the body for 304 replies
            // and sends the feed's ETag/Last-Modified validators when available.
            if (refresh) cacheControl(CacheControl.Builder().maxAge(0, TimeUnit.SECONDS).build())
        }.build()
        http.readResponse(request) { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val feed = if (response.networkResponse?.code == 304 && cached != null) cached.feed
                else RssParser.parse(response.body?.byteStream() ?: throw IOException("Empty podcast feed"))
            synchronized(entries) {
                if (response.cacheControl.noStore) entries.remove(url)
                else {
                    val size = feed.title.length + (feed.description?.length ?: 0) + feed.episodes.sumOf {
                        it.title.length + (it.description?.length ?: 0) + it.audioUrl.length + (it.imageUrl?.length ?: 0)
                    }
                    entries[url] = Entry(feed, now(), size)
                    // Bound retained descriptions as well as the number of feeds. No episodes are truncated.
                    while (entries.size > 3 || entries.values.sumOf { it.characters.toLong() } > 1_000_000) {
                        entries.remove(entries.keys.first())
                    }
                }
            }
            feed
        }
    }
}
