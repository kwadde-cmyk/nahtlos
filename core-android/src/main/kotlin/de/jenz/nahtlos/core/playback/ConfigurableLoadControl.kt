package de.jenz.nahtlos.core.playback

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator
import de.jenz.nahtlos.playback.PlaybackCachePolicy

/**
 * Keeps loading until the configured time window is buffered, and starts playback
 * after about a second so the listener does not wait for the whole window.
 * The window is read on each decision, so a settings change applies without recreating the player.
 */
@UnstableApi
class ConfigurableLoadControl(
    private val aheadMinutes: () -> Int,
) : LoadControl {
    private val allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)

    override fun onPrepared(playerId: PlayerId) = Unit

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<ExoTrackSelection?>,
    ) = Unit

    override fun onStopped(playerId: PlayerId) = Unit

    override fun onReleased(playerId: PlayerId) = Unit

    override fun getAllocator(): Allocator = allocator

    override fun getBackBufferDurationUs(playerId: PlayerId): Long =
        PlaybackCachePolicy.BACK_BUFFER_MS * 1_000L

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = true

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        val minutes = PlaybackCachePolicy.clampMinutes(aheadMinutes())
        val maxUs = PlaybackCachePolicy.aheadMs(minutes) * 1_000L
        if (parameters.bufferedDurationUs >= maxUs) return false
        if (allocator.totalBytesAllocated >= PlaybackCachePolicy.memoryTargetBytes(minutes)) return false
        return true
    }

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        val neededMs = if (parameters.rebuffering) {
            PlaybackCachePolicy.REBUFFER_PLAYBACK_MS
        } else {
            PlaybackCachePolicy.START_PLAYBACK_MS
        }
        return parameters.bufferedDurationUs >= neededMs * 1_000L
    }

    override fun shouldContinuePreloading(
        timeline: Timeline,
        mediaPeriodId: MediaPeriodId,
        bufferedDurationUs: Long,
    ): Boolean = false
}
