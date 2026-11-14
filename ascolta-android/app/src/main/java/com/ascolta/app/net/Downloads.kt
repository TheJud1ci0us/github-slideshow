package com.ascolta.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

object Http {
    const val USER_AGENT = "LingoPod/0.12 (Android podcast player)"

    fun client(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
        }
        .build()
}

suspend fun OkHttpClient.fetchString(url: String): String = readResponse(Request.Builder().url(url).build()) { response ->
    if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
    response.body?.string().orEmpty()
}

/**
 * Downloads [url] into [dest]. Partial downloads are kept in `dest.part` and resumed
 * with an HTTP Range request when the server supports it.
 * [onProgress] receives a fraction in 0..1, or -1 when the size is unknown.
 */
suspend fun OkHttpClient.downloadTo(
    url: String,
    dest: File,
    onProgress: suspend (Float) -> Unit,
) = downloadWithBytes(url, dest) { written, total -> onProgress(if (total > 0) written.toFloat() / total else -1f) }

/** Like [downloadTo] but reports bytes written and total size (-1 if unknown). */
suspend fun OkHttpClient.downloadWithBytes(
    url: String,
    dest: File,
    onProgress: suspend (written: Long, total: Long) -> Unit,
) = withContext(Dispatchers.IO) {
    dest.parentFile?.mkdirs()
    val part = File(dest.path + ".part")
    val existing = if (part.exists()) part.length() else 0L
    val request = Request.Builder().url(url).apply {
        if (existing > 0) header("Range", "bytes=$existing-")
    }.build()

    var complete = false
    newCall(request).execute().use { response ->
        // 416: the partial file already holds every byte (the app stopped just before renaming it).
        if (response.code == 416 && existing > 0) {
            complete = true
            return@use
        }
        if (!response.isSuccessful) throw IOException("HTTP ${response.code} downloading $url")
        val body = response.body ?: throw IOException("Empty response")
        val resumed = response.code == 206 && existing > 0
        val offset = if (resumed) existing else 0L
        val total = body.contentLength().let { if (it > 0) it + offset else -1L }

        FileOutputStream(part, resumed).use { out ->
            body.byteStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                var written = offset
                var lastReportedAt = 0L
                onProgress(written, total)
                while (true) {
                    ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    written += read
                    val now = System.currentTimeMillis()
                    if (now - lastReportedAt >= 500) {
                        lastReportedAt = now
                        onProgress(written, total)
                    }
                }
                onProgress(written, total)
            }
        }
        // A connection that ended early must not pass for a finished file.
        if (total > 0 && part.length() != total) {
            throw IOException("Incomplete download: ${part.length()} of $total bytes")
        }
    }
    if (complete) onProgress(existing, existing)
    if (dest.exists()) dest.delete()
    if (!part.renameTo(dest)) throw IOException("Could not move download into place")
}
