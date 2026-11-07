package com.ascolta.app

import com.ascolta.app.data.Segment
import com.ascolta.app.data.indexAt
import com.ascolta.app.net.FeedTranscriptParser
import com.ascolta.app.net.OpmlParser
import com.ascolta.app.net.RssParser
import com.ascolta.app.ui.wordAt
import com.ascolta.app.whisper.ChunkAccumulator
import com.ascolta.app.whisper.LinearResampler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsersTest {

    private val rss = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"
             xmlns:podcast="https://podcastindex.org/namespace/1.0">
          <channel>
            <title>Italiano Semplice</title>
            <language>it-IT</language>
            <itunes:author>Gianni</itunes:author>
            <itunes:image href="https://example.com/cover.jpg"/>
            <description><![CDATA[Impara <b>l'italiano</b>]]></description>
            <item>
              <title>Episodio 2: Il caffè</title>
              <guid isPermaLink="false">ep-2</guid>
              <pubDate>Tue, 03 Jun 2025 08:00:00 +0000</pubDate>
              <itunes:duration>1:02:03</itunes:duration>
              <enclosure url="https://example.com/ep2.mp3" type="audio/mpeg" length="123"/>
              <podcast:transcript url="https://example.com/ep2.html" type="text/html"/>
              <podcast:transcript url="https://example.com/ep2.vtt" type="text/vtt"/>
            </item>
            <item>
              <title>Episodio 1</title>
              <pubDate>Mon, 2 Jun 2025 08:00:00 GMT</pubDate>
              <itunes:duration>754</itunes:duration>
              <enclosure url="https://example.com/ep1.mp3" type="audio/mpeg"/>
            </item>
            <item>
              <title>No audio, skipped</title>
            </item>
          </channel>
        </rss>
    """.trimIndent()

    @Test
    fun parsesRssFeed() {
        val feed = RssParser.parse(rss.byteInputStream())
        assertEquals("Italiano Semplice", feed.title)
        assertEquals("Gianni", feed.author)
        assertEquals("it", feed.language)
        assertEquals("https://example.com/cover.jpg", feed.imageUrl)
        assertEquals(2, feed.episodes.size)

        val ep2 = feed.episodes[0]
        assertEquals("Episodio 2: Il caffè", ep2.title)
        assertEquals("ep-2", ep2.guid)
        assertEquals(3723L, ep2.durationSec)
        assertEquals("https://example.com/ep2.vtt", ep2.transcriptUrl) // VTT preferred over HTML
        assertTrue(ep2.pubDate > 0)

        val ep1 = feed.episodes[1]
        assertEquals("https://example.com/ep1.mp3", ep1.guid) // falls back to the audio URL
        assertEquals(754L, ep1.durationSec)
        assertNull(ep1.transcriptUrl)
        assertTrue(ep1.pubDate > 0)
    }

    @Test
    fun parsesOpml() {
        val opml = """
            <opml version="1.0"><body>
              <outline text="feeds">
                <outline type="rss" text="A" xmlUrl="https://a.example/feed"/>
                <outline type="rss" text="B" xmlUrl="https://b.example/rss"/>
              </outline>
            </body></opml>
        """.trimIndent()
        assertEquals(listOf("https://a.example/feed", "https://b.example/rss"), OpmlParser.parse(opml.byteInputStream()))
    }

    @Test
    fun parsesVttAndMergesSentences() {
        val vtt = """
            WEBVTT

            00:00:01.000 --> 00:00:02.500
            <v Anna>Ciao a tutti,

            00:00:02.500 --> 00:00:04.000
            benvenuti al podcast.

            1:05.000 --> 1:07.250
            Oggi parliamo di cibo!
        """.trimIndent()
        val segments = FeedTranscriptParser.parse(vtt, "text/vtt")
        assertEquals(2, segments.size)
        assertEquals(Segment(1000, 4000, "Ciao a tutti, benvenuti al podcast."), segments[0])
        assertEquals(Segment(65000, 67250, "Oggi parliamo di cibo!"), segments[1])
    }

    @Test
    fun parsesSrt() {
        val srt = "1\r\n00:00:00,500 --> 00:00:01,750\r\nBuongiorno.\r\n\r\n2\r\n00:00:02,000 --> 00:00:03,000\r\nCome stai?\r\n"
        val segments = FeedTranscriptParser.parse(srt, "application/x-subrip")
        assertEquals(listOf(Segment(500, 1750, "Buongiorno."), Segment(2000, 3000, "Come stai?")), segments)
    }

    @Test
    fun parsesPodcastJsonWordLevel() {
        val json = """
            {"version":"1.0.0","segments":[
              {"startTime":0.0,"endTime":0.4,"body":"Allora"},
              {"startTime":0.4,"endTime":0.9,"body":"andiamo."},
              {"startTime":1.2,"endTime":1.6,"body":"Pronti?"}
            ]}
        """.trimIndent()
        val segments = FeedTranscriptParser.parse(json, "application/json")
        assertEquals(listOf(Segment(0, 900, "Allora andiamo."), Segment(1200, 1600, "Pronti?")), segments)
    }

    @Test
    fun findsSegmentAtPosition() {
        val segments = listOf(Segment(1000, 2000, "a"), Segment(2000, 3000, "b"), Segment(5000, 6000, "c"))
        assertEquals(-1, segments.indexAt(500))
        assertEquals(0, segments.indexAt(1500))
        assertEquals(1, segments.indexAt(2000))
        assertEquals(1, segments.indexAt(4000))
        assertEquals(2, segments.indexAt(9000))
    }

    @Test
    fun picksTappedWord() {
        val text = "Dell'acqua fresca, per favore."
        assertEquals("Dell'acqua", wordAt(text, 3))
        assertEquals("fresca", wordAt(text, 11))
        assertEquals("fresca", wordAt(text, 17)) // on the comma right after the word
        assertEquals("favore", wordAt(text, text.length - 1))
        assertNull(wordAt("  ", 1))
    }

    @Test
    fun resamplesToTargetRate() {
        val resampler = LinearResampler(44_100, 16_000)
        var produced = 0
        val block = FloatArray(4410) { 0.5f }
        repeat(10) { produced += resampler.process(block, block.size).second } // 1 second of audio
        assertTrue("got $produced samples", produced in 15_990..16_010)
    }

    @Test
    fun accumulatesChunksAndSkipsResumedAudio() {
        val acc = ChunkAccumulator(chunkSamples = 4, skipSamples = 3)
        val input = FloatArray(12) { it.toFloat() }
        acc.add(input.copyOfRange(0, 5), 5)
        acc.add(input.copyOfRange(5, 12), 7)
        val first = acc.poll()!!
        assertEquals(listOf(3f, 4f, 5f, 6f), first.first.toList())
        assertEquals(3L, first.second)
        val second = acc.poll()!!
        assertEquals(7L, second.second)
        assertNull(acc.poll())
        val rest = acc.flush()!!
        assertEquals(listOf(11f), rest.first.toList())
        assertEquals(11L, rest.second)
    }

    @Test
    fun shortFirstChunk() {
        val acc = ChunkAccumulator(chunkSamples = 5, skipSamples = 0, firstChunkSamples = 2)
        acc.add(FloatArray(9) { it.toFloat() }, 9)
        assertEquals(listOf(0f, 1f), acc.poll()!!.first.toList())
        val second = acc.poll()!!
        assertEquals(listOf(2f, 3f, 4f, 5f, 6f), second.first.toList())
        assertEquals(2L, second.second)
        assertEquals(listOf(7f, 8f), acc.flush()!!.first.toList())
    }
}
