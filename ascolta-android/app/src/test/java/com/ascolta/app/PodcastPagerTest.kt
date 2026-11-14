package com.ascolta.app

import com.ascolta.app.data.LanguageCheck
import com.ascolta.app.data.PodcastPager
import com.ascolta.app.data.Recommendations
import com.ascolta.app.net.DirectoryResult
import com.ascolta.app.whisper.SpeechModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastPagerTest {
    private fun shows(prefix: String, n: Int) = (1..n).map { DirectoryResult("$prefix $it", null, "https://feed/$prefix/$it", null, null) }

    @Test
    fun pagesComeFromEachSourceInTurnWithoutRepeats() = runBlocking {
        var fetched = 0
        val pager = PodcastPager(
            listOf(
                suspend { fetched++; shows("chart", 10) },
                suspend { fetched++; shows("chart", 5) + shows("backup", 10) }, // 5 repeats
            ),
            check = { batch -> batch.filter { !it.title.endsWith(" 3") } }, // one "dead" per source
            pageSize = 8,
        )
        val first = pager.next()
        assertEquals(8, first.size)
        assertEquals(1, fetched) // the backup isn't fetched while the first source has enough
        val rest = pager.next() + pager.next() + pager.next()
        val all = first + rest
        assertEquals(all.size, all.distinctBy { it.feedUrl }.size)
        assertEquals(18, all.size) // 10 + 10 new, minus the two filtered
        assertTrue(pager.exhausted)
    }

    @Test
    fun armenianAndFarsiUseOmnilingual() {
        assertEquals(SpeechModel.OMNILINGUAL, SpeechModel.recommended("hy"))
        assertEquals(SpeechModel.OMNILINGUAL, SpeechModel.recommended("fa"))
        assertEquals(SpeechModel.BASE, SpeechModel.recommended("it"))
    }

    @Test
    fun backupSearchesUseTheLanguagesOwnName() {
        val names = Recommendations.searchNames("hi")
        assertTrue("हिंदी" in names)
        assertTrue("Hindi" in names)
        assertTrue(LanguageCheck.mentionsLanguage("Bhagavad Gita (Hindi)", "hi"))
        assertFalse(LanguageCheck.mentionsLanguage("Hindi film music charts explained", "hi"))
    }

    @Test
    fun aFailingSourceIsTriedAgainOnce() = runBlocking {
        var calls = 0
        val pager = PodcastPager(
            listOf(suspend { calls++; if (calls == 1) error("rate limited") else shows("chart", 5) }),
            check = { it }, pageSize = 10, retryDelayMs = 0,
        )
        assertEquals(5, pager.next().size)
        assertEquals(2, calls)
    }

    @Test
    fun moreSourcesStartWithTheChosenThemeThenAlternateThemes() = runBlocking {
        val asked = mutableListOf<Pair<Int, String>>()
        val sources = Recommendations.moreSources({ id, store -> asked += id to store; emptyList() }, listOf("BR", "PT"), listOf(1545))
        sources.forEach { it() }
        // Sports' sub-themes first, in both stores.
        val sports = Recommendations.SUBGENRES.getValue(1545)
        assertEquals(sports.flatMap { listOf(it to "BR", it to "PT") }, asked.take(sports.size * 2))
        // Then the other themes in turn: their own charts first, one theme after the other.
        val next = asked.drop(sports.size * 2).take(6).map { it.first }.distinct()
        assertTrue(next.size >= 3)
        assertTrue(next.none { it in sports || it == 1545 })
        // Everything is covered once per store, and Language Learning isn't in the tail.
        assertEquals(asked.size, asked.distinct().size)
        assertFalse(asked.any { it.first == 1498 })
    }

    @Test
    fun aRareFilterStopsSearchingAfterTheCap() = runBlocking {
        var fetched = 0
        val pager = PodcastPager(
            List(50) { n -> suspend { fetched++; shows("s$n", 10) } },
            check = { batch -> batch.filter { it.title == "s2 4" } }, // one match in the third source
            pageSize = 10, maxCheckedPerPage = 40,
        )
        val page = pager.next()
        assertEquals(listOf("s2 4"), page.map { it.title })
        assertTrue(pager.exhausted)
        assertTrue(fetched <= 5) // didn't read all 50 sources
    }

    @Test
    fun hostsThatNeverPublishTranscriptsAreAnsweredWithoutDownloading() {
        assertEquals(false, com.ascolta.app.net.TranscriptHosts.quickAnswer("https://anchor.fm/s/abc/podcast/rss"))
        assertEquals(false, com.ascolta.app.net.TranscriptHosts.quickAnswer("https://feeds.soundcloud.com/users/1/sounds.rss"))
        assertEquals(null, com.ascolta.app.net.TranscriptHosts.quickAnswer("https://rss.listen.style/p/x/rss"))
        assertEquals(null, com.ascolta.app.net.TranscriptHosts.quickAnswer("not a url"))
    }
}
