package de.jenz.nahtlos.core.sync

import android.util.Log
import androidx.room.withTransaction
import de.jenz.nahtlos.core.SyncPolicy
import de.jenz.nahtlos.core.db.PendingUnsubEntity
import de.jenz.nahtlos.core.db.NahtlosDatabase
import de.jenz.nahtlos.core.db.toModel
import de.jenz.nahtlos.core.db.toOutbox
import de.jenz.nahtlos.core.db.toProgressEntity
import de.jenz.nahtlos.core.db.toSubscription
import de.jenz.nahtlos.core.feed.FeedRepository
import de.jenz.nahtlos.feed.EpisodeKeys
import de.jenz.nahtlos.model.EpisodeProgress
import de.jenz.nahtlos.model.ProgressStatus
import de.jenz.nahtlos.model.SyncEvent
import de.jenz.nahtlos.model.NahtlosJson
import de.jenz.nahtlos.sync.OutboxPlanner
import de.jenz.nahtlos.sync.ProgressResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

data class SyncStatus(
    val configured: Boolean = false,
    val syncing: Boolean = false,
    val pending: Int = 0,
    val lastError: String? = null,
    val lastSyncAt: Long? = null,
)

@Singleton
class SyncRepository @Inject constructor(
    private val db: NahtlosDatabase,
    private val api: SyncClient,
    private val settings: SettingsStore,
    private val feeds: FeedRepository,
    private val outboxWork: OutboxWork,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()
    private val _remote = MutableSharedFlow<EpisodeProgress>(extraBufferCapacity = 32)
    val remoteProgress: SharedFlow<EpisodeProgress> = _remote.asSharedFlow()
    private var lastPullAt = 0L

    init {
        scope.launch {
            db.outbox().observeCount().collect { count ->
                _status.update { it.copy(pending = count, configured = settings.read().configured) }
            }
        }
        scope.launch {
            settings.observe().collect { current ->
                _status.update { it.copy(configured = current.configured) }
            }
        }
    }

    fun deviceId(): String = settings.read().deviceId

    suspend fun onNetworkAvailable() {
        pullAndMerge(force = true)
    }

    suspend fun pullAndMerge(force: Boolean = false) {
        val current = settings.read()
        if (!current.configured) {
            _status.update { it.copy(configured = false, syncing = false) }
            return
        }
        val now = System.currentTimeMillis()
        if (!force && now - lastPullAt < SyncPolicy.PULL_DEBOUNCE_MS) return
        gate.withLock {
            _status.update { it.copy(syncing = true, configured = true) }
            var error: String? = null
            try {
                pushSubscriptionChanges(current)
            } catch (e: Exception) {
                error = explain(e)
                Log.w(TAG, "Abo-Sync fehlgeschlagen", e)
            }
            try {
                reconcileSubscriptions(current)
            } catch (e: Exception) {
                error = explain(e)
                Log.w(TAG, "Abo-Abruf fehlgeschlagen", e)
            }
            try {
                syncProgress(current)
                lastPullAt = System.currentTimeMillis()
            } catch (e: Exception) {
                error = explain(e)
                Log.w(TAG, "Positions-Sync fehlgeschlagen", e)
            }
            _status.update {
                it.copy(
                    syncing = false,
                    lastError = error,
                    lastSyncAt = if (error == null) System.currentTimeMillis() else it.lastSyncAt,
                )
            }
        }
    }

    suspend fun testConnection(): String {
        val current = settings.read()
        if (!current.configured) return "Server-URL und Token fehlen"
        api.health(current)
        return "Server erreichbar"
    }

    suspend fun pushSubscription(feedUrl: String) {
        val podcast = db.podcasts().byFeedUrl(EpisodeKeys.normalizeFeedUrl(feedUrl)) ?: return
        db.podcasts().setNeedsSync(podcast.id, true)
        scheduleFlush()
        val current = settings.read()
        if (!current.configured) return
        runCatching { pushSubscriptionChanges(current) }
            .onFailure { error -> _status.update { it.copy(lastError = explain(error)) } }
    }

    suspend fun markUnsubscribed(feedUrl: String) {
        val normalized = EpisodeKeys.normalizeFeedUrl(feedUrl)
        db.pendingUnsub().insert(PendingUnsubEntity(normalized))
        scheduleFlush()
        val current = settings.read()
        if (!current.configured) return
        runCatching { pushSubscriptionChanges(current) }
            .onFailure { error -> _status.update { it.copy(lastError = explain(error)) } }
    }

    /** Uploads queued positions and subscription changes. Safe to call when nothing is pending. */
    suspend fun flushPending() {
        val current = settings.read()
        if (!current.configured) return
        pushSubscriptionChanges(current)
        flushOutbox(current)
    }

    suspend fun recordPlayback(
        episodeKey: String,
        positionMs: Long,
        playerDurationMs: Long,
        status: ProgressStatus,
    ) {
        if (episodeKey.isBlank() || episodeKey == "root") return
        val episode = db.episodes().get(episodeKey)
        val podcast = episode?.let { db.podcasts().get(it.podcastId) }
        val duration = when {
            playerDurationMs > 0 -> playerDurationMs
            episode?.durationMs != null && episode.durationMs > 0 -> episode.durationMs
            else -> 0L
        }
        if (episode != null && playerDurationMs > 0 && (episode.durationMs == null || episode.durationMs == 0L)) {
            db.episodes().updateDuration(episodeKey, playerDurationMs)
        }
        var effective = status
        if (duration > 0 && positionMs >= duration - SyncPolicy.NEAR_END_MS && status != ProgressStatus.PLAYING) {
            effective = ProgressStatus.COMPLETED
        }
        val progress = EpisodeProgress(
            episodeKey = episodeKey,
            podcastGuid = podcast?.podcastGuid,
            episodeGuid = episode?.guid,
            feedUrl = podcast?.feedUrl,
            positionMs = positionMs,
            durationMs = duration,
            updatedAt = System.currentTimeMillis(),
            deviceId = settings.read().deviceId,
            status = effective,
        ).normalized()
        val stored = db.withTransaction {
            val existing = db.progress().get(episodeKey)?.toModel()
            if (existing != null && ProgressResolver.pick(existing, progress) !== progress) {
                false
            } else {
                db.progress().upsert(progress.toProgressEntity())
                db.outbox().deleteEpisode(progress.episodeKey)
                db.outbox().insert(progress.toOutbox(progress.podcastGuid, progress.episodeGuid, progress.feedUrl))
                true
            }
        }
        if (!stored) return
        scheduleFlush()
        val current = settings.read()
        if (current.configured) {
            runCatching { flushOutbox(current) }
                .onFailure { error -> _status.update { it.copy(lastError = explain(error)) } }
        }
    }

    /**
     * Blocks playback for at most [SyncPolicy.PLAY_GATE_MS], then falls back to the local position.
     * A newer remote document wins; a newer local document is queued instead of being overwritten.
     */
    suspend fun positionForPlayback(episodeKey: String): Long {
        val local = db.progress().get(episodeKey)?.toModel()
        val current = settings.read()
        if (!current.configured) return local?.positionMs ?: 0L
        val remote = withTimeoutOrNull(SyncPolicy.PLAY_GATE_MS) {
            runCatching { api.getProgress(current, episodeKey) }.getOrNull()
        }
        if (remote == null) return local?.positionMs ?: 0L
        if (local == null) {
            db.progress().upsert(remote.toProgressEntity())
            _remote.emit(remote)
            return remote.positionMs
        }
        val winner = ProgressResolver.pick(local, remote)
        db.progress().upsert(winner.toProgressEntity())
        if (winner === remote) {
            db.outbox().deleteUpTo(episodeKey, remote.updatedAt)
            _remote.emit(remote)
        } else if (winner.updatedAt > remote.updatedAt || winner.positionMs != remote.positionMs) {
            db.outbox().deleteEpisode(episodeKey)
            db.outbox().insert(local.toOutbox(local.podcastGuid, local.episodeGuid, local.feedUrl))
            scheduleFlush()
            scope.launch { runCatching { flushOutbox(current) } }
        }
        return winner.positionMs
    }

    suspend fun onSocketMessage(text: String) {
        val event = runCatching { NahtlosJson.json.decodeFromString<SyncEvent>(text) }.getOrNull() ?: return
        val progress = event.progress ?: return
        if (event.type != SyncEvent.TYPE_PROGRESS) return
        if (progress.deviceId == settings.read().deviceId) return
        applyRemote(progress)
    }

    private suspend fun applyRemote(remote: EpisodeProgress) {
        val local = db.progress().get(remote.episodeKey)?.toModel()
        if (local == null) {
            db.progress().upsert(remote.toProgressEntity())
            _remote.emit(remote)
            return
        }
        val winner = ProgressResolver.pick(local, remote)
        if (winner === remote) {
            db.progress().upsert(remote.toProgressEntity())
            db.outbox().deleteUpTo(remote.episodeKey, remote.updatedAt)
            _remote.emit(remote)
        }
    }

    private suspend fun pushSubscriptionChanges(current: ServerSettings) {
        for (podcast in db.podcasts().needingSync()) {
            api.putSubscription(current, podcast.toSubscription())
            db.podcasts().setNeedsSync(podcast.id, false)
        }
        for (pending in db.pendingUnsub().all()) {
            api.deleteSubscription(current, pending.feedUrl)
            db.pendingUnsub().delete(pending.feedUrl)
        }
    }

    private suspend fun reconcileSubscriptions(current: ServerSettings) {
        val remote = api.getSubscriptions(current)
        val remoteUrls = remote.map { EpisodeKeys.normalizeFeedUrl(it.feedUrl) }.toSet()
        val pendingDeletes = db.pendingUnsub().all().map { it.feedUrl }.toSet()
        for (subscription in remote) {
            val url = EpisodeKeys.normalizeFeedUrl(subscription.feedUrl)
            if (url in pendingDeletes) continue
            if (db.podcasts().byFeedUrl(url) == null) {
                runCatching { feeds.subscribe(url, markNeedsSync = false) }
                    .onFailure { error -> Log.w(TAG, "Feed $url nicht geladen", error) }
            }
        }
        for (podcast in db.podcasts().all()) {
            if (podcast.needsSync) continue
            if (podcast.feedUrl in pendingDeletes) continue
            if (podcast.feedUrl !in remoteUrls) feeds.deleteLocal(podcast.id)
        }
    }

    private suspend fun flushOutbox(current: ServerSettings) {
        val queued = db.outbox().all().map { it.toModel() }
        val plan = OutboxPlanner.plan(queued, emptyMap())
        for (drop in plan.toDrop) db.outbox().deleteUpTo(drop.episodeKey, drop.updatedAt)
        if (plan.toUpload.isEmpty()) return
        val results = api.putProgressBatch(current, plan.toUpload)
        for (result in results) {
            db.outbox().deleteUpTo(result.stored.episodeKey, result.stored.updatedAt)
            if (result.accepted) {
                db.progress().upsert(result.stored.toProgressEntity())
            } else {
                applyRemote(result.stored)
            }
        }
    }

    private suspend fun syncProgress(current: ServerSettings) {
        val remote = try {
            api.getAllProgress(current)
        } catch (error: Exception) {
            // Upload anyway. The server still applies last-write-wins if the GET was the part that failed.
            runCatching { flushOutbox(current) }
            throw error
        }
        val remoteByKey = remote.associateBy { it.episodeKey }
        for (item in remote) applyRemote(item)
        val queued = db.outbox().all().map { it.toModel() }
        val plan = if (remote.isEmpty() && queued.isNotEmpty()) {
            OutboxPlanner.plan(queued, emptyMap())
        } else {
            OutboxPlanner.plan(queued, remoteByKey)
        }
        for (drop in plan.toDrop) db.outbox().deleteUpTo(drop.episodeKey, drop.updatedAt)
        if (plan.toUpload.isEmpty()) return
        val results = api.putProgressBatch(current, plan.toUpload)
        for (result in results) {
            if (result.accepted) {
                db.outbox().deleteUpTo(result.stored.episodeKey, result.stored.updatedAt)
                db.progress().upsert(result.stored.toProgressEntity())
            } else {
                applyRemote(result.stored)
                db.outbox().deleteUpTo(result.stored.episodeKey, result.stored.updatedAt)
            }
        }
    }

    private fun scheduleFlush() {
        if (settings.read().configured) outboxWork.schedule()
    }

    private fun explain(error: Throwable): String = when (error) {
        is SyncHttpException -> error.message ?: "Sync fehlgeschlagen"
        else -> error.message?.takeIf { it.isNotBlank() } ?: "Sync fehlgeschlagen"
    }

    companion object {
        private const val TAG = "Nahtlos"
    }
}
