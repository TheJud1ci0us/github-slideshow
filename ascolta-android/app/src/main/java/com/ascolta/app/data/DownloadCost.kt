package com.ascolta.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale

/** Sizes of downloads, for the warning shown before downloading on mobile data. */
object DownloadCost {
    /** Something to download: its name, size, and whether the size is exact (or "about"). */
    data class Item(
        val name: String,
        val bytes: Long,
        val exact: Boolean,
        /** For models: the key recorded when the learner agrees (see Settings.approve); always asked. */
        val approveKey: String? = null,
    )

    /** "652 MB", "1.2 GB", "850 KB" (pure). */
    fun formatSize(bytes: Long, locale: Locale = Locale.getDefault()): String = when {
        bytes >= 1_000_000_000 -> String.format(locale, "%.1f GB", bytes / 1e9)
        bytes >= 10_000_000 -> "${(bytes + 500_000) / 1_000_000} MB"
        bytes >= 1_000_000 -> String.format(locale, "%.1f MB", bytes / 1e6)
        else -> "${maxOf(1, (bytes + 500) / 1_000)} KB"
    }

    /** An episode's size from its length when the server doesn't say (podcasts are ~128 kbit/s, ~1 MB a minute). */
    fun estimate(durationSec: Long): Long = if (durationSec > 0) durationSec * 16_000 else 50_000_000

    /** True on mobile data (or any network that may cost money). */
    fun isMetered(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return cm.isActiveNetworkMetered || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** The file's size as the server announces it (HEAD request, following redirects), or null. */
    suspend fun remoteSize(http: OkHttpClient, url: String): Long? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(5_000) {
            runCatching {
                http.newCall(Request.Builder().url(url).head().build()).execute().use { r ->
                    r.header("Content-Length")?.toLongOrNull()?.takeIf { r.isSuccessful && it > 0 }
                }
            }.getOrNull()
        }
    }
}
