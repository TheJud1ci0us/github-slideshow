package com.ascolta.app.net

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale

data class ParsedFeed(
    val title: String,
    val author: String?,
    val description: String?,
    val imageUrl: String?,
    val language: String?,
    val episodes: List<ParsedEpisode>,
)

data class ParsedEpisode(
    val guid: String,
    val title: String,
    val description: String?,
    val audioUrl: String,
    val mimeType: String?,
    val pubDate: Long,
    val durationSec: Long,
    val imageUrl: String?,
    val transcriptUrl: String?,
    val transcriptType: String?,
)

/** Minimal, forgiving RSS 2.0 + iTunes + Podcasting 2.0 parser. */
object RssParser {

    fun parse(input: InputStream): ParsedFeed {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        var title: String? = null
        var author: String? = null
        var description: String? = null
        var itunesImage: String? = null
        var rssImage: String? = null
        var language: String? = null
        val episodes = mutableListOf<ParsedEpisode>()

        var inItem = false
        var inImage = false
        var item = ItemBuilder()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                val name = parser.name.lowercase(Locale.ROOT)
                when {
                    name == "item" -> {
                        inItem = true
                        item = ItemBuilder()
                    }
                    name == "image" && !inItem -> inImage = true
                    name == "itunes:image" -> {
                        val href = parser.getAttributeValue(null, "href")
                        if (inItem) item.image = href else if (itunesImage == null) itunesImage = href
                    }
                    name == "enclosure" && inItem -> {
                        item.audioUrl = parser.getAttributeValue(null, "url")
                        item.mimeType = parser.getAttributeValue(null, "type")
                    }
                    name == "podcast:transcript" && inItem -> {
                        val url = parser.getAttributeValue(null, "url")
                        if (url != null) item.transcripts += url to parser.getAttributeValue(null, "type")
                    }
                    inItem -> when (name) {
                        "title" -> item.title = readText(parser)
                        "description" -> item.description = item.description ?: readText(parser)
                        "content:encoded", "itunes:summary" -> item.altDescription = item.altDescription ?: readText(parser)
                        "guid" -> item.guid = readText(parser)
                        "pubdate" -> item.pubDate = parseDate(readText(parser))
                        "itunes:duration" -> item.duration = parseDuration(readText(parser))
                    }
                    inImage -> if (name == "url") rssImage = readText(parser)
                    else -> when (name) {
                        "title" -> if (title == null) title = readText(parser)
                        "description" -> if (description == null) description = readText(parser)
                        "itunes:summary" -> if (description == null) description = readText(parser)
                        "itunes:author" -> if (author == null) author = readText(parser)
                        "language" -> if (language == null) language = readText(parser)
                    }
                }
            } else if (event == XmlPullParser.END_TAG) {
                when (parser.name.lowercase(Locale.ROOT)) {
                    "item" -> {
                        item.build()?.let { episodes += it }
                        inItem = false
                    }
                    "image" -> inImage = false
                }
            }
            event = parser.next()
        }

        return ParsedFeed(
            title = title?.takeIf { it.isNotBlank() } ?: "Untitled podcast",
            author = author,
            description = description,
            imageUrl = itunesImage ?: rssImage,
            language = language?.lowercase(Locale.ROOT)?.substringBefore('-'),
            episodes = episodes,
        )
    }

    private class ItemBuilder {
        var title: String? = null
        var description: String? = null
        var altDescription: String? = null
        var guid: String? = null
        var pubDate: Long = 0
        var duration: Long = 0
        var image: String? = null
        var audioUrl: String? = null
        var mimeType: String? = null
        val transcripts = mutableListOf<Pair<String, String?>>()

        fun build(): ParsedEpisode? {
            val url = audioUrl?.takeIf { it.isNotBlank() } ?: return null
            val transcript = transcripts.minByOrNull { transcriptRank(it.second, it.first) }
                ?.takeIf { transcriptRank(it.second, it.first) < UNUSABLE }
            return ParsedEpisode(
                guid = guid?.takeIf { it.isNotBlank() } ?: url,
                title = title?.takeIf { it.isNotBlank() } ?: "Untitled episode",
                description = description ?: altDescription,
                audioUrl = url,
                mimeType = mimeType,
                pubDate = pubDate,
                durationSec = duration,
                imageUrl = image,
                transcriptUrl = transcript?.first,
                transcriptType = transcript?.second,
            )
        }
    }

    private const val UNUSABLE = 100

    /** Prefer transcripts with timestamps; plain text/HTML can't be synced to audio. */
    fun transcriptRank(type: String?, url: String): Int {
        val t = (type ?: "").lowercase(Locale.ROOT)
        val u = url.lowercase(Locale.ROOT)
        return when {
            "json" in t || u.endsWith(".json") -> 0
            "vtt" in t || u.endsWith(".vtt") -> 1
            "srt" in t || "subrip" in t || u.endsWith(".srt") -> 2
            else -> UNUSABLE
        }
    }

    /** Reads all text inside the current element, tolerating nested markup. */
    private fun readText(parser: XmlPullParser): String {
        val depth = parser.depth
        val sb = StringBuilder()
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == depth) && event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.TEXT || event == XmlPullParser.CDSECT) sb.append(parser.text)
            event = parser.next()
        }
        return sb.toString().trim()
    }

    private val DATE_FORMATS = listOf(
        "EEE, d MMM yyyy HH:mm:ss Z",
        "EEE, d MMM yyyy HH:mm:ss zzz",
        "EEE, d MMM yyyy HH:mm Z",
        "EEE, d MMM yyyy HH:mm zzz",
        "d MMM yyyy HH:mm:ss Z",
        "d MMM yyyy HH:mm:ss zzz",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
    )

    fun parseDate(text: String): Long {
        val value = text.trim()
        for (pattern in DATE_FORMATS) {
            try {
                val format = SimpleDateFormat(pattern, Locale.US).apply { isLenient = true }
                return format.parse(value)?.time ?: continue
            } catch (_: Exception) {
            }
        }
        return 0
    }

    /** Accepts "3723", "3723.5", "62:03" and "1:02:03". */
    fun parseDuration(text: String): Long {
        val parts = text.trim().split(':')
        return try {
            parts.fold(0.0) { acc, part -> acc * 60 + part.trim().toDouble() }.toLong()
        } catch (_: NumberFormatException) {
            0
        }
    }
}

/** Reads podcast feed URLs from an OPML export (from Pocket Casts, Overcast, AntennaPod, …). */
object OpmlParser {
    fun parse(input: InputStream): List<String> {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(input, null)
        val urls = mutableListOf<String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name.equals("outline", ignoreCase = true)) {
                val url = parser.getAttributeValue(null, "xmlUrl") ?: parser.getAttributeValue(null, "xmlurl")
                if (!url.isNullOrBlank()) urls += url.trim()
            }
            event = parser.next()
        }
        return urls.distinct()
    }
}
