package de.jenz.nahtlos.core.db

import de.jenz.nahtlos.feed.EpisodeKeys
import de.jenz.nahtlos.model.Chapter
import de.jenz.nahtlos.model.EpisodeProgress
import de.jenz.nahtlos.model.FundingLink
import de.jenz.nahtlos.model.ParsedEpisode
import de.jenz.nahtlos.model.ParsedFeed
import de.jenz.nahtlos.model.Person
import de.jenz.nahtlos.model.ProgressStatus
import de.jenz.nahtlos.model.Soundbite
import de.jenz.nahtlos.model.Subscription
import de.jenz.nahtlos.model.TranscriptCue
import de.jenz.nahtlos.model.TranscriptLink
import de.jenz.nahtlos.model.ValueRecipient
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
    )
}

@Serializable
data class EpisodeExtras(
    val descriptionHtml: String = "",
    val season: Int? = null,
    val episodeNumber: String? = null,
    val live: Boolean = false,
    val soundbites: List<Soundbite> = emptyList(),
    val value: List<ValueRecipient> = emptyList(),
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
    extrasJson = json.encodeToString(
        EpisodeExtras(
            descriptionHtml = descriptionHtml,
            season = season,
            episodeNumber = episodeNumber,
            live = live,
            soundbites = soundbites,
            value = value,
        ),
    ),
)

fun EpisodeEntity.extras(): EpisodeExtras =
    runCatching { json.decodeFromString<EpisodeExtras>(extrasJson) }.getOrDefault(EpisodeExtras())

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
