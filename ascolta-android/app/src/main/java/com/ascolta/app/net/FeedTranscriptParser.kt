package com.ascolta.app.net

import com.ascolta.app.data.Segment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses transcripts that podcasts publish themselves (Podcasting 2.0
 * `<podcast:transcript>`): JSON, WebVTT and SRT. Cues are merged into sentences.
 */
object FeedTranscriptParser {

    fun parse(body: String, type: String?): List<Segment> {
        val t = (type ?: "").lowercase()
        val trimmed = body.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        val raw = when {
            "json" in t || trimmed.startsWith("{") -> parseJson(trimmed)
            "-->" in trimmed -> parseCues(trimmed)
            else -> emptyList()
        }
        return mergeIntoSentences(raw)
    }

    private fun parseJson(body: String): List<Segment> {
        val root = Json.parseToJsonElement(body).jsonObject
        val segments = root["segments"]?.jsonArray ?: return emptyList()
        return segments.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val start = obj["startTime"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val end = obj["endTime"]?.jsonPrimitive?.doubleOrNull ?: start
            val text = obj["body"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (text.isEmpty()) null else Segment((start * 1000).toLong(), (end * 1000).toLong(), text)
        }
    }

    private val TAG = Regex("<[^>]+>")

    /** WebVTT and SRT share the "start --> end" cue format. */
    private fun parseCues(body: String): List<Segment> {
        val lines = body.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val result = mutableListOf<Segment>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if ("-->" in line) {
                val (startText, endText) = line.split("-->", limit = 2)
                val start = parseTimestamp(startText)
                val end = parseTimestamp(endText.trim().substringBefore(' '))
                val text = StringBuilder()
                i++
                while (i < lines.size && lines[i].isNotBlank()) {
                    if (text.isNotEmpty()) text.append(' ')
                    text.append(lines[i].trim())
                    i++
                }
                val clean = TAG.replace(text.toString(), "").replace("&amp;", "&").trim()
                if (start != null && clean.isNotEmpty()) {
                    result += Segment(start, end ?: start, clean)
                }
            } else {
                i++
            }
        }
        return result
    }

    /** "01:02:03,456", "01:02:03.456" or "02:03.456" -> milliseconds. */
    fun parseTimestamp(text: String): Long? {
        val parts = text.trim().replace(',', '.').split(':')
        if (parts.size !in 2..3) return null
        return try {
            val seconds = parts.last().toDouble()
            val minutes = parts[parts.size - 2].toLong()
            val hours = if (parts.size == 3) parts[0].toLong() else 0
            ((hours * 3600 + minutes * 60) * 1000 + seconds * 1000).toLong()
        } catch (_: NumberFormatException) {
            null
        }
    }

    private val SENTENCE_END = Regex("""[.!?…]["»”’)]*$""")

    /** Joins short cues (often single words or caption lines) into sentence-sized segments. */
    fun mergeIntoSentences(cues: List<Segment>, maxChars: Int = 220): List<Segment> {
        val result = mutableListOf<Segment>()
        var start = 0L
        var end = 0L
        val text = StringBuilder()
        for (cue in cues) {
            if (text.isEmpty()) start = cue.startMs
            if (text.isNotEmpty()) text.append(' ')
            text.append(cue.text)
            end = cue.endMs
            if (SENTENCE_END.containsMatchIn(cue.text) || text.length >= maxChars) {
                result += Segment(start, end, text.toString())
                text.clear()
            }
        }
        if (text.isNotEmpty()) result += Segment(start, end, text.toString())
        return result
    }
}
