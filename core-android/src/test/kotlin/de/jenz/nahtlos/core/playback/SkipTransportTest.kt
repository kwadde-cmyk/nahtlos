package de.jenz.nahtlos.core.playback

import android.view.KeyEvent
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkipTransportTest {
    @Test
    fun headphoneKeysMapToTheConfiguredSkip() {
        assertEquals(1, SkipTransport.direction(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(1, SkipTransport.direction(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
        assertEquals(1, SkipTransport.direction(KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD))
        assertEquals(-1, SkipTransport.direction(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        assertEquals(-1, SkipTransport.direction(KeyEvent.KEYCODE_MEDIA_REWIND))
        assertEquals(-1, SkipTransport.direction(KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD))
        assertNull(SkipTransport.direction(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals(130_000L, SkipTransport.skippedPosition(100_000, 600_000, 30_000))
        assertEquals(90_000L, SkipTransport.skippedPosition(100_000, 600_000, -10_000))
        assertEquals(0L, SkipTransport.skippedPosition(5_000, 600_000, -30_000))
        assertEquals(600_000L, SkipTransport.skippedPosition(590_000, 600_000, 30_000))
    }

    @Test
    fun nextAndPreviousSeekByTheSkipEvenWhenThePlayerHasNoNextItem() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val exo = ExoPlayer.Builder(context).build()
        val recording = RecordingPlayer(exo)
        val transport = SkipTransport(recording) { forward -> if (forward) 30_000 else 15_000 }
        assertFalse(exo.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        SkipTransport.transportCommands.forEach { command ->
            assertTrue("Befehl $command fehlt", transport.isCommandAvailable(command))
            assertTrue(transport.availableCommands.contains(command))
        }
        transport.seekToNext()
        assertEquals(150_000L, recording.sought)
        transport.seekToPrevious()
        assertEquals(105_000L, recording.sought)
        transport.seekForward()
        assertEquals(150_000L, recording.sought)
        transport.seekBack()
        assertEquals(105_000L, recording.sought)
        transport.seekToNextMediaItem()
        assertEquals(150_000L, recording.sought)
        transport.seekToPreviousMediaItem()
        assertEquals(105_000L, recording.sought)

        var announced: Player.Commands? = null
        var seenState = -1
        var playing: Boolean? = null
        var itemSeen = false
        val listener = object : Player.Listener {
            override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                announced = availableCommands
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                seenState = playbackState
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                itemSeen = true
            }
        }
        transport.addListener(listener)
        val playPause = Player.Commands.Builder().add(Player.COMMAND_PLAY_PAUSE).build()
        recording.fire(playPause)
        assertEquals(Player.STATE_READY, seenState)
        assertEquals(true, playing)
        assertTrue(itemSeen)
        assertTrue(announced!!.contains(Player.COMMAND_PLAY_PAUSE))
        SkipTransport.transportCommands.forEach { command ->
            assertTrue(announced!!.contains(command))
        }
        exo.release()
    }

    private class RecordingPlayer(inner: Player) : ForwardingPlayer(inner) {
        var sought: Long = -1
        private val listeners = mutableListOf<Player.Listener>()

        override fun getCurrentPosition(): Long = 120_000

        override fun getDuration(): Long = 600_000

        override fun seekTo(positionMs: Long) {
            sought = positionMs
        }

        override fun addListener(listener: Player.Listener) {
            listeners += listener
        }

        override fun removeListener(listener: Player.Listener) {
            listeners -= listener
        }

        fun fire(commands: Player.Commands) {
            val item = androidx.media3.common.MediaItem.Builder().setMediaId("episode").build()
            listeners.forEach { listener ->
                listener.onPlaybackStateChanged(Player.STATE_READY)
                listener.onIsPlayingChanged(true)
                listener.onMediaItemTransition(item, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
                listener.onAvailableCommandsChanged(commands)
            }
        }
    }
}
