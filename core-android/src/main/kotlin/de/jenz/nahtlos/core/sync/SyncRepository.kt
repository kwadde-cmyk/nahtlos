package de.jenz.nahtlos.core.sync

import android.util.Log
import androidx.room.withTransaction
import de.jenz.nahtlos.core.AppLog
import de.jenz.nahtlos.core.SyncPolicy
import de.jenz.nahtlos.core.db.PendingUnsubEntity
import de.jenz.nahtlos.core.db.NahtlosDatabase
import de.jenz.nahtlos.core.db.toModel
import de.jenz.nahtlos.core.db.toOutbox
import de.jenz.nahtlos.core.db.toProgressEntity
import de.jenz.nahtlos.core.db.toSubscription
import de.jenz.nahtlos.core.feed.FeedRefreshWork
import de.jenz.nahtlos.core.feed.FeedRepository
import de.jenz.nahtlos.core.playlist.PlaylistRepository
import de.jenz.nahtlos.opml.Opml
import de.jenz.nahtlos.opml.OpmlOutline
import de.jenz.nahtlos.feed.EpisodeKeys
import de.jenz.nahtlos.model.EpisodeProgress
import de.jenz.nahtlos.model.ProgressStatus
import de.jenz.nahtlos.model.SyncEvent
import de.jenz.nahtlos.model.NahtlosJson
import de.jenz.nahtlos.sync.OutboxPlanner
import de.jenz.nahtlos.sync.ProgressResolver
import kotlinx.coroutines.CancellationException
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
    private val feedRefresh: FeedRefreshWork,
    private val playlists: PlaylistRepository,
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

    fun currentSettings() = settings.read()

    fun serverDeviceId(): String = settings.serverDeviceId()

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
                if (e is CancellationException) throw e
                error = fail("Abo-Sync", e)
            }
            try {
                reconcileSubscriptions(current)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = fail("Abo-Abruf", e)
            }
            try {
                syncProgress(current)
                playlists.pull()
                playlists.dropRemotelyFinished()
                lastPullAt = System.currentTimeMillis()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = fail("Positions-Sync", e)
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

    suspend fun importOpml(xml: String): Int {
        val outlines = Opml.parse(xml)
        val count = feeds.rememberOutlines(outlines)
        val current = settings.read()
        if (current.configured) {
            try {
                api.putOpml(current, xml)
            } catch (e: CancellationException) {
                throw e
            } catch (error: Exception) {
                _status.update { it.copy(lastError = explain(error)) }
            }
            try {
                pushSubscriptionChanges(current)
            } catch (e: CancellationException) {
                throw e
            } catch (error: Exception) {
                _status.update { it.copy(lastError = explain(error)) }
            }
        }
        scheduleFlush()
        feedRefresh.schedule()
        return count
    }

    suspend fun testConnection(): String {
        val current = settings.read()
        if (!current.configured) return "Server-URL und Token fehlen"
        api.health(current)
        return "Server erreichbar"
    }

    /**
     * The first login still uses the household admin token. The server then hands
     * this install its own device token and the admin token stays on the server.
     * A token that is already a device token comes back as 403 and is left as-is.
     */
    suspend fun enrollOwnDevice(name: String): String? {
        val current = settings.read()
        if (!current.configured) return null
        val created = try {
            api.bootstrap(current, name.trim().ifBlank { "Gerät" })
        } catch (error: SyncHttpException) {
            if (error.code == 403) return null
            throw error
        }
        settings.saveServerDeviceId(created.id)
        settings.save(
            serverUrl = current.serverUrl,
            token = created.token,
            bufferAheadMinutes = current.bufferAheadMinutes,
            downloadOnUnmeteredOnly = current.downloadOnUnmeteredOnly,
            downloadLimitMb = current.downloadLimitMb,
            deletePlayedAfterHours = current.deletePlayedAfterHours,
        )
        return "Eigenes Geräte-Token für „${created.name}“ gespeichert. Das Admin-Token bleibt am Server gültig."
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
    suspend fun localPosition(episodeKey: String): Long =
        db.progress().get(episodeKey)?.positionMs ?: 0L

    /**
     * Asks the server without blocking playback. Returns a position only when the remote
     * document is newer than the local one. Cancellation is not swallowed, and the wait
     * is capped by [SyncPolicy.PLAY_GATE_MS].
     */
    suspend fun newerRemotePosition(episodeKey: String): Long? {
        val local = db.progress().get(episodeKey)?.toModel()
        val current = settings.read()
        if (!current.configured) return null
        val remote = withTimeoutOrNull(SyncPolicy.PLAY_GATE_MS) {
            try {
                api.getProgress(current, episodeKey)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLog.record("Sync", "Position für $episodeKey nicht geholt: ${error.message}", error)
                null
            }
        } ?: return null
        if (local == null) {
            db.progress().upsert(remote.toProgressEntity())
            _remote.emit(remote)
            return remote.positionMs
        }
        val winner = ProgressResolver.pick(local, remote)
        if (winner !== remote) return null
        db.progress().upsert(remote.toProgressEntity())
        db.outbox().deleteUpTo(episodeKey, remote.updatedAt)
        _remote.emit(remote)
        return remote.positionMs
    }

    suspend fun positionForPlayback(episodeKey: String): Long {
        val localMs = localPosition(episodeKey)
        val local = db.progress().get(episodeKey)?.toModel()
        val current = settings.read()
        if (!current.configured) return localMs
        val remote = withTimeoutOrNull(SyncPolicy.PLAY_GATE_MS) {
            try {
                api.getProgress(current, episodeKey)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLog.record("Sync", "Position für $episodeKey nicht geholt: ${error.message}", error)
                null
            }
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
        if (event.type == SyncEvent.TYPE_PLAYLIST) {
            val remotePlaylist = event.playlist ?: return
            playlists.applyRemote(remotePlaylist)
            return
        }
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
        val missing = mutableListOf<OpmlOutline>()
        for (subscription in remote) {
            val url = EpisodeKeys.normalizeFeedUrl(subscription.feedUrl)
            if (url in pendingDeletes) continue
            if (db.podcasts().byFeedUrl(url) == null) {
                missing += OpmlOutline(subscription.title, url)
            }
        }
        if (missing.isNotEmpty()) {
            feeds.rememberOutlines(missing)
            feedRefresh.schedule()
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
            if (error is CancellationException) throw error
            // Upload anyway. The server still applies last-write-wins if the GET was the part that failed.
            try {
                flushOutbox(current)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The original GET failure is what the caller sees.
            }
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

    private fun fail(stage: String, error: Throwable): String {
        val message = explain(error)
        Log.w(TAG, "$stage fehlgeschlagen", error)
        AppLog.record("Sync", "$stage: $message", error)
        return message
    }

    private fun explain(error: Throwable): String = when (error) {
        is SyncHttpException -> error.message ?: "Sync fehlgeschlagen"
        else -> error.message?.takeIf { it.isNotBlank() } ?: "Sync fehlgeschlagen"
    }

    companion object {
        private const val TAG = "Nahtlos"
    }
}
