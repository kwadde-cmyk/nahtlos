package de.jenz.nahtlos.core.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ApplicationProvider
import de.jenz.nahtlos.core.sync.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackSourceTest {
    @Test
    fun fileUriOpensThroughThePlayerDataSource() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.filesDir, "downloads/episode.bin")
        file.parentFile?.mkdirs()
        val payload = "nahtlos-local".toByteArray()
        file.writeBytes(payload)
        val cache = PlaybackCache(context, SettingsStore(context))
        val source = cache.dataSourceFactory().createDataSource()
        val opened = source.open(DataSpec(Uri.fromFile(file)))
        assertTrue(opened == C.LENGTH_UNSET.toLong() || opened == payload.size.toLong())
        val buffer = ByteArray(payload.size)
        var filled = 0
        while (filled < buffer.size) {
            val read = source.read(buffer, filled, buffer.size - filled)
            if (read == C.RESULT_END_OF_INPUT) break
            filled += read
        }
        source.close()
        assertEquals(payload.size, filled)
        assertEquals("nahtlos-local", buffer.decodeToString())
    }

    @Test
    fun missingDownloadFallsBackToTheRemoteUrl() {
        val missing = File("/tmp/nahtlos-does-not-exist.bin")
        val uri = playbackUri(missing, "https://cdn.example/episode.mp3")
        assertEquals("https", uri.scheme)
        assertEquals("https://cdn.example/episode.mp3", uri.toString())
        val present = File.createTempFile("nahtlos", ".bin")
        present.writeBytes("ID3".toByteArray())
        val local = playbackUri(present, "https://cdn.example/episode.mp3")
        assertEquals("file", local.scheme)
        val html = File.createTempFile("nahtlos-html", ".bin")
        html.writeText("<html>no audio</html>")
        val remote = playbackUri(html, "https://cdn.example/episode.m3u8")
        assertEquals("https", remote.scheme)
        present.delete()
        html.delete()
    }
}
