package de.jenz.nahtlos.core.playlist

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.jenz.nahtlos.core.db.DownloadEntity
import de.jenz.nahtlos.core.db.NahtlosDatabase
import de.jenz.nahtlos.core.net.Net
import de.jenz.nahtlos.core.sync.SettingsStore
import de.jenz.nahtlos.feed.EpisodeKeys
import de.jenz.nahtlos.model.ProgressStatus
import de.jenz.nahtlos.playlist.DownloadCleanup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
) {
    fun enqueue() {
        val current = settings.read()
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (current.downloadOnUnmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
            )
            .build()
        val request = OneTimeWorkRequestBuilder<PlaylistDownloadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            DOWNLOAD_WORK,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun enqueueEpisode(episodeKey: String) {
        if (episodeKey.isBlank()) return
        val request = OneTimeWorkRequestBuilder<PlaylistDownloadWorker>()
            .setInputData(androidx.work.workDataOf("episodeKey" to episodeKey))
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "nahtlos-episode-$episodeKey",
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun ensureCleanup() {
        val request = PeriodicWorkRequestBuilder<DownloadCleanupWorker>(12, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            CLEANUP_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    companion object {
        const val DOWNLOAD_WORK = "nahtlos-playlist-download"
        const val CLEANUP_WORK = "nahtlos-download-cleanup"
    }
}

@Singleton
data class DownloadTransfer(val episodeKey: String, val written: Long, val total: Long)

class DownloadStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: NahtlosDatabase,
) {
    private val transfers = MutableStateFlow<Map<String, DownloadTransfer>>(emptyMap())

    fun observeTransfers(): StateFlow<Map<String, DownloadTransfer>> = transfers.asStateFlow()

    fun report(episodeKey: String, written: Long, total: Long) {
        transfers.value = transfers.value + (episodeKey to DownloadTransfer(episodeKey, written, total))
    }

    fun clearTransfer(episodeKey: String) {
        transfers.value = transfers.value - episodeKey
    }

    fun existingFile(episodeKey: String): File? {
        val directory = File(context.filesDir, "downloads")
        val file = File(directory, EpisodeKeys.sha256(episodeKey) + ".bin")
        return file.takeIf { it.isFile && it.length() > 0L }
    }

    suspend fun usedBytes(): Long = db.downloads().usedBytes()

    suspend fun record(episodeKey: String, file: File) {
        db.downloads().upsert(
            DownloadEntity(
                episodeKey = episodeKey,
                filePath = file.absolutePath,
                bytes = file.length(),
                downloadedAt = System.currentTimeMillis(),
                completedAt = null,
            ),
        )
    }

    suspend fun delete(episodeKey: String) {
        val row = db.downloads().get(episodeKey)
        row?.filePath?.let { path -> File(path).delete() }
        db.downloads().delete(episodeKey)
    }

    fun observe() = db.downloads().observeRows()

    /** Moves the file aside so a snackbar can restore it. [commit] deletes the aside copy. */
    suspend fun park(episodeKey: String): ParkedDownload? {
        val row = db.downloads().get(episodeKey) ?: return null
        val file = File(row.filePath)
        val aside = File(file.path + ".trash")
        if (file.isFile) file.renameTo(aside)
        db.downloads().delete(episodeKey)
        return ParkedDownload(row, file, aside)
    }

    suspend fun restore(parked: ParkedDownload) {
        if (parked.aside.isFile) parked.aside.renameTo(parked.file)
        db.downloads().upsert(parked.row)
    }

    fun discard(parked: ParkedDownload) {
        parked.aside.delete()
    }
}

data class ParkedDownload(
    val row: DownloadEntity,
    val file: File,
    val aside: File,
)

class PlaylistDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val entry = EntryPointAccessors.fromApplication(applicationContext, DownloadEntryPoint::class.java)
        val playlist = entry.playlistRepository().current()
        val only = inputData.getString("episodeKey")
        val settings = entry.settings().read()
        val store = entry.downloadStore()
        val client = entry.okHttp()
        val limit = settings.downloadLimitMb.toLong() * 1024L * 1024L
        val directory = File(applicationContext.filesDir, "downloads").apply { mkdirs() }
        try {
            val queued = if (only.isNullOrBlank()) {
                playlist.items
            } else {
                val episode = entry.database().episodes().get(only)
                listOfNotNull(
                    episode?.let {
                        de.jenz.nahtlos.playlist.PlaylistEntry(
                            episodeKey = it.episodeKey,
                            publishedAt = it.publishedAt ?: 0L,
                            title = it.title,
                            enclosureUrl = it.enclosureUrl,
                        )
                    },
                )
            }
            for (item in queued) {
                if (item.enclosureUrl.isBlank()) continue
                val progress = entry.database().progress().get(item.episodeKey)
                if (progress?.status == ProgressStatus.COMPLETED.name) continue
                if (store.existingFile(item.episodeKey) != null) continue
                val target = File(directory, EpisodeKeys.sha256(item.episodeKey) + ".bin")
                val partial = File(directory, target.name + ".partial")
                val response = client.newCall(
                    Request.Builder().url(item.enclosureUrl).header("User-Agent", Net.USER_AGENT).build(),
                ).execute()
                var stop = false
                response.use { bodyResponse ->
                    if (!bodyResponse.isSuccessful) return@use
                    val length = bodyResponse.body?.contentLength() ?: -1L
                    if (length > 0 && !DownloadCleanup.canStore(store.usedBytes(), length, limit)) {
                        stop = true
                        return@use
                    }
                    val body = bodyResponse.body ?: return@use
                    store.report(item.episodeKey, 0L, length)
                    try {
                        body.byteStream().use { input ->
                            partial.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var written = 0L
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    output.write(buffer, 0, read)
                                    written += read
                                    store.report(item.episodeKey, written, length)
                                }
                            }
                        }
                    } finally {
                        store.clearTransfer(item.episodeKey)
                    }
                    val size = partial.length()
                    if (!DownloadCleanup.canStore(store.usedBytes(), size, limit)) {
                        partial.delete()
                        stop = true
                        return@use
                    }
                    if (target.exists()) target.delete()
                    if (!partial.renameTo(target)) {
                        partial.copyTo(target, overwrite = true)
                        partial.delete()
                    }
                    store.record(item.episodeKey, target)
                }
                if (stop) break
            }
            Result.success()
        } catch (_: Exception) {
            if (runAttemptCount >= 5) Result.failure() else Result.retry()
        }
    }
}

class DownloadCleanupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val entry = EntryPointAccessors.fromApplication(applicationContext, DownloadEntryPoint::class.java)
        val delayMs = entry.settings().read().deletePlayedAfterHours * 60L * 60L * 1000L
        val now = System.currentTimeMillis()
        val downloads = entry.database().downloads().all()
        val progress = entry.database().progress().all().associateBy { it.episodeKey }
        for (row in downloads) {
            val played = progress[row.episodeKey]?.status == ProgressStatus.COMPLETED.name
            if (!played) continue
            val completedAt = row.completedAt ?: now.also {
                entry.database().downloads().markCompleted(row.episodeKey, now)
            }
            if (row.completedAt == null) continue
            if (DownloadCleanup.shouldDeleteDownload(completedAt, now, delayMs)) {
                entry.downloadStore().delete(row.episodeKey)
            }
        }
        entry.playlistRepository().dropRemotelyFinished()
        return Result.success()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DownloadEntryPoint {
    fun playlistRepository(): PlaylistRepository
    fun downloadStore(): DownloadStore
    fun settings(): SettingsStore
    fun database(): NahtlosDatabase
    fun okHttp(): OkHttpClient
}
