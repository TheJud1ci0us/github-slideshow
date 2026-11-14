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
    private val retryDelayMs: Long = 2_000,
    /** A rare kind of show (official transcripts): stop after checking this many for one page. */
    private val maxCheckedPerPage: Int = Int.MAX_VALUE,
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
    suspend fun next(limit: Int = pageSize): List<DirectoryResult> = lock.withLock {
        require(limit > 0)
        val out = mutableListOf<DirectoryResult>()
        var checked = 0
        try {
            while (out.size < limit) {
                if (checked >= maxCheckedPerPage && ready.isEmpty()) {
                    // Searched long enough: what was found is shown, and the list ends here.
                    exhausted = true
                    break
                }
                if (ready.isNotEmpty()) {
                    out += ready.removeFirst()
                    continue
                }
                if (pending.isEmpty()) {
                    if (nextSource >= sources.size) {
                        exhausted = true
                        break
                    }
                    // A source that fails (network, the store's rate limit) is tried once more a bit later.
                    val source = sources[nextSource]
                    val found = runCatching { source() }.getOrElse {
                        if (it is kotlinx.coroutines.CancellationException) throw it
                        kotlinx.coroutines.delay(retryDelayMs)
                        runCatching { source() }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; emptyList() }
                    }
                    nextSource++
                    found.filter { seen.add(it.feedUrl) }.forEach(pending::addLast)
                    continue
                }
                val batch = List(minOf(pending.size, limit - out.size)) { pending.removeFirst() }
                checked += batch.size
                ready += runCatching { check(batch) }.getOrElse {
                    if (it is kotlinx.coroutines.CancellationException) {
                        batch.asReversed().forEach(pending::addFirst)
                        throw it
                    }
                    emptyList()
                }
            }
            out
        } catch (e: kotlinx.coroutines.CancellationException) {
            out.asReversed().forEach(ready::addFirst)
            throw e
        }
    }
}
