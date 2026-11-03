package de.jenz.nahtlos.feed

import de.jenz.nahtlos.model.FeedParseException
import de.jenz.nahtlos.sync.GpodderMapping
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedParserTest {
    private val parser = RssFeedParser()

    @Test
    fun parsesPodcasting20FieldsAndInlineChapters() {
        val feed = parser.parse(SAMPLE)
        assertEquals("e7b3c1d0-1111-2222-3333-444455556666", feed.podcastGuid)
        assertEquals("Kabine", feed.title)
        assertEquals("Ada", feed.persons.single().name)
        assertEquals("host", feed.persons.single().role)
        assertEquals("https://cdn.example/cover.jpg", feed.imageUrl)
        assertTrue(feed.description.contains("Ampersand & More"))

        val episode = feed.episodes.single()
        assertEquals("ep-1", episode.guid)
        assertEquals("https://cdn.example/1.mp3", episode.enclosureUrl)
        assertEquals("audio/mpeg", episode.mimeType)
        assertEquals(3_723_000L, episode.durationMs)
        assertEquals("https://cdn.example/1.vtt", episode.transcripts.single().url)
        assertEquals("text/vtt", episode.transcripts.single().type)
        assertEquals("de", episode.transcripts.single().language)
        assertEquals("https://cdn.example/1.json", episode.chaptersUrl)
        assertEquals("application/json+chapters", episode.chaptersType)
        assertEquals(listOf(0L, 65_500L), episode.chapters.map { it.startMs })
        assertEquals(listOf("Intro", "Thema"), episode.chapters.map { it.title })
        assertEquals("Bea", episode.persons.single().name)
        assertEquals("guest", episode.persons.single().role)
        assertTrue(episode.description.contains("Show notes"))
        assertTrue(episode.publishedAtEpochMs != null && episode.publishedAtEpochMs!! > 0)

        val key = EpisodeKeys.episodeKey(feed.podcastGuid, "https://example.com/feed.xml", episode.guid, episode.enclosureUrl)
        assertEquals("${feed.podcastGuid}|ep-1", key)
    }

    @Test
    fun episodeKeyIgnoresEnclosureWhenGuidExists() {
        val first = EpisodeKeys.episodeKey("pod", "https://ex/feed", "ep", "https://cdn/a.mp3?token=1")
        val second = EpisodeKeys.episodeKey("pod", "https://ex/feed", "ep", "https://cdn/b.mp3?token=2")
        assertEquals(first, second)
    }

    @Test
    fun episodeKeyIsStableWhenGuidIsMissing() {
        val feed = "https://example.com/feed/"
        val enclosure = "https://cdn.example/only.mp3"
        val a = EpisodeKeys.episodeKey(null, feed, null, enclosure)
        val b = EpisodeKeys.episodeKey(null, feed.trimEnd('/'), "  ", enclosure)
        assertEquals(a, b)
        assertTrue(a.startsWith("feed:"))
        assertTrue(a.contains("|url:"))
    }

    @Test
    fun stopsEarlyWhenANewestFirstFeedLeavesTheHorizon() {
        val now = RssFeedParser.parseDate("2026-11-02T08:00:00Z")!!
        val cutoff = now - 14L * 86_400_000L
        val xml = buildString {
            append("<rss version=\"2.0\"><channel><title>Lang</title>")
            append(item("heute", "https://ex/new.mp3", "2026-11-02T08:00:00Z"))
            append(item("gestern", "https://ex/y.mp3", "2026-11-01T08:00:00Z"))
            append(item("alt", "https://ex/old.mp3", "2024-06-01T00:00:00Z"))
            append(item("noch-aelter", "https://ex/older.mp3", "2024-01-01T00:00:00Z"))
            append("</channel></rss>")
        }
        val feed = parser.parse(xml, publishedAfterEpochMs = cutoff)
        assertEquals(setOf("heute", "gestern"), feed.episodes.map { it.title }.toSet())
    }

    @Test
    fun oldestFirstFeedStillKeepsEpisodesInsideTheHorizon() {
        val now = RssFeedParser.parseDate("2026-11-02T08:00:00Z")!!
        val cutoff = now - 14L * 86_400_000L
        val xml = buildString {
            append("<rss version=\"2.0\"><channel><title>Lang</title>")
            append(item("alt", "https://ex/old.mp3", "2024-01-01T00:00:00Z"))
            append(item("heute", "https://ex/new.mp3", "2026-11-02T08:00:00Z"))
            append("</channel></rss>")
        }
        val feed = parser.parse(xml, publishedAfterEpochMs = cutoff)
        assertEquals(listOf("heute"), feed.episodes.map { it.title })
    }

    @Test
    fun keepsOnlyTheNewestEpisodesWhenCapped() {
        val xml = buildString {
            append("<rss version=\"2.0\"><channel><title>Lang</title>")
            append(item("alt", "https://ex/old.mp3", "Mon, 01 Jan 2024 00:00:00 GMT"))
            append(item("neu", "https://ex/new.mp3", "Mon, 01 Jun 2026 00:00:00 GMT"))
            append(item("mitte", "https://ex/mid.mp3", "Wed, 01 Jan 2025 00:00:00 GMT"))
            append("</channel></rss>")
        }
        val feed = parser.parse(xml, maxEpisodes = 2)
        assertEquals(setOf("neu", "mitte"), feed.episodes.map { it.title }.toSet())
    }

    @Test
    fun repairsABareAmpersandWithoutCopyingTheDocument() {
        val xml = """
            <rss version="2.0"><channel>
              <title>AT&T Daily</title>
              <description>Rock &amp; Roll</description>
              <item>
                <title>Folge</title>
                <enclosure url="https://cdn.example/at.mp3" type="audio/mpeg"/>
              </item>
            </channel></rss>
        """.trimIndent()
        val feed = parser.parse(xml.reader(), maxEpisodes = 10)
        assertEquals("AT&T Daily", feed.title)
        assertTrue(feed.description.contains("Rock & Roll"))
        assertEquals(1, feed.episodes.size)
    }

    @Test
    fun skipsItemsWithoutEnclosure() {
        val xml = """
            <rss version="2.0"><channel>
              <title>Nur Text</title>
              <item><title>Kein Audio</title><guid>x</guid></item>
            </channel></rss>
        """.trimIndent()
        assertEquals(0, parser.parse(xml).episodes.size)
    }

    @Test
    fun rejectsXmlWithoutAChannel() {
        assertFailsWith<FeedParseException> { parser.parse("<rss version=\"2.0\"></rss>") }
    }

    @Test
    fun parsesChapterJson() {
        val chapters = ChapterJsonParser().parse(
            """
            {"version":"1.2.0","chapters":[
              {"startTime":65.5,"title":"Thema","url":"https://ex/t"},
              {"startTime":0,"title":"Intro"}
            ]}
            """.trimIndent(),
        )
        assertEquals(listOf(0L, 65_500L), chapters.map { it.startMs })
        assertEquals("https://ex/t", chapters[1].url)
    }

    @Test
    fun durationAndClockFormats() {
        assertEquals(3_723_000L, RssFeedParser.parseDuration("1:02:03"))
        assertEquals(125_000L, RssFeedParser.parseDuration("125"))
        assertEquals(90_000L, RssFeedParser.parseDuration("1:30"))
        assertEquals(12_500L, RssFeedParser.parseClock("12.5"))
        assertNull(RssFeedParser.parseDuration(""))
        assertNull(RssFeedParser.parseDate("not a date"))
    }

    @Test
    fun gpodderSecondsAreAProjectionNotTheStoredUnit() {
        assertEquals(12L, GpodderMapping.toSeconds(12_500))
        assertEquals(12_000L, GpodderMapping.fromSeconds(12))
        assertEquals("play", GpodderMapping.ACTION_PLAY)
    }

    private companion object {
        fun item(title: String, url: String, date: String) =
            """<item><title>$title</title><pubDate>$date</pubDate><enclosure url="$url" type="audio/mpeg"/></item>"""

        val SAMPLE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"
                 xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"
                 xmlns:podcast="https://podcastindex.org/namespace/1.0"
                 xmlns:psc="http://podlove.org/simple-chapters"
                 xmlns:content="http://purl.org/rss/1.0/modules/content/">
              <channel>
                <title>Kabine</title>
                <link>https://example.com</link>
                <description>Ampersand &amp; More</description>
                <podcast:guid>e7b3c1d0-1111-2222-3333-444455556666</podcast:guid>
                <itunes:author>Ada</itunes:author>
                <itunes:image href="https://cdn.example/cover.jpg"/>
                <podcast:person role="host" href="https://ada.example" img="https://cdn.example/ada.jpg">Ada</podcast:person>
                <item>
                  <title>Folge 1</title>
                  <guid isPermaLink="false">ep-1</guid>
                  <pubDate>Mon, 02 Nov 2026 08:00:00 GMT</pubDate>
                  <itunes:duration>1:02:03</itunes:duration>
                  <content:encoded><![CDATA[<p>Show&nbsp;notes</p>]]></content:encoded>
                  <enclosure url="https://cdn.example/1.mp3" type="audio/mpeg" length="123"/>
                  <podcast:transcript url="https://cdn.example/1.vtt" type="text/vtt" language="de"/>
                  <podcast:chapters url="https://cdn.example/1.json" type="application/json+chapters"/>
                  <psc:chapters version="1.2">
                    <psc:chapter start="0:00:00.000" title="Intro"/>
                    <psc:chapter start="0:01:05.500" title="Thema"/>
                  </psc:chapters>
                  <podcast:person role="guest" href="https://bea.example">Bea</podcast:person>
                </item>
              </channel>
            </rss>
        """.trimIndent()
    }
}
