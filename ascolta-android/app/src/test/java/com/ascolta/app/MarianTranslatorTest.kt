package com.ascolta.app

import com.ascolta.app.translate.MarianTokenizer
import com.ascolta.app.translate.MarianTranslator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class MarianTranslatorTest {
    @Test
    fun piecesAreChosenByScoreAndTheLigatureIsSplit() {
        // A tiny vocabulary: whole words beat letter-by-letter splits.
        val pieces = listOf("</s>", "<unk>", "▁Երև", "ան", "▁Եր", "ևան", "▁", "Ե", "ր", "ե", "ւ", "ա", "ն", "<pad>")
        val scores = floatArrayOf(0f, 0f, -3f, -2f, -4f, -9f, -5f, -8f, -8f, -8f, -8f, -8f, -8f, 0f)
        val t = MarianTokenizer(pieces, scores, eos = 0, pad = 13)
        // "և" (one character) is written "եւ" after NFKC normalisation.
        val ids = t.encode("Երևան")
        assertEquals(0, ids.last())
        assertEquals("Երեւան", t.decode(ids))
    }

    @Test
    fun armenianToEnglishWithTheRealModel() {
        val dir = System.getProperty("opus.dir")?.let { File(it, "hy-en") }
        assumeTrue("set OPUS_DIR to run", dir?.isDirectory == true)
        MarianTranslator(dir!!).use { mt ->
            val en = mt.translate("Այսօր Երևանում եղանակը շատ լավ է։")
            println("hy→en: $en")
            assertTrue(en, en.contains("weather", ignoreCase = true) && en.contains("today", ignoreCase = true))
        }
    }
}
