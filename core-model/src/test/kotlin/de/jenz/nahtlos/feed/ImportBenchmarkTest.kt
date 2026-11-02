package de.jenz.nahtlos.feed

import de.jenz.nahtlos.opml.Opml
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Hopp- and Pocket-Casts exports are the two device fixtures (36 feeds each).
 * The recorded body is Einundzwanzig as fetched on 2026-11-02: 4_147_627 bytes, 682 items.
 * The other 35 Hopp feeds brought the set to 43.3 MB and 7_991 items. This test does not
 * download them again; it checks that a pull parse of the largest one stays bounded.
 */
class ImportBenchmarkTest {
    private val parser = RssFeedParser()

    @Test
    fun bothOpmlFixturesList36Feeds() {
        val hopp = Opml.parse(resource("opml/hopp-export.xml"))
        val pocket = Opml.parse(resource("opml/pocketcasts-export.xml"))
        assertEquals(36, hopp.size)
        assertEquals(36, pocket.size)
        assertTrue(hopp.any { it.feedUrl.contains("anchor.fm/s/d8d3c38") })
        assertTrue(pocket.none { it.title.equals("feeds", ignoreCase = true) })
    }

    @Test
    fun recordedEinundzwanzigFeedStaysWithinTheEpisodeCap() {
        val bytes = GZIPInputStream(javaClass.classLoader.getResourceAsStream("feeds/einundzwanzig.xml.gz")).use { it.readBytes() }
        assertTrue(bytes.size > 4_000_000, "recorded feed shrank unexpectedly: ${bytes.size}")

        val uncapped = measure("uncapped") {
            parser.parse(ByteArrayInputStream(bytes).reader(Charsets.UTF_8))
        }
        assertEquals("Einundzwanzig, der Bitcoin Podcast", uncapped.feed.title)
        assertTrue(uncapped.feed.episodes.size > RssFeedParser.MAX_EPISODES_PER_REFRESH, "items=${uncapped.feed.episodes.size}")
        assertTrue(uncapped.elapsedMs < 15_000, "uncapped parse took ${uncapped.elapsedMs} ms")

        val capped = measure("capped") {
            parser.parse(ByteArrayInputStream(bytes).reader(Charsets.UTF_8), RssFeedParser.MAX_EPISODES_PER_REFRESH)
        }
        assertEquals(RssFeedParser.MAX_EPISODES_PER_REFRESH, capped.feed.episodes.size)
        assertTrue(capped.feed.episodes.all { it.description.length <= 4_000 })
        assertTrue(capped.feed.episodes.all { it.enclosureUrl.startsWith("http") })
        assertTrue(capped.elapsedMs < 15_000, "capped parse took ${capped.elapsedMs} ms")
        // Retained set after GC, not total allocation. A DOM of this 4 MB feed is several times
        // the document and stays live until the parse returns; the pull parser should not.
        assertTrue(capped.retainedBytes < 32L * 1024 * 1024, "retained ${capped.retainedBytes} bytes")

        val newest = uncapped.feed.episodes
            .sortedByDescending { it.publishedAtEpochMs ?: Long.MIN_VALUE }
            .take(RssFeedParser.MAX_EPISODES_PER_REFRESH)
            .map { it.guid ?: it.enclosureUrl }
            .toSet()
        val kept = capped.feed.episodes.map { it.guid ?: it.enclosureUrl }.toSet()
        assertEquals(newest, kept)
        println(
            "einundzwanzig bytes=${bytes.size} items=${uncapped.feed.episodes.size} " +
                "uncappedMs=${uncapped.elapsedMs} cappedMs=${capped.elapsedMs} " +
                "cappedAlloc=${capped.allocatedBytes} cappedRetained=${capped.retainedBytes}",
        )
    }

    private fun resource(path: String): String =
        javaClass.classLoader.getResourceAsStream(path)!!.bufferedReader().use { it.readText() }

    private fun <T> measure(label: String, block: () -> T): Measured<T> {
        val thread = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        System.gc()
        val heapBefore = usedHeap()
        val before = if (thread.isThreadAllocatedMemorySupported) thread.currentThreadAllocatedBytes else -1L
        val started = System.nanoTime()
        val value = block()
        val elapsed = (System.nanoTime() - started) / 1_000_000
        val after = if (before >= 0) thread.currentThreadAllocatedBytes else before
        val allocated = if (before >= 0) (after - before).coerceAtLeast(0) else 0
        System.gc()
        val retained = (usedHeap() - heapBefore).coerceAtLeast(0)
        println("$label allocated=$allocated retained=$retained elapsedMs=$elapsed")
        return Measured(value, elapsed, allocated, retained)
    }

    private fun usedHeap(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private data class Measured<T>(
        val feed: T,
        val elapsedMs: Long,
        val allocatedBytes: Long,
        val retainedBytes: Long,
    )
}
