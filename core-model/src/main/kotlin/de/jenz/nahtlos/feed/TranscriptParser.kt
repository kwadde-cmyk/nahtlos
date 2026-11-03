package de.jenz.nahtlos.feed

import de.jenz.nahtlos.model.TranscriptCue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import de.jenz.nahtlos.model.NahtlosJson

/** SRT, WebVTT and Podcasting 2.0 JSON transcripts. HTML falls back to one block without times. */
object TranscriptParser {
    fun parse(body: String, type: String? = null): List<TranscriptCue> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return emptyList()
        val hint = type?.lowercase().orEmpty()
        return when {
            hint.contains("json") || trimmed.startsWith("{") || trimmed.startsWith("[") -> parseJson(trimmed)
            hint.contains("html") || trimmed.startsWith("<") -> listOf(TranscriptCue(0, Long.MAX_VALUE, stripTags(trimmed)))
            else -> parseTimed(trimmed)
        }
    }

    private fun parseJson(raw: String): List<TranscriptCue> {
        val root = runCatching { NahtlosJson.json.parseToJsonElement(raw) }.getOrNull() ?: return emptyList()
        val segments = when (root) {
            is JsonArray -> root
            else -> root.jsonObject["segments"] as? JsonArray ?: return emptyList()
        }
        return segments.mapNotNull { element ->
            val obj = element.jsonObject
            val start = obj["startTime"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val end = obj["endTime"]?.jsonPrimitive?.doubleOrNull ?: (start + 2.0)
            val text = obj["body"]?.jsonPrimitive?.contentOrNull ?: obj["text"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            TranscriptCue((start * 1000).toLong(), (end * 1000).toLong(), text.trim())
        }
    }

    private fun parseTimed(raw: String): List<TranscriptCue> {
        val cues = mutableListOf<TranscriptCue>()
        val lines = raw.replace("\r\n", "\n").lines()
        var index = 0
        while (index < lines.size) {
            val line = lines[index].trim()
            val times = timeRange(line)
            if (times != null) {
                index++
                val text = StringBuilder()
                while (index < lines.size && lines[index].isNotBlank()) {
                    if (text.isNotEmpty()) text.append('\n')
                    text.append(lines[index].trim())
                    index++
                }
                val spoken = stripTags(text.toString())
                if (spoken.isNotBlank()) cues += TranscriptCue(times.first, times.second, spoken)
            }
            index++
        }
        return cues
    }

    private fun timeRange(line: String): Pair<Long, Long>? {
        val cleaned = line.substringBefore(" ").let { if (it.contains("-->")) line else line }
        val parts = cleaned.split("-->")
        if (parts.size != 2) return null
        val start = clock(parts[0]) ?: return null
        val end = clock(parts[1].trim().substringBefore(" ")) ?: return null
        return start to end
    }

    private fun clock(raw: String): Long? {
        val text = raw.trim().replace(',', '.')
        val bits = text.split(":")
        if (bits.size < 2) return null
        return try {
            val seconds = when (bits.size) {
                2 -> bits[0].toDouble() * 60 + bits[1].toDouble()
                else -> bits[0].toDouble() * 3600 + bits[1].toDouble() * 60 + bits[2].toDouble()
            }
            (seconds * 1000).toLong()
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun stripTags(raw: String): String =
        raw.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
}
