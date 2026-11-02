package de.jenz.nahtlos.opml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Robolectric would not catch the Android crash: it still uses the JDK
 * `javax.xml` parser. Android replaces that with DocumentBuilderFactoryImpl,
 * which throws on setFeature / setXIncludeAware. An instrumented test would
 * need a device or emulator, which this environment does not have.
 */

class OpmlTest {
    @Test
    fun readsNestedOutlinesAndIgnoresEntriesWithoutAFeed() {
        val xml = """
            <opml version="2.0">
              <head><title>Export</title></head>
              <body>
                <outline text="Ordner">
                  <outline text="Kabine" title="Kabine" type="rss" xmlUrl="https://example.com/feed.xml"/>
                </outline>
                <outline text="Nur ein Link" type="link" url="https://example.com"/>
              </body>
            </opml>
        """.trimIndent()
        val outlines = Opml.parse(xml)
        assertEquals(1, outlines.size)
        assertEquals("Kabine", outlines.single().title)
        assertEquals("https://example.com/feed.xml", outlines.single().feedUrl)
    }

    @Test
    fun writeThenParseRoundTrip() {
        val xml = Opml.write(
            "Nahtlos",
            listOf(OpmlOutline("Show", "https://cdn.example/show.xml")),
        )
        assertTrue(xml.contains("xmlUrl=\"https://cdn.example/show.xml\""))
        assertEquals("Show", Opml.parse(xml).single().title)
    }

    @Test
    fun pocketCastsExportWithNestedOutlinesAndOpml10() {
        val xml = javaClass.classLoader.getResource("opml/pocketcasts-export.xml")!!.readText()
        assertTrue(xml.contains("standalone='yes'"))
        assertTrue(xml.contains("<opml version=\"1.0\">"))
        val outlines = Opml.parse(xml)
        assertEquals(36, outlines.size)
        assertEquals(
            "https://anchor.fm/s/6b8bc084/podcast/rss",
            outlines.first { it.title == "Pleb's Taverne" }.feedUrl,
        )
        assertEquals(
            "Dezentral & Direkt – mit Joana Cotar und Roman Reher",
            outlines.first { it.feedUrl.contains("c44e77ad") }.title,
        )
        assertTrue(outlines.none { it.title == "feeds" })
        assertTrue(outlines.all { it.feedUrl.startsWith("http") })
    }
}
