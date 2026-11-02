package de.jenz.nahtlos.core.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        PodcastEntity::class,
        EpisodeEntity::class,
        ProgressEntity::class,
        OutboxEntity::class,
        PendingUnsubEntity::class,
        PlaylistDocEntity::class,
        DownloadEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class NahtlosDatabase : RoomDatabase() {
    abstract fun podcasts(): PodcastDao
    abstract fun episodes(): EpisodeDao
    abstract fun progress(): ProgressDao
    abstract fun outbox(): OutboxDao
    abstract fun pendingUnsub(): PendingUnsubDao
    abstract fun playlist(): PlaylistDao
    abstract fun downloads(): DownloadDao
}
