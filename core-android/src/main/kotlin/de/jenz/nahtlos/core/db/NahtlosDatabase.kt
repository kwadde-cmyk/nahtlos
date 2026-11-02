package de.jenz.nahtlos.core.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    version = 3,
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

    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE podcasts ADD COLUMN refreshStatus TEXT NOT NULL DEFAULT 'OK'")
                db.execSQL("ALTER TABLE podcasts ADD COLUMN refreshError TEXT")
            }
        }
    }
}
