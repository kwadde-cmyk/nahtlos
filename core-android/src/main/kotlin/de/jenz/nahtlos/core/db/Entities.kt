package de.jenz.nahtlos.core.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "podcasts",
    indices = [Index(value = ["feedUrl"], unique = true)],
)
data class PodcastEntity(
    @PrimaryKey val id: String,
    val feedUrl: String,
    val title: String,
    val description: String,
    val imageUrl: String?,
    val podcastGuid: String?,
    val author: String?,
    val link: String?,
    val personsJson: String,
    val lastFetchedAt: Long,
    val needsSync: Boolean,
)

@Entity(
    tableName = "episodes",
    indices = [Index("podcastId")],
)
data class EpisodeEntity(
    @PrimaryKey val episodeKey: String,
    val podcastId: String,
    val title: String,
    val description: String,
    val enclosureUrl: String,
    val mimeType: String?,
    val guid: String?,
    val durationMs: Long?,
    val publishedAt: Long?,
    val imageUrl: String?,
    val chaptersJson: String,
    val chaptersUrl: String?,
    val transcriptsJson: String,
    val personsJson: String,
)

@Entity(tableName = "progress")
data class ProgressEntity(
    @PrimaryKey val episodeKey: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val deviceId: String,
    val status: String,
    val serverReceivedAt: Long?,
)

@Entity(tableName = "outbox", primaryKeys = ["episodeKey", "updatedAt"])
data class OutboxEntity(
    val episodeKey: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val deviceId: String,
    val status: String,
    val podcastGuid: String?,
    val episodeGuid: String?,
    val feedUrl: String?,
)

@Entity(tableName = "pending_unsub")
data class PendingUnsubEntity(
    @PrimaryKey val feedUrl: String,
)

data class ContinueRow(
    val episodeKey: String,
    val episodeTitle: String,
    val podcastTitle: String,
    val imageUrl: String?,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)

data class EpisodeRow(
    @androidx.room.Embedded val episode: EpisodeEntity,
    val positionMs: Long?,
    val progressStatus: String?,
)
