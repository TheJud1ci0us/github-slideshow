package com.ascolta.app.net

/**
 * Smaller pictures for small places. Podcast feeds often point to covers of 3000×3000 pixels
 * (1–3 MB each); Apple's copies (on mzstatic.com) can be asked for at any size, so a 56 dp
 * thumbnail downloads a 200-pixel image of a few kilobytes instead.
 */
object Artwork {
    private val APPLE = Regex("""^(https?://[^/]*mzstatic\.com/.+)/\d+x\d+[a-z]*\.(jpg|jpeg|png|webp)$""", RegexOption.IGNORE_CASE)

    /** True if the image can be asked for at another size (an Apple copy). */
    fun resizable(url: String?): Boolean = url != null && APPLE.matches(url)

    /** [url] at about [px] pixels wide when possible (sizes 100–600 in steps, so the cache is shared). */
    fun sized(url: String?, px: Int): String? {
        if (url == null) return null
        val m = APPLE.find(url) ?: return url
        val size = listOf(100, 200, 300, 400, 600).firstOrNull { it >= px } ?: 600
        return "${m.groupValues[1]}/${size}x${size}bb.jpg"
    }
}
