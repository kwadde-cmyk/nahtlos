package de.jenz.nahtlos.feed

import de.jenz.nahtlos.model.Chapter
import de.jenz.nahtlos.model.NahtlosJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Podcasting 2.0 chapters file (`application/json+chapters`).
 * `startTime` is seconds.
 */
class ChapterJsonParser {
    fun parse(json: String): List<Chapter> {
        val root = NahtlosJson.json.parseToJsonElement(json).jsonObject
        val chapters = root["chapters"] as? JsonArray ?: return emptyList()
        return chapters.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val start = obj.double("startTime") ?: return@mapNotNull null
            val title = obj.string("title")?.takeIf { it.isNotBlank() } ?: "Kapitel"
            Chapter(
                startMs = (start * 1000.0).toLong().coerceAtLeast(0),
                title = title,
                url = obj.string("url"),
                imageUrl = obj.string("img"),
            )
        }.sortedBy { it.startMs }
    }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null }

    private fun JsonObject.double(name: String): Double? {
        val primitive = this[name] as? JsonPrimitive ?: return null
        return primitive.doubleOrNull ?: primitive.contentOrNull?.toDoubleOrNull()
    }
}
