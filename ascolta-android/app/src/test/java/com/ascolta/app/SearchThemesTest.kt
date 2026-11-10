package com.ascolta.app

import com.ascolta.app.data.Recommendations
import com.ascolta.app.net.Artwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchThemesTest {
    @Test
    fun searchesNamingAThemeAreRecognised() {
        assertEquals(1489, Recommendations.genreFor("news podcast"))
        assertEquals(1489, Recommendations.genreFor("हिंदी समाचार"))
        assertEquals(1533, Recommendations.genreFor("podcast di scienza"))
        assertEquals(1488, Recommendations.genreFor("true crime"))
        assertEquals(1545, Recommendations.genreFor("Calcio", mapOf(1545 to "Calcio")))
        assertNull(Recommendations.genreFor("Ranveer"))
        assertNull(Recommendations.genreFor("newsletter tips")) // a whole word, not part of one
    }

    @Test
    fun appleCoversAreAskedForSmall() {
        val big = "https://is1-ssl.mzstatic.com/image/thumb/Podcasts126/v4/aa/bb/cc/abc.jpg/600x600bb.jpg"
        assertEquals("https://is1-ssl.mzstatic.com/image/thumb/Podcasts126/v4/aa/bb/cc/abc.jpg/200x200bb.jpg", Artwork.sized(big, 154))
        assertTrue(Artwork.resizable(big))
        val feed = "https://example.com/cover-3000.jpg"
        assertEquals(feed, Artwork.sized(feed, 154)) // other sites can't be resized
        assertFalse(Artwork.resizable(feed))
    }
}
