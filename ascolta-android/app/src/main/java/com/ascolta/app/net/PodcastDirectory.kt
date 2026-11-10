package com.ascolta.app.net

import android.net.Uri
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import org.json.JSONObject

data class DirectoryResult(
    val title: String,
    val author: String?,
    val feedUrl: String,
    val artworkUrl: String?,
    val genre: String?,
    /** Short description (from the charts), used to guess the language level. */
    val summary: String? = null,
    /** Language the feed declares (filled in when checked). */
    val language: String? = null,
    /** Apple's genre ids: the main one first, then its parents and others (e.g. 1534 Natural Sciences, 26, 1533 Science). */
    val genreIds: List<Int> = emptyList(),
)

/** What a pasted link turned out to be. */
sealed interface LinkResolution {
    data class Feed(val feedUrl: String) : LinkResolution
    /** We only know the show's name (e.g. from a Spotify link); search for it. */
    data class Search(val query: String) : LinkResolution
    data class Unsupported(val reason: String) : LinkResolution
}

/**
 * Finds podcasts using the free Apple Podcasts search API (no key needed).
 * It returns each show's public RSS feed, which is what the app subscribes to.
 */
class PodcastDirectory(private val http: OkHttpClient) {
    /**
     * Apple allows about 20 requests a minute: answers are kept for an hour and at most four
     * requests run at once, so browsing themes and levels doesn't get the app refused.
     */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()
    private val gate = kotlinx.coroutines.sync.Semaphore(4)

    private suspend fun get(url: String): String {
        cache[url]?.takeIf { System.currentTimeMillis() - it.first < 3_600_000 }?.let { return it.second }
        val body = gate.withPermit {
            try {
                http.fetchString(url)
            } catch (e: java.io.IOException) {
                // 403 here means "too many requests": wait a little and try once more.
                if (e.message?.startsWith("HTTP 403") != true) throw e
                kotlinx.coroutines.delay(4_000)
                http.fetchString(url)
            }
        }
        cache[url] = System.currentTimeMillis() to body
        return body
    }

    suspend fun search(term: String, country: String?, limit: Int = 40): List<DirectoryResult> {
        val url = Uri.parse("https://itunes.apple.com/search").buildUpon()
            .appendQueryParameter("media", "podcast")
            .appendQueryParameter("entity", "podcast")
            .appendQueryParameter("limit", limit.toString())
            .appendQueryParameter("term", term)
            .apply { if (country != null) appendQueryParameter("country", country) }
            .build().toString()
        return parseResults(get(url))
    }

    /**
     * Searches within one Apple genre (e.g. 1498 = Language Learning) in a country's store.
     * Note: Apple's search ignores genreId (checked November 2026); callers filter on [DirectoryResult.genreIds].
     */
    suspend fun searchGenre(term: String, genreId: Int, country: String?): List<DirectoryResult> {
        val url = Uri.parse("https://itunes.apple.com/search").buildUpon()
            .appendQueryParameter("media", "podcast")
            .appendQueryParameter("entity", "podcast")
            .appendQueryParameter("limit", "40")
            .appendQueryParameter("genreId", genreId.toString())
            .appendQueryParameter("term", term)
            .apply { if (country != null) appendQueryParameter("country", country) }
            .build().toString()
        return parseResults(get(url))
    }

    /**
     * Top podcasts of a genre in a country's store (Apple's charts), with their descriptions.
     * The chart has no feed addresses, so they are fetched with one lookup request.
     */
    suspend fun topByGenre(genreId: Int, country: String, limit: Int = 50): List<DirectoryResult> {
        val chart = JSONObject(
            get("https://itunes.apple.com/${country.lowercase()}/rss/toppodcasts/limit=$limit/genre=$genreId/json"),
        ).optJSONObject("feed")?.optJSONArray("entry") ?: return emptyList()
        val entries = (0 until chart.length()).mapNotNull { i ->
            val e = chart.getJSONObject(i)
            val id = e.optJSONObject("id")?.optJSONObject("attributes")?.optString("im:id")?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            id to e.optJSONObject("summary")?.optString("label")
        }
        if (entries.isEmpty()) return emptyList()
        val summaries = entries.toMap()
        val body = get(
            "https://itunes.apple.com/lookup?entity=podcast&country=${country.lowercase()}&id=" + entries.joinToString(",") { it.first },
        )
        val found = JSONObject(body).optJSONArray("results") ?: return emptyList()
        val byId = (0 until found.length()).associate { i ->
            val o = found.getJSONObject(i)
            o.optLong("collectionId").toString() to o
        }
        // Keep the chart's order.
        return entries.mapNotNull { (id, _) ->
            val o = byId[id] ?: return@mapNotNull null
            parseResult(o)?.copy(summary = summaries[id])
        }.distinctBy { it.feedUrl }
    }

