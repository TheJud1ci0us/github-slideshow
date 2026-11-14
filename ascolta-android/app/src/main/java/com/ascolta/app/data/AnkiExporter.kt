package com.ascolta.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Legacy .apkg format supported by Anki Desktop and AnkiDroid, including media. */
object AnkiExporter {
    const val MODEL_ID = 1700420001L
    fun html(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;").replace("\u001f", " ").replace("\r\n", "\n").replace("\n", "<br>")

    fun guid(language: String, word: String, sentence: String, isSentence: Boolean): String =
        MessageDigest.getInstance("SHA-256").digest("$language\u001f$word\u001f$sentence\u001f$isSentence".toByteArray(Charsets.UTF_8))
            .take(10).joinToString("") { "%02x".format(it) }

    fun write(context: Context, language: String, cards: List<VocabEntity>): File {
        require(cards.isNotEmpty()) { "Save a word or sentence before exporting" }
        val root = File(context.cacheDir, "export").apply { mkdirs() }
        val scratch = File(root, "anki-${System.nanoTime()}").apply { mkdirs() }
        val collection = File(scratch, "collection.anki2")
        val timestamp = System.currentTimeMillis()
        val output = File(root, "lingopod-$language-$timestamp.apkg")
        val media = JSONObject()
        val clips = mutableListOf<File>()
        try {
            context.assets.open("anki-template.anki2").use { input -> collection.outputStream().use { input.copyTo(it) } }
            SQLiteDatabase.openDatabase(collection.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.beginTransaction()
                try {
                    val deckId = 1700500000L + (language.hashCode().toLong() and 0x7fffffff)
                    db.rawQuery("SELECT decks, models FROM col", null).use { cursor ->
                        check(cursor.moveToFirst())
                        val decks = JSONObject(cursor.getString(0))
                        val key = decks.keys().asSequence().first { it != "1" }
                        val deck = decks.getJSONObject(key).put("id", deckId).put("name", "LingoPod::$language").put("mod", timestamp / 1000)
                        decks.remove(key); decks.put(deckId.toString(), deck)
                        val models = JSONObject(cursor.getString(1))
                        models.getJSONObject(MODEL_ID.toString()).put("did", deckId)
                        db.execSQL("UPDATE col SET decks=?, models=?, mod=?", arrayOf<Any>(decks.toString(), models.toString(), timestamp))
                    }
                    // Identical saved entries merge into one note, including on repeated imports.
                    cards.distinctBy { guid(it.language, it.word, it.sentence.orEmpty(), it.isSentence) }.forEachIndexed { index, v ->
                        val id = timestamp + index
                        val guid = guid(v.language, v.word, v.sentence.orEmpty(), v.isSentence)
                        val clip = v.audioPath?.let(::File)?.takeIf { it.isFile }
                        val sound = clip?.let {
                            val extension = it.extension.lowercase().takeIf { ext -> ext.matches(Regex("[a-z0-9]{1,8}")) } ?: "wav"
                            val name = "lingopod-$guid.$extension"
                            media.put(clips.size.toString(), name); clips += it
                            "[sound:$name]"
                        }.orEmpty()
                        val front = html(v.word)
                        val fields = listOf(front, html(v.translation), html(v.sentence.orEmpty()), html(v.sentenceTranslation.orEmpty()), sound).joinToString("\u001f")
                        val checksum = MessageDigest.getInstance("SHA-1").digest(v.word.toByteArray(Charsets.UTF_8))
                            .take(4).joinToString("") { "%02x".format(it) }.toLong(16)
                        db.execSQL("INSERT INTO notes VALUES (?,?,?,?,?,?,?,?,?,?,?)", arrayOf<Any>(id, guid, MODEL_ID, timestamp / 1000, -1, " lingopod ", fields, v.word, checksum, 0, ""))
                        db.execSQL("INSERT INTO cards VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", arrayOf<Any>(id, id, deckId, 0, timestamp / 1000, -1, 0, 0, index + 1, 0, 2500, 0, 0, 0, 0, 0, 0, ""))
                    }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
            ZipOutputStream(output.outputStream().buffered()).use { zip ->
                fun add(name: String, file: File) {
                    zip.putNextEntry(ZipEntry(name)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
                add("collection.anki2", collection)
                clips.forEachIndexed { index, file -> add(index.toString(), file) }
                zip.putNextEntry(ZipEntry("media")); zip.write(media.toString().toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
            return output
        } catch (error: Throwable) {
            output.delete(); throw error
        } finally { scratch.deleteRecursively() }
    }
}
