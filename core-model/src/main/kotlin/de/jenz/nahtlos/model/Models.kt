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

@Serializable
data class FundingLink(val url: String, val title: String)

@Serializable
data class ValueRecipient(
    val name: String? = null,
    val type: String? = null,
    val address: String? = null,
    val split: Int? = null,
    val fee: Boolean = false,
    val customKey: Long? = null,
    val customValue: String? = null,
)

/**
 * Zeitfenster, in dem andere Empfänger gelten.
 * [remotePercentage] ist der Anteil am Boost, der an [recipients] geht.
 * Der Rest bleibt beim normalen Value-Block der Folge oder des Kanals.
 */
@Serializable
data class ValueTimeSplit(
    val startMs: Long,
    val durationMs: Long,
    val remotePercentage: Int = 100,
    val recipients: List<ValueRecipient> = emptyList(),
)

@Serializable
data class Soundbite(val startMs: Long, val durationMs: Long? = null, val title: String = "")

@Serializable
data class RemoteItem(
    val feedGuid: String? = null,
    val feedUrl: String? = null,
    val itemGuid: String? = null,
    val title: String? = null,
    val medium: String? = null,
)

@Serializable
data class AlternateSource(val url: String, val type: String? = null, val bitrate: Int = 0)

@Serializable
data class Trailer(val url: String, val title: String = "", val season: String? = null)

@Serializable
data class PodLocation(val name: String = "", val geo: String? = null, val osm: String? = null)

@Serializable
data class PodLicense(val url: String = "", val name: String = "")

@Serializable
data class PodTxt(val purpose: String? = null, val value: String = "")

@Serializable
data class TranscriptCue(val startMs: Long, val endMs: Long, val text: String)

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
    val descriptionHtml: String = "",
    val season: Int? = null,
    val episodeNumber: String? = null,
    val live: Boolean = false,
    val liveStatus: String? = null,
    val soundbites: List<Soundbite> = emptyList(),
    val value: List<ValueRecipient> = emptyList(),
    val valueMethod: String? = null,
    val valueSuggested: String? = null,
    val valueTimeSplits: List<ValueTimeSplit> = emptyList(),
    val alternates: List<AlternateSource> = emptyList(),
    val location: PodLocation? = null,
    val license: PodLicense? = null,
    val txt: List<PodTxt> = emptyList(),
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
    val funding: List<FundingLink> = emptyList(),
    val locked: Boolean = false,
    val value: List<ValueRecipient> = emptyList(),
    val valueMethod: String? = null,
    val valueSuggested: String? = null,
    val location: PodLocation? = null,
    val license: PodLicense? = null,
    val txt: List<PodTxt> = emptyList(),
    val trailers: List<Trailer> = emptyList(),
    val podroll: List<RemoteItem> = emptyList(),
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

@Serializable
data class WalletOpenRequest(val sessionId: String)

@Serializable
data class WalletOpenResponse(val sessionId: String, val expiresIn: Int)

@Serializable
data class WalletCipherRequest(val ciphertext: String)

@Serializable
data class NostrIdentityBody(
    val pubkey: String,
    val name: String = "",
    val picture: String? = null,
    val updatedAt: Long = 0,
)

@Serializable
data class NostrSignRequest(
    val id: String,
    val kind: Int,
    val content: String,
    val tags: List<List<String>> = emptyList(),
    val createdAt: Long,
)

@Serializable
data class NostrSignedBody(val event: String)

@Serializable
data class NostrSignStatus(
    val id: String,
    val status: String,
    val kind: Int = 0,
    val content: String = "",
    val tags: List<List<String>> = emptyList(),
    val createdAt: Long = 0,
    val expiresIn: Int = 0,
    val event: String? = null,
    val error: String? = null,
) {
    companion object {
        const val PENDING = "pending"
        const val SIGNED = "signed"
        const val EXPIRED = "expired"
        const val FAILED = "failed"
    }
}

@Serializable
data class WalletPollResponse(
    val status: String,
    val expiresIn: Int? = null,
    val ciphertext: String? = null,
    val senderName: String? = null,
) {
    companion object {
        const val PENDING = "pending"
        const val READY = "ready"
        const val EXPIRED = "expired"
        const val GONE = "gone"
    }
}
