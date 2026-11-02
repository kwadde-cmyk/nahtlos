package de.jenz.nahtlos.core.db

import de.jenz.nahtlos.feed.EpisodeKeys
import de.jenz.nahtlos.model.Chapter
import de.jenz.nahtlos.model.EpisodeProgress
import de.jenz.nahtlos.model.ParsedEpisode
import de.jenz.nahtlos.model.ParsedFeed
import de.jenz.nahtlos.model.Person
import de.jenz.nahtlos.model.ProgressStatus
import de.jenz.nahtlos.model.Subscription
import de.jenz.nahtlos.model.TranscriptLink
import de.jenz.nahtlos.model.NahtlosJson
import kotlinx.serialization.encodeToString

private val json = NahtlosJson.json

fun ParsedFeed.toPodcast(feedUrl: String, needsSync: Boolean, fetchedAt: Long): PodcastEntity {
    val normalized = EpisodeKeys.normalizeFeedUrl(feedUrl)
    return PodcastEntity(
        id = EpisodeKeys.podcastId(podcastGuid, normalized),
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
    )
}

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
)

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
