package de.jenz.nahtlos.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PodcastDao {
    @Query("SELECT * FROM podcasts ORDER BY title COLLATE NOCASE")
    fun observeAll(): Flow<List<PodcastEntity>>

    @Query("SELECT * FROM podcasts ORDER BY title COLLATE NOCASE")
    suspend fun all(): List<PodcastEntity>

    @Query("SELECT * FROM podcasts WHERE id = :id")
    suspend fun get(id: String): PodcastEntity?

    @Query("SELECT * FROM podcasts WHERE id = :id")
    fun observe(id: String): Flow<PodcastEntity?>

    @Query("SELECT * FROM podcasts WHERE feedUrl = :feedUrl LIMIT 1")
    suspend fun byFeedUrl(feedUrl: String): PodcastEntity?

    @Query("SELECT * FROM podcasts WHERE needsSync = 1")
    suspend fun needingSync(): List<PodcastEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PodcastEntity)

    @Query("UPDATE podcasts SET needsSync = :needsSync WHERE id = :id")
    suspend fun setNeedsSync(id: String, needsSync: Boolean)

    @Query("DELETE FROM podcasts WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface EpisodeDao {
    @Query(
        """
        SELECT e.*, pr.positionMs AS positionMs, pr.status AS progressStatus
        FROM episodes e
        LEFT JOIN progress pr ON pr.episodeKey = e.episodeKey
        WHERE e.podcastId = :podcastId
        ORDER BY e.publishedAt IS NULL, e.publishedAt DESC
        """,
    )
    fun observeForPodcast(podcastId: String): Flow<List<EpisodeRow>>

    @Query("SELECT * FROM episodes WHERE episodeKey = :episodeKey")
    suspend fun get(episodeKey: String): EpisodeEntity?

    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId ORDER BY publishedAt IS NULL, publishedAt DESC")
    suspend fun forPodcast(podcastId: String): List<EpisodeEntity>

    @Query("SELECT episodeKey FROM episodes WHERE podcastId = :podcastId")
    suspend fun keysForPodcast(podcastId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<EpisodeEntity>)

    @Query("UPDATE episodes SET durationMs = :durationMs WHERE episodeKey = :episodeKey")
    suspend fun updateDuration(episodeKey: String, durationMs: Long)

    @Query("UPDATE episodes SET chaptersJson = :chaptersJson WHERE episodeKey = :episodeKey")
    suspend fun updateChapters(episodeKey: String, chaptersJson: String)

    @Query("DELETE FROM episodes WHERE podcastId = :podcastId")
    suspend fun deleteForPodcast(podcastId: String)

    @Query(
        """
        SELECT e.episodeKey AS episodeKey,
               e.title AS title,
               e.publishedAt AS publishedAt,
               e.enclosureUrl AS enclosureUrl,
               p.title AS podcastTitle,
               p.feedUrl AS feedUrl
        FROM episodes e
        INNER JOIN podcasts p ON p.id = e.podcastId
        WHERE e.publishedAt IS NOT NULL
        """,
    )
    suspend fun candidates(): List<EpisodeCandidateRow>
}

@Dao
interface ProgressDao {
    @Query("SELECT * FROM progress WHERE episodeKey = :episodeKey")
    suspend fun get(episodeKey: String): ProgressEntity?

    @Query("SELECT * FROM progress")
    suspend fun all(): List<ProgressEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ProgressEntity)

    @Query("DELETE FROM progress WHERE episodeKey IN (:keys)")
    suspend fun deleteKeys(keys: List<String>)

    @Query(
        """
        SELECT e.episodeKey AS episodeKey,
               e.title AS episodeTitle,
               p.title AS podcastTitle,
               COALESCE(e.imageUrl, p.imageUrl) AS imageUrl,
               pr.positionMs AS positionMs,
               CASE WHEN pr.durationMs > 0 THEN pr.durationMs ELSE COALESCE(e.durationMs, 0) END AS durationMs,
               pr.updatedAt AS updatedAt
        FROM progress pr
        INNER JOIN episodes e ON e.episodeKey = pr.episodeKey
        INNER JOIN podcasts p ON p.id = e.podcastId
        WHERE pr.status != 'COMPLETED'
          AND pr.positionMs > 3000
          AND NOT (pr.durationMs > 0 AND pr.positionMs >= pr.durationMs - 15000)
        ORDER BY pr.updatedAt DESC
        LIMIT 30
        """,
    )
    fun observeContinue(): Flow<List<ContinueRow>>

    @Query(
        """
        SELECT e.episodeKey AS episodeKey,
               e.title AS episodeTitle,
               p.title AS podcastTitle,
               COALESCE(e.imageUrl, p.imageUrl) AS imageUrl,
               pr.positionMs AS positionMs,
               CASE WHEN pr.durationMs > 0 THEN pr.durationMs ELSE COALESCE(e.durationMs, 0) END AS durationMs,
               pr.updatedAt AS updatedAt
        FROM progress pr
        INNER JOIN episodes e ON e.episodeKey = pr.episodeKey
        INNER JOIN podcasts p ON p.id = e.podcastId
        WHERE pr.status != 'COMPLETED'
          AND pr.positionMs > 3000
          AND NOT (pr.durationMs > 0 AND pr.positionMs >= pr.durationMs - 15000)
        ORDER BY pr.updatedAt DESC
        LIMIT 1
        """,
    )
    suspend fun topContinue(): ContinueRow?
}

@Dao
interface OutboxDao {
    @Query("SELECT * FROM outbox ORDER BY updatedAt ASC")
    suspend fun all(): List<OutboxEntity>

    @Query("SELECT COUNT(*) FROM outbox")
    fun observeCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: OutboxEntity)

    @Query("DELETE FROM outbox WHERE episodeKey = :episodeKey")
    suspend fun deleteEpisode(episodeKey: String)

    @Query("DELETE FROM outbox WHERE episodeKey = :episodeKey AND updatedAt <= :updatedAt")
    suspend fun deleteUpTo(episodeKey: String, updatedAt: Long)

    @Query("DELETE FROM outbox WHERE episodeKey IN (:keys)")
    suspend fun deleteKeys(keys: List<String>)
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlist_doc WHERE id = 1")
    suspend fun get(): PlaylistDocEntity?

    @Query("SELECT * FROM playlist_doc WHERE id = 1")
    fun observe(): Flow<PlaylistDocEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PlaylistDocEntity)
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads")
    suspend fun all(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE episodeKey = :episodeKey")
    suspend fun get(episodeKey: String): DownloadEntity?

    @Query("SELECT COALESCE(SUM(bytes), 0) FROM downloads")
    suspend fun usedBytes(): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DownloadEntity)

    @Query("UPDATE downloads SET completedAt = :completedAt WHERE episodeKey = :episodeKey AND completedAt IS NULL")
    suspend fun markCompleted(episodeKey: String, completedAt: Long)

    @Query("DELETE FROM downloads WHERE episodeKey = :episodeKey")
    suspend fun delete(episodeKey: String)
}

@Dao
interface PendingUnsubDao {
    @Query("SELECT * FROM pending_unsub")
    suspend fun all(): List<PendingUnsubEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PendingUnsubEntity)

    @Query("DELETE FROM pending_unsub WHERE feedUrl = :feedUrl")
    suspend fun delete(feedUrl: String)
}
