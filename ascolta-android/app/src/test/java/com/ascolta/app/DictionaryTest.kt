package com.ascolta.app

import com.ascolta.app.data.Dictionary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class DictionaryTest {
    @Test
    fun linesAreFoundBySearchingTheSortedFile() {
        // Sorted by the bytes of the second field, as tools/build_dict.py writes them.
        val words = listOf("գիրք", "եղանակ", "լավ", "մարդ", "քաղաք", "Հայաստան").sortedBy { it.toByteArray().joinToString(",") { b -> "%03d".format(b.toInt() and 0xff) } }
        val file = File.createTempFile("dict", ".tsv")
        file.writeText(words.joinToString("") { "E\t$it\tnoun\tmeaning of $it\t\n" })
        for (w in words) assertEquals("E\t$w\tnoun\tmeaning of $w\t", Dictionary.find(file, w))
        assertNull(Dictionary.find(file, "գրքեր"))
        assertNull(Dictionary.find(file, "ա"))
        file.delete()
    }
}
