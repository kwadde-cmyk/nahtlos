package de.jenz.nahtlos.model

import de.jenz.nahtlos.playlist.ListeningPlaylist
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Shared contract between the phone, the AAOS app and the sync server.
 * Client timestamps ([EpisodeProgress.updatedAt]) are the authority for conflicts.
 */
object NahtlosJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
}

@Serializable
enum class ProgressStatus {
    PLAYING,
    PAUSED,
    STOPPED,
    COMPLETED,
}

@Serializable
data class EpisodeProgress(
    val episodeKey: String,
    val positionMs: Long,
    val updatedAt: Long,
    val deviceId: String,
    val durationMs: Long = 0,
    val status: ProgressStatus = ProgressStatus.PAUSED,
    val podcastGuid: String? = null,
    val episodeGuid: String? = null,
    val feedUrl: String? = null,
    /** Set by the server when a write is stored. Clients leave this empty. */
    val serverReceivedAt: Long? = null,
) {
    fun normalized(): EpisodeProgress = copy(
        episodeKey = episodeKey.trim(),
        positionMs = positionMs.coerceAtLeast(0),
        durationMs = durationMs.coerceAtLeast(0),
        deviceId = deviceId.trim(),
        podcastGuid = podcastGuid?.trim()?.ifEmpty { null },
        episodeGuid = episodeGuid?.trim()?.ifEmpty { null },
        feedUrl = feedUrl?.trim()?.ifEmpty { null },
    )

    fun validationError(): String? {
        val value = normalized()
        if (value.episodeKey.isEmpty() || value.episodeKey.length > 512) return "episodeKey fehlt oder ist zu lang"
        if (value.deviceId.isEmpty() || value.deviceId.length > 128) return "deviceId fehlt oder ist zu lang"
        if (value.updatedAt <= 0L) return "updatedAt muss ein Client-Zeitstempel in Millisekunden sein"
        if (positionMs < 0 || durationMs < 0) return "positionMs und durationMs dürfen nicht negativ sein"
        return null
    }
}

@Serializable
data class ProgressWriteResponse(
    val accepted: Boolean,
    val stored: EpisodeProgress,
)

@Serializable
data class ProgressBatchRequest(
    val items: List<EpisodeProgress>,
)

@Serializable
data class ProgressBatchResponse(
    val results: List<ProgressWriteResponse>,
)

@Serializable
data class Subscription(
    val feedUrl: String,
    val title: String,
    val podcastGuid: String? = null,
    val imageUrl: String? = null,
    val updatedAt: Long = 0,
)

@Serializable
data class ErrorBody(
    val error: String,
)

@Serializable
data class SyncEvent(
    val type: String,
    val progress: EpisodeProgress? = null,
    val playlist: ListeningPlaylist? = null,
) {
    companion object {
        const val TYPE_PROGRESS = "progress"
        const val TYPE_PLAYLIST = "playlist"
    }
}

@Serializable
data class Chapter(
    val startMs: Long,
    val title: String,
    val url: String? = null,
    val imageUrl: String? = null,
)

@Serializable
data class TranscriptLink(
    val url: String,
    val type: String? = null,
    val language: String? = null,
)

@Serializable
data class Person(
    val name: String,
    val role: String? = null,
    val href: String? = null,
    val img: String? = null,
    val group: String? = null,
)

data class ParsedEpisode(
    val title: String,
    val description: String,
    val guid: String?,
    val enclosureUrl: String,
    val mimeType: String?,
    val durationMs: Long?,
    val publishedAtEpochMs: Long?,
    val imageUrl: String?,
    val chapters: List<Chapter>,
    val chaptersUrl: String?,
    val chaptersType: String?,
    val transcripts: List<TranscriptLink>,
    val persons: List<Person>,
)

data class ParsedFeed(
    val title: String,
    val description: String,
    val link: String?,
    val imageUrl: String?,
    val podcastGuid: String?,
    val author: String?,
    val persons: List<Person>,
    val episodes: List<ParsedEpisode>,
)

class FeedParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

@Serializable
data class PairStartResponse(
    val pairingId: String,
    val code: String,
    val expiresIn: Int,
)

@Serializable
data class PairApproveRequest(
    val code: String,
    val name: String,
)

@Serializable
data class PairApproveResponse(
    val deviceId: String,
    val name: String,
)

@Serializable
data class PairPollResponse(
    val status: String,
    val expiresIn: Int? = null,
    val token: String? = null,
    val deviceId: String? = null,
    val name: String? = null,
) {
    companion object {
        const val PENDING = "pending"
        const val APPROVED = "approved"
        const val EXPIRED = "expired"
        const val CONSUMED = "consumed"
    }
}

@Serializable
data class DeviceSummary(
    val id: String,
    val name: String,
    val createdAt: Long,
    val lastSeenAt: Long? = null,
    val revoked: Boolean = false,
)

@Serializable
data class BootstrapRequest(val name: String)

@Serializable
data class BootstrapResponse(
    val id: String,
    val name: String,
    val token: String,
)
