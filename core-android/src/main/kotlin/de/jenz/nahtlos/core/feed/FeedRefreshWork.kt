package de.jenz.nahtlos.core.feed

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

/** Unique connected-network job. Leaving the import screen does not cancel it. */
@Singleton
class FeedRefreshWork @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun schedule() {
        val request = OneTimeWorkRequestBuilder<FeedRefreshWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    companion object {
        const val UNIQUE_NAME = "nahtlos-feed-refresh"
    }
}

class FeedRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val feeds = EntryPointAccessors.fromApplication(
            applicationContext,
            FeedRefreshEntryPoint::class.java,
        ).feedRepository()
        feeds.requeueInterrupted()
        val gate = Semaphore(FeedRefresh.CONCURRENCY)
        while (true) {
            val batch = feeds.pendingRefresh()
            if (batch.isEmpty()) return Result.success()
            coroutineScope {
                batch.map { podcast ->
                    async {
                        gate.withPermit {
                            try {
                                feeds.refresh(podcast.id)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                feeds.markFailed(podcast.id, error.message)
                            }
                        }
                    }
                }.awaitAll()
            }
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface FeedRefreshEntryPoint {
    fun feedRepository(): FeedRepository
}
