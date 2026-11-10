package com.ascolta.app

import com.ascolta.app.data.Recommendations
import com.ascolta.app.net.DirectoryResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real titles from Apple's search (November 2026). */
class LearnerShowsTest {
    private fun show(title: String, summary: String? = null, vararg genres: Int) =
        DirectoryResult(title, null, "https://feed/$title", null, null, summary, genreIds = genres.toList())

    private val italian = Recommendations.languageNames("it")

    @Test
    fun learnerShowsAreKept() {
        assertTrue(Recommendations.forLearners(show("Stories in Slow Italian - Learn Italian through stories"), italian))
        assertTrue(Recommendations.forLearners(show("Coffee Break Italian", null, 1498, 26, 1304), italian))
        assertTrue(Recommendations.forLearners(show("Easy Italian: Learn Italian with real conversations"), italian))
    }

    @Test
    fun otherShowsAreNot() {
        assertFalse(Recommendations.forLearners(show("Italian American Crime Stories", null, 1488, 26), italian))
        assertFalse(Recommendations.forLearners(show("Learn Spanish with Coffee Break"), italian)) // another language
    }

    @Test
    fun searchesInEnglishAndInTheLanguage() {
        val q = Recommendations.learnerQueries("it", "IT")
        assertTrue("slow Italian" to "US" in q)
        assertTrue("italiano facile" to "IT" in q)
    }
}
