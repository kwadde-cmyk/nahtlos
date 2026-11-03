package de.jenz.nahtlos.core.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import de.jenz.nahtlos.core.AppLog
import de.jenz.nahtlos.playback.PlaybackTuning
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class PlayerSnapshot(
    val connected: Boolean = false,
    val episodeKey: String? = null,
    val title: String = "",
    val artist: String = "",
    val artworkUri: String? = null,
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = PlaybackTuning.DEFAULT_SPEED,
    val error: String? = null,
)

@Singleton
class PlayerConnection @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PlayerSnapshot())
    val state: StateFlow<PlayerSnapshot> = _state.asStateFlow()
    private var controller: MediaController? = null
    private var connecting = false
    private var pendingKey: String? = null
    private var ticker: Job? = null
    private var sleepJob: Job? = null
    private var pauseAtEnd = false

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publish()
        }

        override fun onPlayerError(error: PlaybackException) {
            val message = error.message ?: "Wiedergabe fehlgeschlagen"
            AppLog.record("Player", message, error)
            _state.update { it.copy(error = message, buffering = false) }
        }
    }

    fun connect() {
        if (controller != null || connecting) return
        connecting = true
        val token = SessionToken(context, ComponentName(context, PodcastMediaLibraryService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        scope.launch {
            delay(8_000)
            if (controller == null) {
                connecting = false
                val message = "Player antwortet nicht. Unter Android 13 die Benachrichtigung erlauben und es erneut versuchen."
                AppLog.record("Player", message)
                _state.update { it.copy(error = message, buffering = false) }
            }
        }
        future.addListener({
            connecting = false
            val connected = runCatching { future.get() }.getOrElse { error ->
                val message = error.message ?: "Player nicht erreichbar"
                AppLog.record("Player", message, error)
                _state.update { it.copy(error = message, buffering = false) }
                return@addListener
            }
            controller = connected
            connected.addListener(listener)
            publish()
            startTicker()
            pendingKey?.let { key ->
                pendingKey = null
                startPlayback(connected, key)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun play(episodeKey: String) {
        _state.update { it.copy(episodeKey = episodeKey, error = null, buffering = true) }
        val current = controller
        if (current == null) {
            pendingKey = episodeKey
            connect()
            return
        }
        startPlayback(current, episodeKey)
    }

    private fun startPlayback(current: MediaController, episodeKey: String) {
        try {
            if (current.currentMediaItem?.mediaId == episodeKey && current.playbackState != Player.STATE_IDLE) {
                current.play()
                return
            }
            current.setMediaItem(MediaItem.Builder().setMediaId(episodeKey).build())
            current.prepare()
            current.play()
        } catch (error: Throwable) {
            val message = error.message ?: "Wiedergabe konnte nicht starten"
            AppLog.record("Player", message, error)
            _state.update { it.copy(episodeKey = episodeKey, error = message, buffering = false) }
        }
    }

    fun toggle() {
        val current = controller ?: return
        if (current.isPlaying) current.pause() else current.play()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs.coerceAtLeast(0))
    }

    fun seekBack() {
        controller?.seekBack()
    }

    fun seekForward() {
        controller?.seekForward()
    }

    fun nudgeSpeed(direction: Int) = command(if (direction < 0) SessionActions.SPEED_DOWN else SessionActions.SPEED_UP)

    fun chapter(direction: Int) = command(if (direction < 0) SessionActions.CHAPTER_PREV else SessionActions.CHAPTER_NEXT)

    fun toggleSilence() = command(SessionActions.SILENCE)

    fun toggleBoost() = command(SessionActions.BOOST)

    fun moreIntro() = command(SessionActions.INTRO)

    fun moreOutro() = command(SessionActions.OUTRO)

    /** Phone only. The car UI does not offer a sleep timer. */
    fun sleepForMinutes(minutes: Int) {
        sleepJob?.cancel()
        pauseAtEnd = false
        if (minutes <= 0) return
        sleepJob = scope.launch {
            delay(minutes * 60_000L)
            controller?.pause()
        }
    }

    fun sleepAtEnd() {
        sleepJob?.cancel()
        pauseAtEnd = true
    }

    fun clearSleep() {
        sleepJob?.cancel()
        pauseAtEnd = false
    }

    private fun command(action: String) {
        val current = controller ?: return
        current.sendCustomCommand(SessionCommand(action, android.os.Bundle.EMPTY), android.os.Bundle.EMPTY)
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                publish()
                delay(400)
            }
        }
    }

    private fun publish() {
        val current = controller ?: return
        val metadata: MediaMetadata? = current.mediaMetadata
        val duration = current.duration.takeIf { it > 0 } ?: 0L
        val position = current.currentPosition.coerceAtLeast(0)
        if (pauseAtEnd && duration > 0 && position >= duration - 1_500) {
            pauseAtEnd = false
            current.pause()
        }
        val failure = current.playerError?.let { it.message ?: "Wiedergabe fehlgeschlagen" }
        _state.update {
            val playing = current.isPlaying || current.playbackState == Player.STATE_READY
            it.copy(
                connected = true,
                episodeKey = current.currentMediaItem?.mediaId ?: it.episodeKey,
                title = metadata?.title?.toString().orEmpty().ifBlank { it.title },
                artist = metadata?.artist?.toString().orEmpty().ifBlank { it.artist },
                artworkUri = metadata?.artworkUri?.toString() ?: it.artworkUri,
                isPlaying = current.isPlaying,
                buffering = current.playbackState == Player.STATE_BUFFERING,
                positionMs = position,
                durationMs = duration,
                speed = current.playbackParameters.speed,
                error = failure ?: if (playing) null else it.error,
            )
        }
    }
}
