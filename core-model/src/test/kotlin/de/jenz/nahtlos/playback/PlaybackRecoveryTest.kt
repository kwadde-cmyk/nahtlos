package de.jenz.nahtlos.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackRecoveryTest {
    @Test
    fun redirectsToTheRealFileAreRetriedOnce() {
        val action = PlaybackRecovery.action(
            kind = HeaderKind.AUDIO,
            mime = "audio/mpeg",
            currentUrl = "https://serve.example/episode/a.mp3",
            finalUrl = "https://assets.example/a.mp3",
        )
        assertEquals(PlaybackRecovery.REPLAY, action)
        assertEquals("audio/mpeg", PlaybackRecovery.replayMime(HeaderKind.AUDIO, "audio/mpeg"))
    }

    @Test
    fun theSameAudioAddressIsNotLooped() {
        val url = "https://assets.example/a.mp3"
        assertEquals(
            PlaybackRecovery.SKIP,
            PlaybackRecovery.action(HeaderKind.AUDIO, "audio/mpeg", url, url),
        )
    }

    @Test
    fun htmlIsSkippedAndPlaylistsAreReplayedAsHls() {
        assertEquals(
            PlaybackRecovery.SKIP,
            PlaybackRecovery.action(HeaderKind.MARKUP, null, "https://cdn/a", "https://cdn/a"),
        )
        assertEquals(
            PlaybackRecovery.REPLAY,
            PlaybackRecovery.action(HeaderKind.PLAYLIST, AudioSource.HLS, "https://cdn/a.mp3", "https://cdn/a.m3u8"),
        )
        assertEquals(AudioSource.HLS, PlaybackRecovery.replayMime(HeaderKind.PLAYLIST, AudioSource.HLS))
    }
}
