package de.jenz.nahtlos.core.sync

import android.content.Context
import androidx.work.BackoffPolicy
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
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules a unique connected-network job. WorkManager keeps it across process death,
 * so a position written to the outbox still leaves if nobody opens the app again.
 */
@Singleton
class OutboxWork @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun schedule() {
        val request = OneTimeWorkRequestBuilder<OutboxFlushWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    companion object {
        const val UNIQUE_NAME = "nahtlos-outbox-flush"
    }
}

class OutboxFlushWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val sync = EntryPointAccessors.fromApplication(
            applicationContext,
            OutboxWorkerEntryPoint::class.java,
        ).syncRepository()
        return try {
            sync.flushPending()
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: SyncHttpException) {
            if (error.code in 400..499 || runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()
        } catch (_: Exception) {
            if (runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 8
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface OutboxWorkerEntryPoint {
    fun syncRepository(): SyncRepository
}
