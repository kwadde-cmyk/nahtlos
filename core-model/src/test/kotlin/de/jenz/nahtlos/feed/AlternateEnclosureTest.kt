package de.jenz.nahtlos.feed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlternateEnclosureTest {
    private val parser = RssFeedParser()

    @Test
    fun youtubeAlternateDoesNotReplaceTheMp3() {
        val feed = parser.parse(NODESIGNAL)
        val episode = feed.episodes.single { it.guid == "9bf0fb90-9f6e-486c-bce6-1c0b8d500469" }
        assertEquals(MP3, episode.enclosureUrl)
        assertEquals("audio/mpeg", episode.mimeType)
        assertTrue(episode.alternates.none { it.url.contains("youtu.be") || it.type?.startsWith("video/") == true })
        assertEquals("https://chapters.example/e299.json", episode.chaptersUrl)
        assertEquals("https://transcript.example/e299.vtt", episode.transcripts.single().url)
        assertEquals(12_000L, episode.soundbites.single().startMs)
        assertTrue(episode.chapters.isNotEmpty() || episode.chaptersUrl != null)

        val recap = feed.episodes.single { it.title.contains("Oktober") }
        assertEquals("https://serve.podhome.fm/episode/recap.mp3", recap.enclosureUrl)
        assertNull(recap.chaptersUrl)
        assertTrue(recap.soundbites.isEmpty())
    }

    @Test
    fun audioAlternateIsOnlyAFallbackWhenTheEnclosureIsMissing() {
        val missing = parser.parse(AUDIO_ONLY_ALTERNATE).episodes.single()
        assertEquals("https://cdn.example/fallback.mp3", missing.enclosureUrl)
        assertEquals("audio/mpeg", missing.mimeType)

        val present = parser.parse(ENCLOSURE_WINS).episodes.single()
        assertEquals("https://cdn.example/low.mp3", present.enclosureUrl)
        assertTrue(present.alternates.any { it.url == "https://cdn.example/hq.mp3" })
    }

    @Test
    fun aVideoOnlyItemIsNotStoredAsPlayable() {
        assertTrue(parser.parse(VIDEO_ONLY).episodes.isEmpty())
        assertTrue(EnclosurePolicy.isWatchPage("https://youtu.be/XcYgUdvDxxA"))
        assertTrue(
            EnclosurePolicy.isPlayable(
                "https://serve.podhome.fm/episode/24263e26/6392821429290964189bf0fb90-9f6e-486c-bce6-1c0b8d500469.mp3",
                "audio/mpeg",
            ),
        )
    }

    private companion object {
        const val MP3 =
            "https://serve.podhome.fm/episode/24263e26-0aa8-432a-467a-08dc1370a35c/6392821429290964189bf0fb90-9f6e-486c-bce6-1c0b8d500469.mp3"

        val NODESIGNAL = """
            <rss version="2.0" xmlns:podcast="https://podcastindex.org/namespace/1.0">
              <channel>
                <title>Nodesignal</title>
                <item>
                  <title>Nodesignal-Techboost - E299 - Gemischter Satz</title>
                  <guid>9bf0fb90-9f6e-486c-bce6-1c0b8d500469</guid>
                  <enclosure url="$MP3" length="66876485" type="audio/mpeg"/>
                  <podcast:alternateEnclosure type="video/mp4">
                    <podcast:source uri="https://youtu.be/XcYgUdvDxxA"/>
                  </podcast:alternateEnclosure>
                  <podcast:chapters url="https://chapters.example/e299.json" type="application/json+chapters"/>
                  <podcast:transcript url="https://transcript.example/e299.vtt" type="text/vtt"/>
                  <podcast:soundbite startTime="12.0">Integration von Bark</podcast:soundbite>
                </item>
                <item>
                  <title>Nodesignal-Recap - Oktober 2026</title>
                  <guid>28923d23-3b4f-41c1-8f28-c94ded51e99a</guid>
                  <enclosure url="https://serve.podhome.fm/episode/recap.mp3" length="4988850" type="audio/mpeg"/>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val AUDIO_ONLY_ALTERNATE = """
            <rss version="2.0" xmlns:podcast="https://podcastindex.org/namespace/1.0">
              <channel>
                <title>Nur Ersatz</title>
                <item>
                  <title>Ohne Enclosure</title>
                  <podcast:alternateEnclosure type="audio/mpeg" bitrate="64000">
                    <podcast:source uri="https://cdn.example/fallback.mp3"/>
                  </podcast:alternateEnclosure>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val ENCLOSURE_WINS = """
            <rss version="2.0" xmlns:podcast="https://podcastindex.org/namespace/1.0">
              <channel>
                <title>Beides</title>
                <item>
                  <title>Folge</title>
                  <enclosure url="https://cdn.example/low.mp3" type="audio/mpeg"/>
                  <podcast:alternateEnclosure type="audio/mpeg" bitrate="128000">
                    <podcast:source uri="https://cdn.example/hq.mp3"/>
                  </podcast:alternateEnclosure>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val VIDEO_ONLY = """
            <rss version="2.0" xmlns:podcast="https://podcastindex.org/namespace/1.0">
              <channel>
                <title>Video</title>
                <item>
                  <title>Nur YouTube</title>
                  <podcast:alternateEnclosure type="video/mp4">
                    <podcast:source uri="https://youtu.be/XcYgUdvDxxA"/>
                  </podcast:alternateEnclosure>
                </item>
              </channel>
            </rss>
        """.trimIndent()
    }
}
