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
    version = 5,
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
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE podcasts ADD COLUMN fundingJson TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE podcasts ADD COLUMN valueJson TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE podcasts ADD COLUMN locked INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE podcasts ADD COLUMN playbackJson TEXT NOT NULL DEFAULT '{}'")
                db.execSQL("ALTER TABLE episodes ADD COLUMN extrasJson TEXT NOT NULL DEFAULT '{}'")
                db.execSQL("ALTER TABLE episodes ADD COLUMN transcriptCache TEXT NOT NULL DEFAULT ''")
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE podcasts ADD COLUMN extrasJson TEXT NOT NULL DEFAULT '{}'")
            }
        }
    }
}
