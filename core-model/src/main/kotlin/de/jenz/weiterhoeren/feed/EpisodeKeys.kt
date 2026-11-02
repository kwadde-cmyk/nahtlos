package de.jenz.weiterhoeren.feed

import java.security.MessageDigest

/**
 * Episode identity is `podcast:guid` + item GUID when the feed provides them.
 * Enclosure URLs are only a fallback — CDN and tracking parameters change.
 */
object EpisodeKeys {
    fun normalizeFeedUrl(url: String): String = url.trim().trimEnd('/')

    fun podcastId(podcastGuid: String?, feedUrl: String): String {
        val guid = podcastGuid?.trim().orEmpty()
        if (guid.isNotEmpty()) return guid
        return "feed:" + sha256(normalizeFeedUrl(feedUrl)).take(32)
    }

    fun episodeKey(
        podcastGuid: String?,
        feedUrl: String,
        episodeGuid: String?,
        enclosureUrl: String,
    ): String {
        val podcastPart = podcastGuid?.trim().orEmpty().ifEmpty {
            "feed:" + sha256(normalizeFeedUrl(feedUrl)).take(32)
        }
        val episodePart = episodeGuid?.trim().orEmpty().ifEmpty {
            "url:" + sha256(enclosureUrl.trim()).take(32)
        }
        return "$podcastPart|$episodePart"
    }

    fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
