package de.jenz.nahtlos.core.db

import de.jenz.nahtlos.feed.EpisodeKeys
import de.jenz.nahtlos.model.AlternateSource
import de.jenz.nahtlos.model.Chapter
import de.jenz.nahtlos.model.EpisodeProgress
import de.jenz.nahtlos.model.FundingLink
import de.jenz.nahtlos.model.ParsedEpisode
import de.jenz.nahtlos.model.ParsedFeed
import de.jenz.nahtlos.model.Person
import de.jenz.nahtlos.model.PodLicense
import de.jenz.nahtlos.model.PodLocation
import de.jenz.nahtlos.model.PodTxt
import de.jenz.nahtlos.model.RemoteItem
import de.jenz.nahtlos.model.Trailer
import de.jenz.nahtlos.model.ProgressStatus
import de.jenz.nahtlos.model.Soundbite
import de.jenz.nahtlos.model.Subscription
import de.jenz.nahtlos.model.TranscriptCue
import de.jenz.nahtlos.model.TranscriptLink
import de.jenz.nahtlos.model.ValueRecipient
import de.jenz.nahtlos.model.ValueTimeSplit
import de.jenz.nahtlos.model.NahtlosJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

private val json = NahtlosJson.json

fun ParsedFeed.toPodcast(
    feedUrl: String,
    needsSync: Boolean,
    fetchedAt: Long,
    existingId: String? = null,
): PodcastEntity {
    val normalized = EpisodeKeys.normalizeFeedUrl(feedUrl)
    return PodcastEntity(
        id = existingId ?: EpisodeKeys.podcastId(podcastGuid, normalized),
        feedUrl = normalized,
        title = title,
        description = description,
        imageUrl = imageUrl,
        podcastGuid = podcastGuid,
        author = author ?: persons.firstOrNull()?.name,
        link = link,
        personsJson = json.encodeToString(persons),
        lastFetchedAt = fetchedAt,
        needsSync = needsSync,
        fundingJson = json.encodeToString(funding),
        valueJson = json.encodeToString(value),
        locked = locked,
        extrasJson = json.encodeToString(
            PodcastExtras(
                location = location,
                license = license,
                txt = txt,
                trailers = trailers,
                podroll = podroll,
                valueMethod = valueMethod,
                valueSuggested = valueSuggested,
            ),
        ),
    )
}

