package de.jenz.nahtlos.core.feed

import de.jenz.nahtlos.core.net.Net
import de.jenz.nahtlos.model.NahtlosJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

data class PodcastHit(
    val title: String,
    val url: String,
    val author: String?,
    val image: String?,
)

/**
 * Podcast Index search. Without a key and secret every call returns an empty list
 * and does not touch the network.
 */
@Singleton
class PodcastIndexClient @Inject constructor(
    private val net: Net,
) {
    suspend fun search(key: String, secret: String, term: String): List<PodcastHit> {
        val query = term.trim()
        if (key.isBlank() || secret.isBlank() || query.length < 2) return emptyList()
        val url = "https://api.podcastindex.org/api/1.0/search/byterm?q=" + URLEncoder.encode(query, Charsets.UTF_8.name())
        return hits(key, secret, url, "feeds")
    }

    suspend fun byGuid(key: String, secret: String, guid: String): PodcastHit? {
        if (key.isBlank() || secret.isBlank() || guid.isBlank()) return null
        val url = "https://api.podcastindex.org/api/1.0/podcasts/byguid?guid=" + URLEncoder.encode(guid, Charsets.UTF_8.name())
        return hits(key, secret, url, "feed").firstOrNull()
    }

    private suspend fun hits(key: String, secret: String, url: String, field: String): List<PodcastHit> = try {
        val now = (System.currentTimeMillis() / 1000L).toString()
        val sha1 = sha1(key + secret + now)
        val body = net.getText(
            url,
            headers = mapOf(
                "User-Agent" to Net.USER_AGENT,
                "X-Auth-Date" to now,
                "X-Auth-Key" to key,
                "Authorization" to sha1,
            ),
            maxBytes = 1_000_000,
        )
        val root = NahtlosJson.json.parseToJsonElement(body).jsonObject
        val nodes = when (field) {
            "feed" -> listOfNotNull(root["feed"]?.jsonObject)
            else -> root[field]?.jsonArray?.mapNotNull { runCatching { it.jsonObject }.getOrNull() }.orEmpty()
        }
        nodes.mapNotNull { it.toHit() }
    } catch (_: Throwable) {
        emptyList()
    }

    private fun JsonObject.toHit(): PodcastHit? {
        val url = this["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (!url.startsWith("http")) return null
        return PodcastHit(
            title = this["title"]?.jsonPrimitive?.contentOrNull?.ifBlank { url } ?: url,
            url = url,
            author = this["author"]?.jsonPrimitive?.contentOrNull,
            image = this["image"]?.jsonPrimitive?.contentOrNull ?: this["artwork"]?.jsonPrimitive?.contentOrNull,
        )
    }

    private fun sha1(text: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
