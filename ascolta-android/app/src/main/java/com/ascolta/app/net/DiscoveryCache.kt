package com.ascolta.app.net

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** A small, short-lived first page. Only already language-checked recommendations are stored. */
class DiscoveryCache(private val directory: File, private val now: () -> Long = System::currentTimeMillis) {
    private fun file(key: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(directory, "$hash.json")
    }

    @Synchronized fun read(key: String): List<DirectoryResult> = runCatching {
        val file = file(key)
        if (!file.isFile || file.length() > 256_000) return emptyList()
        val json = JSONObject(file.readText())
        if (now() - json.getLong("at") !in 0..6 * 60 * 60_000L) return emptyList()
        val items = json.getJSONArray("items")
        (0 until minOf(items.length(), 6)).mapNotNull { i ->
            val o = items.getJSONObject(i)
            val url = o.getString("feedUrl")
            if (!url.startsWith("https://") && !url.startsWith("http://")) return@mapNotNull null
            fun optional(key: String) = if (o.isNull(key)) null else o.optString(key).takeIf { it.isNotBlank() }
            DirectoryResult(o.getString("title"), optional("author"), url, optional("artworkUrl"), optional("genre"), optional("summary"), optional("language"), o.optJSONArray("genreIds")?.let { ids -> (0 until ids.length()).map { ids.getInt(it) } }.orEmpty())
        }.distinctBy { it.feedUrl }
    }.getOrDefault(emptyList())

    @Synchronized fun write(key: String, items: List<DirectoryResult>) {
        if (items.isEmpty()) return
        directory.mkdirs()
        val array = JSONArray()
        items.distinctBy { it.feedUrl }.take(6).forEach { r ->
            array.put(JSONObject().put("title", r.title).put("author", r.author).put("feedUrl", r.feedUrl)
                .put("artworkUrl", r.artworkUrl).put("genre", r.genre).put("summary", r.summary?.take(2_000)).put("language", r.language).put("genreIds", JSONArray(r.genreIds)))
        }
        val dest = file(key)
        val temp = File.createTempFile("recs-", ".tmp", directory)
        try {
            temp.writeText(JSONObject().put("at", now()).put("items", array).toString())
            check(temp.renameTo(dest)) { "Cannot update recommendations cache" }
        } finally { temp.delete() }
        directory.listFiles()?.filter { it.extension == "json" }?.sortedByDescending { it.lastModified() }?.drop(8)?.forEach { it.delete() }
    }
}