    suspend fun lookup(appleId: String): DirectoryResult? {
        val body = get("https://itunes.apple.com/lookup?entity=podcast&id=$appleId")
        return parseResults(body).firstOrNull()
    }

    private fun parseResults(body: String): List<DirectoryResult> {
        val results = JSONObject(body).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i -> parseResult(results.getJSONObject(i)) }
            .distinctBy { it.feedUrl } // Apple sometimes lists the same show twice
    }

    private fun parseResult(o: JSONObject): DirectoryResult? {
        val feed = o.optString("feedUrl").takeIf { it.isNotBlank() } ?: return null
        return DirectoryResult(
            title = o.optString("collectionName", o.optString("trackName", "Podcast")),
            author = o.optString("artistName").takeIf { it.isNotBlank() },
            feedUrl = feed,
            artworkUrl = o.optString("artworkUrl600").ifBlank { o.optString("artworkUrl100") }.ifBlank { null },
            genre = o.optString("primaryGenreName").takeIf { it.isNotBlank() },
            genreIds = o.optJSONArray("genreIds")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).toIntOrNull() } }.orEmpty(),
        )
    }

    /**
     * Turns whatever the user pasted or shared into a feed:
     *  - RSS feed URLs are used directly
     *  - Apple Podcasts links are looked up by id
     *  - Spotify links: Spotify doesn't expose audio or feeds, so we read the show's
     *    name from the page and search the open directory for the same show
     *  - Web pages that advertise an RSS feed (<link rel="alternate">) are followed
     */
    suspend fun resolve(input: String): LinkResolution {
        val text = input.trim()
        val url = URL_REGEX.find(text)?.value
            ?: return LinkResolution.Search(text)
        val uri = Uri.parse(url)
        val host = uri.host?.lowercase() ?: ""

        if ("podcasts.apple.com" in host || "itunes.apple.com" in host) {
            val id = Regex("""id(\d+)""").find(url)?.groupValues?.get(1)
                ?: return LinkResolution.Unsupported("Couldn't find the podcast id in that Apple link.")
            val result = lookup(id) ?: return LinkResolution.Unsupported("Apple Podcasts didn't return a feed for that show.")
            return LinkResolution.Feed(result.feedUrl)
        }

        if ("spotify.com" in host || "spotify.link" in host) {
            val html = runCatching { get(url) }.getOrNull()
                ?: return LinkResolution.Unsupported("Couldn't open the Spotify link.")
            val ogTitle = metaContent(html, "og:title")
            val ogDescription = metaContent(html, "og:description")
            // Episode pages say "Listen to this episode from <Show> on Spotify."
            val show = ogDescription?.let { Regex("""episode from (.+?) on Spotify""").find(it)?.groupValues?.get(1) }
            val name = show ?: ogTitle
                ?: return LinkResolution.Unsupported("Couldn't read the show name from Spotify.")
            return LinkResolution.Search(name)
        }

        if ("youtube.com" in host || "youtu.be" in host) {
            return LinkResolution.Unsupported("YouTube videos aren't podcast feeds. Try searching for the show's name instead.")
        }

        // Treat anything else as a feed, or a page that links to one.
        val body = runCatching { get(url) }.getOrElse {
            return LinkResolution.Unsupported("Couldn't open that link: ${it.message}")
        }
        val head = body.take(2000).lowercase()
        if ("<rss" in head || "<feed" in head || "<?xml" in head) return LinkResolution.Feed(url)
        val alternate = Regex(
            """<link[^>]+type=["']application/rss\+xml["'][^>]*>""",
            RegexOption.IGNORE_CASE,
        ).find(body)?.value?.let { Regex("""href=["']([^"']+)["']""").find(it)?.groupValues?.get(1) }
        if (alternate != null) return LinkResolution.Feed(Uri.parse(url).resolveRelative(alternate))
        return LinkResolution.Unsupported("That link isn't a podcast feed.")
    }

    private fun metaContent(html: String, property: String): String? {
        val tag = Regex("""<meta[^>]+property=["']$property["'][^>]*>""", RegexOption.IGNORE_CASE).find(html)?.value
            ?: return null
        return Regex("""content=["']([^"']*)["']""").find(tag)?.groupValues?.get(1)?.let(::decodeEntities)
    }

    private fun decodeEntities(s: String) = s
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&#x27;", "'").replace("&lt;", "<").replace("&gt;", ">")

    private fun Uri.resolveRelative(href: String): String =
        if (href.startsWith("http")) href else java.net.URI(toString()).resolve(href).toString()

    companion object {
        private val URL_REGEX = Regex("""https?://\S+""")
    }
}
