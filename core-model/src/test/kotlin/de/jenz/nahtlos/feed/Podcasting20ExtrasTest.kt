package de.jenz.nahtlos.feed

import de.jenz.nahtlos.playback.PlaybackTuning
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Podcasting20ExtrasTest {
    private val parser = RssFeedParser()

    @Test
    fun readsFundingValueSoundbiteSeasonLiveAndAlternateEnclosure() {
        val feed = parser.parse(SAMPLE)
        assertTrue(feed.locked)
        assertEquals("https://example.com/fund", feed.funding.single().url)
        assertEquals("Ada", feed.value.single().name)
        assertEquals("03abc", feed.value.single().address)

        val episode = feed.episodes.single { it.title == "Folge 1" }
        assertEquals(2, episode.season)
        assertEquals("5", episode.episodeNumber)
        assertEquals("https://cdn.example/hq.mp3", episode.enclosureUrl)
        assertEquals(73_000L, episode.soundbites.single().startMs)
        assertEquals("Bob", episode.value.single().name)
        assertEquals(true, episode.value.single().fee)
        assertEquals(696969L, episode.value.single().customKey)
        assertEquals("ff", episode.value.single().customValue)
        val window = episode.valueTimeSplits.single()
        assertEquals(60_000L, window.startMs)
        assertEquals(95, window.remotePercentage)
        assertEquals("Gast", window.recipients.single().name)
        assertTrue(episode.descriptionHtml.contains("<p>"))

        val live = feed.episodes.single { it.live }
        assertEquals("Live-Runde", live.title)
        assertEquals("https://cdn.example/live.mp3", live.enclosureUrl)
        assertEquals("live", live.liveStatus)
    }

    @Test
    fun readsPodrollTrailerLocationAndPlainTranscript() {
        val feed = parser.parse(PODROLL)
        val remote = feed.podroll.single()
        assertEquals("https://example.com/other.xml", remote.feedUrl)
        assertEquals("Andere Show", remote.title)
        assertEquals("Berlin", feed.location?.name)
        assertEquals("https://example.com/trailer.mp3", feed.trailers.single().url)
        assertEquals("CC", feed.license?.name)
        assertEquals("hello", feed.txt.single().value)
        val text = TranscriptParser.parse("Nur Text", "text/plain")
        assertEquals("Nur Text", text.single().text)
    }

    @Test
    fun parsesVttSrtAndJsonTranscripts() {
        val vtt = TranscriptParser.parse(
            """
            WEBVTT

            00:00:01.000 --> 00:00:04.000
            Hallo <b>Welt</b>

            00:01:05.500 --> 00:01:08.000
            Thema
            """.trimIndent(),
            "text/vtt",
        )
        assertEquals(listOf(1_000L, 65_500L), vtt.map { it.startMs })
        assertEquals("Hallo Welt", vtt.first().text)

        val json = TranscriptParser.parse(
            """{"segments":[{"startTime":1.5,"endTime":3.0,"body":"Eins"}]}""",
            "application/json",
        )
        assertEquals(1_500L, json.single().startMs)
        assertEquals("Eins", json.single().text)
    }

    @Test
    fun speedStepsStayInsideTheRange() {
        assertEquals(1.1f, PlaybackTuning.nudgeSpeed(1.0f, 1))
        assertEquals(PlaybackTuning.MIN_SPEED, PlaybackTuning.nudgeSpeed(0.5f, -1))
        assertEquals(PlaybackTuning.MAX_SPEED, PlaybackTuning.nudgeSpeed(2.96f, 1))
        assertEquals(15_000L, PlaybackTuning.introStartMs(0, 15))
        assertNull(PlaybackTuning.introStartMs(8_000, 15))
        assertTrue(PlaybackTuning.outroReached(100_000, 120_000, 30))
    }

    private companion object {
        val SAMPLE = """
            <rss version="2.0" xmlns:podcast="https://podcastindex.org/namespace/1.0" xmlns:content="http://purl.org/rss/1.0/modules/content/">
              <channel>
                <title>Kabine</title>
                <podcast:locked>yes</podcast:locked>
                <podcast:funding url="https://example.com/fund">Spenden</podcast:funding>
                <podcast:value type="lightning" method="keysend">
                  <podcast:valueRecipient name="Ada" type="node" address="03abc" split="100"/>
                </podcast:value>
                <item>
                  <title>Folge 1</title>
                  <podcast:season>2</podcast:season>
                  <podcast:episode>5</podcast:episode>
                  <enclosure url="https://cdn.example/low.mp3" type="audio/mpeg"/>
                  <podcast:alternateEnclosure type="audio/mpeg" bitrate="128000">
                    <podcast:source uri="https://cdn.example/hq.mp3"/>
                  </podcast:alternateEnclosure>
                  <podcast:soundbite startTime="73.0" duration="30.0">Ausschnitt</podcast:soundbite>
                  <content:encoded><![CDATA[<p>Notizen</p>]]></content:encoded>
                  <podcast:value type="lightning" method="keysend">
                    <podcast:valueRecipient name="Bob" type="node" address="02def" split="40" fee="true" customKey="696969" customValue="ff"/>
                  </podcast:value>
                  <podcast:valueTimeSplit startTime="60" duration="30" remotePercentage="95">
                    <podcast:valueRecipient name="Gast" type="lnaddress" address="gast@example.com" split="100"/>
                  </podcast:valueTimeSplit>
                </item>
                <podcast:liveItem status="live">
                  <title>Live-Runde</title>
                  <enclosure url="https://cdn.example/live.mp3" type="audio/mpeg"/>
                </podcast:liveItem>
              </channel>
            </rss>
        """.trimIndent()

        val PODROLL = """
            <rss version="2.0" xmlns:podcast="https://podcastindex.org/namespace/1.0">
              <channel>
                <title>Empfehler</title>
                <podcast:location geo="52.5,13.4">Berlin</podcast:location>
                <podcast:license url="https://creativecommons.org/licenses/by/4.0/">CC</podcast:license>
                <podcast:txt purpose="verify">hello</podcast:txt>
                <podcast:trailer url="https://example.com/trailer.mp3" season="1">Vorspann</podcast:trailer>
                <podcast:podroll>
                  <podcast:remoteItem feedGuid="guid-1" feedUrl="https://example.com/other.xml" title="Andere Show"/>
                </podcast:podroll>
                <item>
                  <title>Eins</title>
                  <enclosure url="https://cdn.example/a.mp3" type="audio/mpeg"/>
                </item>
              </channel>
            </rss>
        """.trimIndent()
    }
}
