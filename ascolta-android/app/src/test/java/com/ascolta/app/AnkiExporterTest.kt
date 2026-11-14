package com.ascolta.app

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import com.ascolta.app.data.AnkiExporter
import com.ascolta.app.data.VocabEntity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AnkiExporterTest {
    private fun card(word: String, audio: String? = null) = VocabEntity(
        word = word, translation = "meaning", sentence = "<test> & context", sentenceTranslation = "translated",
        language = "fr", episodeId = null, episodeTitle = null, createdAt = 0, audioPath = audio,
    )

    @Test fun deckContainsNotesTemplatesAndMatchingAudioReferences() {
        val context = RuntimeEnvironment.getApplication()
        val clip = File(context.cacheDir, "example.wav").apply { writeBytes(byteArrayOf(82, 73, 70, 70)) }
        val a = card("l'été", clip.path)
        val cards = listOf(a, a.copy(id = 99), card("sans audio", "/missing.wav"))
        val deck = AnkiExporter.write(context, "fr", cards)
        assertTrue(deck.name.endsWith(".apkg"))
        val extracted = File(context.cacheDir, "check.anki2")
        ZipFile(deck).use { zip ->
            val media = JSONObject(zip.getInputStream(zip.getEntry("media")).bufferedReader().readText())
            assertEquals(1, media.length())
            val sound = media.getString("0")
            assertArrayEquals(clip.readBytes(), zip.getInputStream(zip.getEntry("0")).readBytes())
            zip.getInputStream(zip.getEntry("collection.anki2")).use { input -> extracted.outputStream().use { input.copyTo(it) } }
            SQLiteDatabase.openDatabase(extracted.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("PRAGMA integrity_check", null).use { c -> assertTrue(c.moveToFirst()); assertEquals("ok", c.getString(0)) }
                db.rawQuery("SELECT flds FROM notes ORDER BY id", null).use { c ->
                    assertEquals(2, c.count); assertTrue(c.moveToFirst())
                    val fields = c.getString(0).split('\u001f')
                    assertEquals(5, fields.size)
                    assertEquals("l&#39;été", fields[0]); assertEquals("&lt;test&gt; &amp; context", fields[2])
                    assertEquals("[sound:$sound]", fields[4]); assertTrue(c.moveToNext()); assertEquals("", c.getString(0).split('\u001f')[4])
                }
                db.rawQuery("SELECT COUNT(*) FROM cards WHERE type=0 AND queue=0", null).use { c -> c.moveToFirst(); assertEquals(2, c.getInt(0)) }
                db.rawQuery("SELECT models,decks FROM col", null).use { c ->
                    c.moveToFirst(); val model = JSONObject(c.getString(0)).getJSONObject(AnkiExporter.MODEL_ID.toString())
                    assertEquals(5, model.getJSONArray("flds").length())
                    assertTrue(model.getJSONArray("tmpls").getJSONObject(0).getString("afmt").contains("{{Audio}}"))
                    assertEquals("LingoPod::fr", JSONObject(c.getString(1)).getJSONObject(model.getLong("did").toString()).getString("name"))
                }
            }
        }
    }

    @Test fun repeatedExportsUseStableIdentityAndEscapeFieldSeparators() {
        val a = card("same")
        assertEquals(AnkiExporter.guid(a.language, a.word, a.sentence!!, false), AnkiExporter.guid(a.copy(id = 444).language, a.word, a.sentence!!, false))
        assertNotEquals(AnkiExporter.guid("fr", "same", "one", false), AnkiExporter.guid("fr", "same", "two", false))
        assertEquals("a b<br>🙂", AnkiExporter.html("a\u001fb\n🙂"))
    }

    @Test(expected = IllegalArgumentException::class) fun emptyDeckIsRejected() {
        AnkiExporter.write(RuntimeEnvironment.getApplication(), "fr", emptyList())
    }
}
