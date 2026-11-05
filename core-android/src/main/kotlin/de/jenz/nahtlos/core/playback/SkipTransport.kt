package de.jenz.nahtlos.core.playback

import android.view.KeyEvent
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import java.util.IdentityHashMap

/**
 * Headset, Bluetooth-AVRCP und Lenkrad schicken Next/Previous oder Fast-Forward/Rewind.
 * Beides soll den eingestellten Skip sein, nicht die nächste Mediendatei.
 *
 * AVRCP sendet Next nur, wenn COMMAND_SEEK_TO_NEXT verfügbar ist. Deshalb muss das
 * sowohl in [getAvailableCommands] als auch in [isCommandAvailable] stehen, und
 * spätere [Player.Listener.onAvailableCommandsChanged]-Events dürfen die Befehle
 * nicht wieder streichen. [MediaSession.Callback.onMediaButtonEvent] bleibt unangetastet,
 * damit Media3 die Tasten selbst zustellt.
 */
class SkipTransport(
    private val inner: Player,
    private val skipMs: (forward: Boolean) -> Long,
) : ForwardingPlayer(inner) {
    private val listeners = IdentityHashMap<Player.Listener, Player.Listener>()

    override fun seekToNext() = skip(forward = true)

    override fun seekToNextMediaItem() = skip(forward = true)

    override fun seekForward() = skip(forward = true)

    override fun seekToPrevious() = skip(forward = false)

    override fun seekToPreviousMediaItem() = skip(forward = false)

    override fun seekBack() = skip(forward = false)

    override fun getSeekForwardIncrement(): Long = skipMs(true).coerceAtLeast(0)

    override fun getSeekBackIncrement(): Long = skipMs(false).coerceAtLeast(0)

    override fun isCommandAvailable(command: Int): Boolean =
        command in transportCommands || super.isCommandAvailable(command)

    override fun getAvailableCommands(): Player.Commands = withTransport(super.getAvailableCommands())

    override fun addListener(listener: Player.Listener) {
        val wrapped = object : Player.Listener by listener {
            override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                listener.onAvailableCommandsChanged(withTransport(availableCommands))
            }
        }
        listeners[listener] = wrapped
        super.addListener(wrapped)
    }

    override fun removeListener(listener: Player.Listener) {
        super.removeListener(listeners.remove(listener) ?: listener)
    }

    private fun skip(forward: Boolean) {
        val delta = if (forward) skipMs(true) else -skipMs(false)
        inner.seekTo(skippedPosition(inner.currentPosition, inner.duration, delta))
    }

    companion object {
        val transportCommands: Set<Int> = setOf(
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_SEEK_BACK,
        )

        fun withTransport(commands: Player.Commands): Player.Commands {
            val builder = commands.buildUpon()
            transportCommands.forEach { builder.add(it) }
            return builder.build()
        }

        /** +1 vor, -1 zurück, null wenn die Taste kein Skip ist. */
        fun direction(keyCode: Int): Int? = when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
            -> 1
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,
            -> -1
            else -> null
        }

        fun skippedPosition(currentMs: Long, durationMs: Long, deltaMs: Long): Long {
            val target = (currentMs.coerceAtLeast(0) + deltaMs).coerceAtLeast(0)
            return if (durationMs > 0) target.coerceAtMost(durationMs) else target
        }
    }
}
