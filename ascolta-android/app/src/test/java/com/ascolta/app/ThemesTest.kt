package com.ascolta.app

import com.ascolta.app.data.Recommendations
import com.ascolta.app.net.DirectoryResult
import org.junit.Assert.assertEquals
import org.junit.Test

/** Real shows and genres from Apple's Japanese store (November 2026). */
class ThemesTest {
    private fun show(title: String, genre: String, vararg ids: Int) =
        DirectoryResult(title, null, "https://feed/$title", null, genre, genreIds = ids.toList())

    private val science = 1533

    @Test
    fun theThemesOwnNameComesFromTheChart() {
        val chart = listOf(
            show("大人の科学教養ブルーバックス＋", "自然科学", 1534, 26, 1533),
            show("科学のラジオ", "科学", 1533, 26, 1304),
        )
        assertEquals("科学", Recommendations.genreName(chart, science))
    }

    @Test
    fun searchResultsOutsideTheThemeAreDropped() {
        // A search for ポッドキャスト ("podcast"), which Apple doesn't limit to Science.
        val found = listOf(
            show("ポッドキャスト研究室", "マーケティング", 1492, 26, 1321),
            show("ポッドキャスト（岡田康太）", "コメディ", 1303, 26),
            show("ヴォイニッチの科学書", "自然科学", 1534, 26, 1533),
        )
        assertEquals(listOf("ヴォイニッチの科学書"), Recommendations.inTheme(found, science).map { it.title })
    }

    @Test
    fun similarShowsComeFromTheShowsOwnThemesFirst() {
        assertEquals(listOf(1534, 1533), Recommendations.themesOf(show("x", "自然科学", 1534, 26, 1533)))
    }
}