@Serializable
data class EpisodeExtras(
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

@Serializable
data class PodcastExtras(
    val location: PodLocation? = null,
    val license: PodLicense? = null,
    val txt: List<PodTxt> = emptyList(),
    val trailers: List<Trailer> = emptyList(),
    val podroll: List<RemoteItem> = emptyList(),
    val valueMethod: String? = null,
    val valueSuggested: String? = null,
)

@Serializable
data class PodcastPlayback(
    val speed: Float? = null,
    val skipSilence: Boolean? = null,
    val boost: Boolean? = null,
    val introSec: Int = 0,
    val outroSec: Int = 0,
)

fun ParsedEpisode.toEntity(podcast: PodcastEntity): EpisodeEntity = EpisodeEntity(
    episodeKey = EpisodeKeys.episodeKey(podcast.podcastGuid, podcast.feedUrl, guid, enclosureUrl),
    podcastId = podcast.id,
    title = title,
    description = description,
    enclosureUrl = enclosureUrl,
    mimeType = mimeType,
    guid = guid,
    durationMs = durationMs,
    publishedAt = publishedAtEpochMs,
    imageUrl = imageUrl ?: podcast.imageUrl,
    chaptersJson = json.encodeToString(chapters),
    chaptersUrl = chaptersUrl,
    transcriptsJson = json.encodeToString(transcripts),
    personsJson = json.encodeToString(persons),
    extrasJson = encodeExtras(
        EpisodeExtras(
            descriptionHtml = descriptionHtml,
            season = season,
            episodeNumber = episodeNumber,
            live = live,
            liveStatus = liveStatus,
            soundbites = soundbites,
            value = value,
            valueMethod = valueMethod,
            valueSuggested = valueSuggested,
            valueTimeSplits = valueTimeSplits,
            alternates = alternates,
            location = location,
            license = license,
            txt = txt,
        ),
    ),
)

fun encodeExtras(extras: EpisodeExtras): String = try {
    json.encodeToString(extras)
} catch (error: Throwable) {
    android.util.Log.w("Nahtlos", "EpisodeExtras nicht schreibbar", error)
    "{}"
}

fun EpisodeEntity.extras(): EpisodeExtras = try {
    if (extrasJson.isBlank()) EpisodeExtras() else json.decodeFromString<EpisodeExtras>(extrasJson)
} catch (error: Throwable) {
    android.util.Log.w("Nahtlos", "EpisodeExtras für $episodeKey nicht lesbar", error)
    EpisodeExtras()
}

fun PodcastEntity.extras(): PodcastExtras =
    runCatching { json.decodeFromString<PodcastExtras>(extrasJson) }.getOrDefault(PodcastExtras())

fun PodcastEntity.funding(): List<FundingLink> =
    runCatching { json.decodeFromString<List<FundingLink>>(fundingJson) }.getOrDefault(emptyList())

fun PodcastEntity.valueRecipients(): List<ValueRecipient> =
    runCatching { json.decodeFromString<List<ValueRecipient>>(valueJson) }.getOrDefault(emptyList())

fun PodcastEntity.playback(): PodcastPlayback =
    runCatching { json.decodeFromString<PodcastPlayback>(playbackJson) }.getOrDefault(PodcastPlayback())

fun EpisodeEntity.cachedCues(): List<TranscriptCue> =
    if (transcriptCache.isBlank()) emptyList()
    else runCatching { json.decodeFromString<List<TranscriptCue>>(transcriptCache) }.getOrDefault(emptyList())

fun EpisodeEntity.chapters(): List<Chapter> =
    runCatching { json.decodeFromString<List<Chapter>>(chaptersJson) }.getOrDefault(emptyList())

fun EpisodeEntity.transcripts(): List<TranscriptLink> =
    runCatching { json.decodeFromString<List<TranscriptLink>>(transcriptsJson) }.getOrDefault(emptyList())

fun EpisodeEntity.persons(): List<Person> =
    runCatching { json.decodeFromString<List<Person>>(personsJson) }.getOrDefault(emptyList())

fun PodcastEntity.persons(): List<Person> =
    runCatching { json.decodeFromString<List<Person>>(personsJson) }.getOrDefault(emptyList())

fun PodcastEntity.toSubscription(): Subscription = Subscription(
    feedUrl = feedUrl,
    title = title,
    podcastGuid = podcastGuid,
    imageUrl = imageUrl,
    updatedAt = lastFetchedAt,
)

fun ProgressEntity.toModel(): EpisodeProgress = EpisodeProgress(
    episodeKey = episodeKey,
    positionMs = positionMs,
    durationMs = durationMs,
    updatedAt = updatedAt,
    deviceId = deviceId,
    status = runCatching { ProgressStatus.valueOf(status) }.getOrDefault(ProgressStatus.PAUSED),
    serverReceivedAt = serverReceivedAt,
)

fun EpisodeProgress.toProgressEntity(): ProgressEntity = ProgressEntity(
    episodeKey = episodeKey,
    positionMs = positionMs,
    durationMs = durationMs,
    updatedAt = updatedAt,
    deviceId = deviceId,
    status = status.name,
    serverReceivedAt = serverReceivedAt,
)

fun EpisodeProgress.toOutbox(podcastGuid: String?, episodeGuid: String?, feedUrl: String?): OutboxEntity =
    OutboxEntity(
        episodeKey = episodeKey,
        positionMs = positionMs,
        durationMs = durationMs,
        updatedAt = updatedAt,
        deviceId = deviceId,
        status = status.name,
        podcastGuid = podcastGuid,
        episodeGuid = episodeGuid,
        feedUrl = feedUrl,
    )

fun OutboxEntity.toModel(): EpisodeProgress = EpisodeProgress(
    episodeKey = episodeKey,
    podcastGuid = podcastGuid,
    episodeGuid = episodeGuid,
    feedUrl = feedUrl,
    positionMs = positionMs,
    durationMs = durationMs,
    updatedAt = updatedAt,
    deviceId = deviceId,
    status = runCatching { ProgressStatus.valueOf(status) }.getOrDefault(ProgressStatus.PAUSED),
)
