package com.ascolta.app

import com.ascolta.app.data.AudioLength
import com.ascolta.app.data.DownloadCost
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class LengthAndSizeTest {
    @Test
    fun lengthComesFromTheBestSource() {
        assertEquals(3_000_000, AudioLength.best(playerMs = 3_000_000, feedSec = 2_000, fileMs = 1, transcriptEndMs = 1))
        assertEquals(2_000_000, AudioLength.best(playerMs = 0, feedSec = 2_000, fileMs = 5, transcriptEndMs = 5))
        assertEquals(1_234_000, AudioLength.best(playerMs = -1, feedSec = 0, fileMs = 1_234_000, transcriptEndMs = 5))
        assertEquals(900_000, AudioLength.best(playerMs = 0, feedSec = 0, fileMs = null, transcriptEndMs = 900_000))
        assertEquals(0, AudioLength.best(playerMs = 0, feedSec = 0, fileMs = null, transcriptEndMs = null))
    }

    @Test
    fun sizesReadNaturally() {
        assertEquals("652 MB", DownloadCost.formatSize(652_282_294, Locale.US))
        assertEquals("1.2 GB", DownloadCost.formatSize(1_234_000_000, Locale.US))
        assertEquals("7.2 MB", DownloadCost.formatSize(7_174_335, Locale.US))
        assertEquals("885 KB", DownloadCost.formatSize(885_098, Locale.US))
        assertEquals(48_000_000, DownloadCost.estimate(3_000)) // 50 minutes ≈ 48 MB
    }
}
