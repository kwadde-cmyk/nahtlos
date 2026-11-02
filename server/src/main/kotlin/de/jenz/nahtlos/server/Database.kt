package de.jenz.nahtlos.server

import de.jenz.nahtlos.model.EpisodeProgress
import de.jenz.nahtlos.model.ProgressStatus
import de.jenz.nahtlos.model.ProgressWriteResponse
import de.jenz.nahtlos.model.Subscription
import de.jenz.nahtlos.sync.ProgressResolver
import java.sql.Connection
import java.sql.DriverManager

class SyncDatabase(path: String) : AutoCloseable {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:$path").apply {
        autoCommit = true
    }
    private val lock = Any()

    init {
        synchronized(lock) {
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode=WAL")
                statement.execute("PRAGMA busy_timeout=5000")
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS subscriptions (
                      feed_url TEXT PRIMARY KEY,
                      title TEXT NOT NULL,
                      podcast_guid TEXT,
                      image_url TEXT,
                      updated_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS progress (
                      episode_key TEXT PRIMARY KEY,
                      podcast_guid TEXT,
                      episode_guid TEXT,
                      feed_url TEXT,
                      position_ms INTEGER NOT NULL,
                      duration_ms INTEGER NOT NULL,
                      updated_at INTEGER NOT NULL,
                      device_id TEXT NOT NULL,
                      status TEXT NOT NULL,
                      server_received_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }
    }

    fun listSubscriptions(): List<Subscription> = synchronized(lock) {
        connection.prepareStatement(
            "SELECT feed_url, title, podcast_guid, image_url, updated_at FROM subscriptions ORDER BY title COLLATE NOCASE",
        ).use { statement ->
            statement.executeQuery().use { rows ->
                val list = mutableListOf<Subscription>()
                while (rows.next()) {
                    list += Subscription(
                        feedUrl = rows.getString(1),
                        title = rows.getString(2),
                        podcastGuid = rows.getString(3),
                        imageUrl = rows.getString(4),
                        updatedAt = rows.getLong(5),
                    )
                }
                list
            }
        }
    }

    fun upsertSubscription(subscription: Subscription, now: Long) = synchronized(lock) {
        connection.prepareStatement(
            """
            INSERT INTO subscriptions(feed_url, title, podcast_guid, image_url, updated_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(feed_url) DO UPDATE SET
              title = excluded.title,
              podcast_guid = excluded.podcast_guid,
              image_url = excluded.image_url,
              updated_at = excluded.updated_at
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, subscription.feedUrl)
            statement.setString(2, subscription.title)
            statement.setString(3, subscription.podcastGuid)
            statement.setString(4, subscription.imageUrl)
            statement.setLong(5, now)
            statement.executeUpdate()
        }
    }

    fun deleteSubscription(feedUrl: String): Boolean = synchronized(lock) {
        connection.prepareStatement("DELETE FROM subscriptions WHERE feed_url = ?").use { statement ->
            statement.setString(1, feedUrl)
            statement.executeUpdate() > 0
        }
    }

    fun listProgress(since: Long?): List<EpisodeProgress> = synchronized(lock) {
        val sql = if (since == null) {
            "SELECT * FROM progress ORDER BY updated_at DESC"
        } else {
            "SELECT * FROM progress WHERE server_received_at > ? ORDER BY updated_at DESC"
        }
        connection.prepareStatement(sql).use { statement ->
            if (since != null) statement.setLong(1, since)
            statement.executeQuery().use { rows ->
                val list = mutableListOf<EpisodeProgress>()
                while (rows.next()) list += rows.toProgress()
                list
            }
        }
    }

    fun findProgress(episodeKey: String): EpisodeProgress? = synchronized(lock) {
        connection.prepareStatement("SELECT * FROM progress WHERE episode_key = ?").use { statement ->
            statement.setString(1, episodeKey)
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.toProgress() else null
            }
        }
    }

    /**
     * Applies last-write-wins. [ProgressWriteResponse.accepted] is false when the
     * stored document won; the caller should adopt [ProgressWriteResponse.stored].
     * Returns whether client-visible fields changed, so identical retries do not fan out.
     */
    fun applyProgress(incoming: EpisodeProgress, now: Long): ApplyResult = synchronized(lock) {
        val stamped = incoming.normalized().copy(serverReceivedAt = now)
        val existing = findProgressUnlocked(stamped.episodeKey)
        if (existing == null) {
            save(stamped)
            return ApplyResult(ProgressWriteResponse(accepted = true, stored = stamped), changed = true)
        }
        val winner = ProgressResolver.pick(existing, stamped)
        val incomingWins = ProgressResolver.sameClientWrite(winner, stamped)
        if (!incomingWins) {
            return ApplyResult(ProgressWriteResponse(accepted = false, stored = existing), changed = false)
        }
        val changed = !ProgressResolver.sameClientWrite(existing, stamped)
        if (changed) save(stamped)
        ApplyResult(
            ProgressWriteResponse(accepted = true, stored = if (changed) stamped else existing),
            changed = changed,
        )
    }

    private fun findProgressUnlocked(episodeKey: String): EpisodeProgress? {
        connection.prepareStatement("SELECT * FROM progress WHERE episode_key = ?").use { statement ->
            statement.setString(1, episodeKey)
            statement.executeQuery().use { rows ->
                return if (rows.next()) rows.toProgress() else null
            }
        }
    }

    private fun save(progress: EpisodeProgress) {
        connection.prepareStatement(
            """
            INSERT INTO progress(
              episode_key, podcast_guid, episode_guid, feed_url, position_ms, duration_ms,
              updated_at, device_id, status, server_received_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(episode_key) DO UPDATE SET
              podcast_guid = excluded.podcast_guid,
              episode_guid = excluded.episode_guid,
              feed_url = excluded.feed_url,
              position_ms = excluded.position_ms,
              duration_ms = excluded.duration_ms,
              updated_at = excluded.updated_at,
              device_id = excluded.device_id,
              status = excluded.status,
              server_received_at = excluded.server_received_at
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, progress.episodeKey)
            statement.setString(2, progress.podcastGuid)
            statement.setString(3, progress.episodeGuid)
            statement.setString(4, progress.feedUrl)
            statement.setLong(5, progress.positionMs)
            statement.setLong(6, progress.durationMs)
            statement.setLong(7, progress.updatedAt)
            statement.setString(8, progress.deviceId)
            statement.setString(9, progress.status.name)
            statement.setLong(10, progress.serverReceivedAt ?: 0)
            statement.executeUpdate()
        }
    }

    override fun close() {
        synchronized(lock) { connection.close() }
    }

    private fun java.sql.ResultSet.toProgress(): EpisodeProgress = EpisodeProgress(
        episodeKey = getString("episode_key"),
        podcastGuid = getString("podcast_guid"),
        episodeGuid = getString("episode_guid"),
        feedUrl = getString("feed_url"),
        positionMs = getLong("position_ms"),
        durationMs = getLong("duration_ms"),
        updatedAt = getLong("updated_at"),
        deviceId = getString("device_id"),
        status = ProgressStatus.valueOf(getString("status")),
        serverReceivedAt = getLong("server_received_at"),
    )
}

data class ApplyResult(
    val response: ProgressWriteResponse,
    val changed: Boolean,
)
