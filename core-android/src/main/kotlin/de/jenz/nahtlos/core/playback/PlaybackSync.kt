package de.jenz.nahtlos.core.playback

import android.os.SystemClock
import androidx.media3.common.Player
import de.jenz.nahtlos.core.SyncPolicy
import de.jenz.nahtlos.core.sync.SyncRepository
import de.jenz.nahtlos.model.ProgressStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * Pushes the local position on pause, stop, seek and a heartbeat while playing.
 * Applies a newer remote position without writing that seek back as our own.
 */
class PlaybackSync(
    private val player: Player,
    private val sync: SyncRepository,
    private val scope: CoroutineScope,
    private val onEpisodeEnded: (String) -> Unit = {},
    private val onAdoptIdle: () -> Unit = {},
) : Player.Listener {
    private var heartbeat: Job? = null
    private var ignoreSeekUntil = 0L
    private var suppressPushUntil = 0L

    /** Ignores position writes while the car warms the cache at the saved resume point. */
    fun suppressPushes(durationMs: Long = 2_000) {
        val until = SystemClock.elapsedRealtime() + durationMs
        ignoreSeekUntil = until
        suppressPushUntil = until
    }

    fun start() {
        player.addListener(this)
        heartbeat = scope.launch {
            while (isActive) {
                delay(SyncPolicy.HEARTBEAT_MS)
                if (player.isPlaying) push(ProgressStatus.PLAYING)
            }
        }
        scope.launch {
            sync.remoteProgress.collect { remote ->
                if (remote.deviceId == sync.deviceId()) return@collect
                if (holdsPlayback()) {
                    val current = player.currentMediaItem?.mediaId ?: return@collect
                    if (remote.episodeKey != current) return@collect
                    if (abs(player.currentPosition - remote.positionMs) < 1_500) return@collect
                    ignoreSeekUntil = SystemClock.elapsedRealtime() + SyncPolicy.SEEK_IGNORE_MS
                    player.seekTo(remote.positionMs)
                    return@collect
                }
                onAdoptIdle()
            }
        }
    }

    /** Wiedergabe oder ein gerade gestartetes Puffern. Idle und Ende geben die Folge frei. */
    private fun holdsPlayback(): Boolean {
        if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) return false
        return player.isPlaying || player.playWhenReady
    }

    fun flushBlocking() {
        val snapshot = snapshot(currentStatus()) ?: run {
            runBlocking { withTimeoutOrNull(2_000) { /* outbox flush happens inside recordPlayback */ } }
            return
        }
        runBlocking {
            withTimeoutOrNull(2_500) {
                sync.recordPlayback(snapshot.key, snapshot.positionMs, snapshot.durationMs, snapshot.status)
            }
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (!isPlaying) push(currentStatus())
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason != Player.DISCONTINUITY_REASON_SEEK) return
        if (SystemClock.elapsedRealtime() < ignoreSeekUntil) return
        push(currentStatus())
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) {
            push(ProgressStatus.COMPLETED)
            val key = player.currentMediaItem?.mediaId
            if (!key.isNullOrBlank() && !LibraryIds.isFolder(key)) onEpisodeEnded(key)
        }
    }

    private fun push(status: ProgressStatus) {
        if (SystemClock.elapsedRealtime() < suppressPushUntil) return
        val snapshot = snapshot(status) ?: return
        scope.launch(Dispatchers.IO) {
            sync.recordPlayback(snapshot.key, snapshot.positionMs, snapshot.durationMs, snapshot.status)
        }
    }

    private fun snapshot(status: ProgressStatus): Snapshot? {
        val key = player.currentMediaItem?.mediaId ?: return null
        if (key.isBlank() || LibraryIds.isFolder(key)) return null
        val duration = player.duration.takeIf { it > 0 } ?: 0L
        return Snapshot(key, player.currentPosition.coerceAtLeast(0), duration, status)
    }

    private fun currentStatus(): ProgressStatus = when {
        player.playbackState == Player.STATE_ENDED -> ProgressStatus.COMPLETED
        player.isPlaying -> ProgressStatus.PLAYING
        player.playbackState == Player.STATE_IDLE -> ProgressStatus.STOPPED
        else -> ProgressStatus.PAUSED
    }

    private data class Snapshot(
        val key: String,
        val positionMs: Long,
        val durationMs: Long,
        val status: ProgressStatus,
    )
}
