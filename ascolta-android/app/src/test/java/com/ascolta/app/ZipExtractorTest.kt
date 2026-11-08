package com.ascolta.app

import com.ascolta.app.whisper.ZipExtractor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ZipExtractorTest {
    @Test
    fun extractsZip64EntriesAndStripsTopFolder() {
        val zip = File(javaClass.classLoader!!.getResource("zip64-sample.zip")!!.toURI())
        val out = kotlin.io.path.createTempDirectory("unzip").toFile()
        ZipExtractor.extract(zip, out, stripTopFolder = true)
        val data = File(out, "encoder_onnx/encoder.data").readBytes()
        assertEquals(256 * 4000, data.size)
        assertArrayEquals(ByteArray(256) { it.toByte() }, data.copyOfRange(512, 768))
        assertEquals("{\"ok\": true}", File(out, "metadata.json").readText())
    }
}
