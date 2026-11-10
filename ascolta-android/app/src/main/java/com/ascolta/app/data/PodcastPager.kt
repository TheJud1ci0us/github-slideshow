package com.ascolta.app.data

import com.ascolta.app.net.DirectoryResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Endless lists in Discover: podcasts a page at a time from a series of [sources] (a theme's
 * chart, then searches, then backup searches by the language's name…). Each source is only
 * fetched when the ones before it are used up, so the backup sources are what fills the list
 * when the main way finds little. Every page goes through [check] (right language, feed still
 * working), a few shows at a time; shows already given are never repeated.
 */
class PodcastPager(
    private val sources: List<suspend () -> List<DirectoryResult>>,
    private val check: suspend (List<DirectoryResult>) -> List<DirectoryResult>,
    private val pageSize: Int = 12,
) {
    private val seen = HashSet<String>()
    private val pending = ArrayDeque<DirectoryResult>()
    /** Already checked, kept for the next page. */
    private val ready = ArrayDeque<DirectoryResult>()
    private var nextSource = 0
    private val lock = Mutex()

    /** True once every source is used up and nothing is left to give. */
    @Volatile var exhausted = false
        private set

    /** The next page (possibly shorter, or empty once [exhausted]). */
    suspend fun next(): List<DirectoryResult> = lock.withLock {
        val out = mutableListOf<DirectoryResult>()
        while (out.size < pageSize) {
            if (ready.isNotEmpty()) {
                out += ready.removeFirst()
                continue
            }
            if (pending.isEmpty()) {
                if (nextSource >= sources.size) {
                    exhausted = true
                    break
                }
                val found = runCatching { sources[nextSource++]() }.getOrDefault(emptyList())
                found.filter { seen.add(it.feedUrl) }.forEach(pending::addLast)
                continue
            }
            val batch = List(minOf(pending.size, pageSize + 4)) { pending.removeFirst() }
            ready += runCatching { check(batch) }.getOrDefault(emptyList())
        }
        out
    }
}
