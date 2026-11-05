package de.jenz.nahtlos.core.playback

import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import de.jenz.nahtlos.core.db.playback
import de.jenz.nahtlos.core.feed.FeedRepository
import de.jenz.nahtlos.core.sync.SettingsStore
import de.jenz.nahtlos.playback.PlaybackTuning
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Applies global and per-podcast playback options on the real player.
 * AAOS reaches the same knobs through custom session actions.
 */
class PlaybackEffects(
    private val player: ExoPlayer,
    private val settings: SettingsStore,
    private val feeds: FeedRepository,
    private val scope: CoroutineScope,
) {
    private var boost: LoudnessEnhancer? = null
    private var outroArmedFor: String? = null
    private var shownChapter: String? = null

    fun applyForCurrentItem() {
        val key = player.currentMediaItem?.mediaId ?: return
        scope.launch {
            val prefs = feeds.playbackForEpisode(key)
            val global = settings.read()
            val speed = PlaybackTuning.clampSpeed(prefs?.speed ?: global.playbackSpeed)
            val silence = prefs?.skipSilence ?: global.skipSilence
            val louder = prefs?.boost ?: global.volumeBoost
            val intro = PlaybackTuning.introStartMs(player.currentPosition, prefs?.introSec ?: 0)
            withContext(Dispatchers.Main) {
                player.playbackParameters = player.playbackParameters.withSpeed(speed)
                player.skipSilenceEnabled = silence
                setBoost(louder)
                // Kopfhörer und Lenkrad lesen den Skip bei jedem Tastendruck neu.
                if (intro != null) player.seekTo(intro)
            }
        }
    }

    fun nudgeSpeed(direction: Int) {
        val next = PlaybackTuning.nudgeSpeed(player.playbackParameters.speed, direction)
        player.playbackParameters = player.playbackParameters.withSpeed(next)
        val key = player.currentMediaItem?.mediaId ?: return
        scope.launch {
            val id = feeds.podcastIdForEpisode(key) ?: return@launch
            val current = feeds.playbackForEpisode(key) ?: de.jenz.nahtlos.core.db.PodcastPlayback()
            feeds.savePlayback(id, current.copy(speed = next))
        }
    }

    fun skip(forward: Boolean) {
        val global = settings.read()
        val delta = (if (forward) global.skipForwardSec else -global.skipBackSec) * 1000L
        player.seekTo((player.currentPosition + delta).coerceAtLeast(0))
    }

    fun toggleSilence() {
        val enabled = !player.skipSilenceEnabled
        player.skipSilenceEnabled = enabled
        persist { it.copy(skipSilence = enabled) }
    }

    fun toggleBoost() {
        val enabled = boost?.enabled != true
        setBoost(enabled)
        persist { it.copy(boost = enabled) }
    }

    fun bumpTrim(intro: Boolean) {
        persist { current ->
            if (intro) current.copy(introSec = (current.introSec + 15).coerceAtMost(180))
            else current.copy(outroSec = (current.outroSec + 15).coerceAtMost(180))
        }
    }

    fun jumpChapter(direction: Int) {
        val key = player.currentMediaItem?.mediaId ?: return
        val position = player.currentPosition
        scope.launch {
            val target = feeds.chapterJump(key, position, direction) ?: return@launch
            val title = feeds.ensureChapters(key).firstOrNull { it.startMs == target }?.title
            withContext(Dispatchers.Main) {
                player.seekTo(target)
                if (!title.isNullOrBlank()) showChapter(title, null)
            }
        }
    }

    fun tickChapter() {
        val key = player.currentMediaItem?.mediaId ?: return
        val position = player.currentPosition
        scope.launch {
            val chapter = runCatching { feeds.ensureChapters(key) }.getOrDefault(emptyList())
                .lastOrNull { it.startMs <= position } ?: return@launch
            if (chapter.title == shownChapter) return@launch
            shownChapter = chapter.title
            withContext(Dispatchers.Main) { showChapter(chapter.title, chapter.imageUrl) }
        }
    }

    fun tickOutro() {
        val key = player.currentMediaItem?.mediaId ?: return
        if (!player.isPlaying) return
        scope.launch {
            val prefs = feeds.playbackForEpisode(key) ?: return@launch
            val duration = player.duration
            if (PlaybackTuning.outroReached(player.currentPosition, duration, prefs.outroSec)) {
                if (outroArmedFor == key) return@launch
                outroArmedFor = key
                withContext(Dispatchers.Main) { player.seekTo(duration) }
            }
        }
    }

    private fun persist(edit: (de.jenz.nahtlos.core.db.PodcastPlayback) -> de.jenz.nahtlos.core.db.PodcastPlayback) {
        val key = player.currentMediaItem?.mediaId ?: return
        scope.launch {
            val id = feeds.podcastIdForEpisode(key) ?: return@launch
            val current = feeds.playbackForEpisode(key) ?: de.jenz.nahtlos.core.db.PodcastPlayback()
            feeds.savePlayback(id, edit(current))
        }
    }

    private fun showChapter(title: String, imageUrl: String?) {
        val item = player.currentMediaItem ?: return
        val metadata = item.mediaMetadata.buildUpon().setSubtitle(title)
        if (!imageUrl.isNullOrBlank()) metadata.setArtworkUri(Uri.parse(imageUrl))
        val updated = item.buildUpon()
            .setMediaMetadata(metadata.build())
            .build()
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        val play = player.playWhenReady
        player.replaceMediaItem(index, updated)
        player.seekTo(index, position)
        player.playWhenReady = play
    }

    private fun setBoost(enabled: Boolean) {
        val session = player.audioSessionId
        if (session == 0) return
        val effect = boost ?: runCatching { LoudnessEnhancer(session) }.getOrNull()?.also { boost = it } ?: return
        runCatching {
            effect.setTargetGain(800)
            effect.enabled = enabled
        }
    }

    fun release() {
        runCatching { boost?.release() }
        boost = null
    }
}

fun chapterSubtitle(metadata: MediaMetadata, chapter: String?): MediaMetadata =
    if (chapter.isNullOrBlank()) metadata else metadata.buildUpon().setSubtitle(chapter).build()

fun MediaItem.withSubtitle(chapter: String?): MediaItem =
    buildUpon().setMediaMetadata(chapterSubtitle(mediaMetadata, chapter)).build()
