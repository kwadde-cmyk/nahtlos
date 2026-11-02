package de.jenz.nahtlos.core.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
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

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publish()
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.update { it.copy(error = error.message ?: "Wiedergabe fehlgeschlagen") }
        }
    }

    fun connect() {
        if (controller != null || connecting) return
        connecting = true
        val token = SessionToken(context, ComponentName(context, PodcastMediaLibraryService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            connecting = false
            val connected = runCatching { future.get() }.getOrElse { error ->
                _state.update { it.copy(error = error.message ?: "Player nicht erreichbar") }
                return@addListener
            }
            controller = connected
            connected.addListener(listener)
            publish()
            startTicker()
            pendingKey?.let { key ->
                pendingKey = null
                play(key)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun play(episodeKey: String) {
        val current = controller
        if (current == null) {
            pendingKey = episodeKey
            connect()
            return
        }
        _state.update { it.copy(error = null) }
        if (current.currentMediaItem?.mediaId == episodeKey && current.playbackState != Player.STATE_IDLE) {
            current.play()
            return
        }
        current.setMediaItem(MediaItem.Builder().setMediaId(episodeKey).build())
        current.prepare()
        current.play()
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
        _state.update {
            it.copy(
                connected = true,
                episodeKey = current.currentMediaItem?.mediaId,
                title = metadata?.title?.toString().orEmpty(),
                artist = metadata?.artist?.toString().orEmpty(),
                artworkUri = metadata?.artworkUri?.toString(),
                isPlaying = current.isPlaying,
                buffering = current.playbackState == Player.STATE_BUFFERING,
                positionMs = current.currentPosition.coerceAtLeast(0),
                durationMs = duration,
            )
        }
    }
}
