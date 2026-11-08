package com.ascolta.app

import com.ascolta.app.data.Japanese
import com.ascolta.app.data.JapaneseSentences
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.float
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real ReazonSpeech output (sherpa-onnx 1.12.40) for pieces of speech like those the app
 * makes, two of them holding two sentences said with a 0.2 s pause.
 */
class JapaneseSentencesTest {
    private val pieces = Json.parseToJsonElement(
        javaClass.classLoader!!.getResource("reazonspeech-pieces.json")!!.readText(),
    ).jsonObject

    private fun sentences(key: String): List<String> {
        runBlocking { Japanese.load() }
        val p = pieces[key]!!.jsonObject
        val tokens = p["tokens"]!!.jsonArray.map { it.jsonPrimitive.content }
        val times = p["times"]!!.jsonArray.map { (it.jsonPrimitive.float * 1000).toLong() }
        val out = JapaneseSentences.build(tokens, times, p["durationMs"]!!.jsonPrimitive.long)
        println("$key → ${out.joinToString(" | ") { it.text }}")
        return out.map { it.text }
    }

    @Test
    fun twoSentencesAreSeparated() {
        val a = sentences("t2+t3")
        assertEquals(2, a.size)
        assertTrue(a[0].endsWith("のです。"))
        assertTrue(a[1].startsWith("ヤンバルクイナ") && a[1].endsWith("だった。"))
        val b = sentences("t3+ja")
        assertEquals(2, b.size)
        assertTrue(b[0].endsWith("だった。"))
        assertTrue(b[1].startsWith("うちの中学"))
    }

    @Test
    fun longSentencesAreCutAtAClauseNotMidWord() {
        for (key in listOf("t1", "t4")) {
            val s = sentences(key)
            assertTrue("$key: $s", s.size >= 2)
            assertTrue("$key: $s", s.all { it.length <= JapaneseSentences.MAX_CHARS + 6 })
            assertTrue(s.last().endsWith("。"))
        }
    }

    @Test
    fun relativeClauseIsNotASentenceEnd() {
        // "持ち主とはぐれた傘" (the umbrella that got separated from its owner): the speaker pauses
        // after はぐれた, but it describes 傘, so no 。 there. The 14 s sentence is cut at the
        // clause 舞い、 instead.
        val s = sentences("t5")
        assertEquals(listOf("持ち主とはぐれた傘が風で舞い、", "看板もなぎ倒されてしまったようです。"), s)
    }
}
