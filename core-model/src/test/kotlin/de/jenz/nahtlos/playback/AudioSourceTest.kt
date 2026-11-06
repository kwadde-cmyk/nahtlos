package de.jenz.nahtlos.playback

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioSourceTest {
    @Test
    fun anMpegMimeDoesNotHideAnHlsAddress() {
        assertEquals(AudioSource.HLS, AudioSource.mediaMime("audio/mpeg", "https://cdn.example/episode.m3u8?token=1"))
        assertEquals(AudioSource.HLS, AudioSource.mediaMime("application/vnd.apple.mpegurl", "https://cdn.example/live"))
        assertEquals("audio/mpeg", AudioSource.mediaMime("audio/mpeg", "https://cdn.example/episode.mp3"))
        assertEquals(null, AudioSource.mediaMime(null, "https://cdn.example/episode"))
    }

    @Test
    fun htmlAndPlaylistsAreNotStoredAsAudio() {
        assertFalse(AudioSource.shouldStore("text/html", "<html>blocked</html>".toByteArray()))
        assertFalse(AudioSource.shouldStore("audio/mpeg", "#EXTM3U\n#EXTINF:1,a\n".toByteArray()))
        assertFalse(AudioSource.shouldStore("application/vnd.apple.mpegurl", "#EXTM3U\n".toByteArray()))
        assertTrue(AudioSource.shouldStore("application/octet-stream", "ID3title".toByteArray()))
        assertTrue(AudioSource.shouldStore("audio/mpeg", byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0)))
    }

    @Test
    fun aSavedHtmlPageIsNotAPlayableDownload() {
        val file = File.createTempFile("nahtlos", ".bin")
        file.writeText("<!DOCTYPE html><html><body>no audio</body></html>")
        assertFalse(AudioSource.isProgressiveAudio(file))
        file.writeBytes(byteArrayOf(0, 0, 0, 24, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte()))
        assertTrue(AudioSource.isProgressiveAudio(file))
        file.delete()
    }
}
