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
        assertEquals(SpeechModel.PARAKEET, SpeechModel.recommended("it"))
    }

    @Test
    fun backupSearchesUseTheLanguagesOwnName() {
        val names = Recommendations.searchNames("hi")
        assertTrue("हिंदी" in names)
        assertTrue("Hindi" in names)
        assertTrue(LanguageCheck.mentionsLanguage("Bhagavad Gita (Hindi)", "hi"))
        assertFalse(LanguageCheck.mentionsLanguage("Hindi film music charts explained", "hi"))
    }
}
