package de.jenz.nahtlos.opml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
}
