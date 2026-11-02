package de.jenz.nahtlos.playlist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaylistEditorTest {
    private val day = 86_400_000L

    private val episodes = listOf(
        candidate("a", publishedAt = day, title = "Alt"),
        candidate("b", publishedAt = day * 3, title = "Neu"),
        candidate("c", publishedAt = day * 2, title = "Mitte"),
        candidate("old", publishedAt = day / 2, title = "Vorher"),
    )

    @Test
    fun sinceDateKeepsOnlyLaterEpisodesAndSortsOldestFirst() {
        val playlist = PlaylistEditor.generate(episodes, sinceEpochMs = day, PlaylistSort.OLDEST_FIRST)
        assertEquals(listOf("a", "c", "b"), playlist.map { it.episodeKey })
    }

    @Test
    fun newestFirstReversesTheDateOrder() {
        val playlist = PlaylistEditor.generate(episodes, sinceEpochMs = day, PlaylistSort.NEWEST_FIRST)
        assertEquals(listOf("b", "c", "a"), playlist.map { it.episodeKey })
    }

    @Test
    fun excludedEpisodesStayOutWhenTheListIsRebuilt() {
        val playlist = PlaylistEditor.generate(
            episodes,
            sinceEpochMs = day,
            PlaylistSort.OLDEST_FIRST,
            excludedKeys = setOf("c"),
        )
        assertEquals(listOf("a", "b"), playlist.map { it.episodeKey })
    }

    @Test
    fun refreshKeepsManualOrderAndAppendsNewEpisodesInSortOrder() {
        val current = listOf(
            entry("c", day * 2),
            entry("a", day),
        )
        val refreshed = PlaylistEditor.refresh(
            current,
            episodes,
            sinceEpochMs = day,
            PlaylistSort.OLDEST_FIRST,
        )
        assertEquals(listOf("c", "a", "b"), refreshed.map { it.episodeKey })
    }

    @Test
    fun reorderAndRemoveAreStable() {
        val start = PlaylistEditor.generate(episodes, day, PlaylistSort.OLDEST_FIRST)
        val moved = PlaylistEditor.move(start, index = 0, delta = 2)
        assertEquals(listOf("c", "b", "a"), moved.map { it.episodeKey })
        val removed = PlaylistEditor.remove(moved, "b")
        assertEquals(listOf("c", "a"), removed.map { it.episodeKey })
        assertEquals(start, PlaylistEditor.move(start, index = 0, delta = 9))
    }

    @Test
    fun nextEpisodeIsTheFollowingRowAndMissingKeysDoNotAutoplay() {
        val items = PlaylistEditor.generate(episodes, day, PlaylistSort.OLDEST_FIRST)
        assertEquals("c", PlaylistEditor.nextAfter(items, "a")?.episodeKey)
        assertNull(PlaylistEditor.nextAfter(items, "b"))
        assertNull(PlaylistEditor.nextAfter(items, "not-in-list"))
    }

    @Test
    fun finishedEpisodesLeaveThePlaylistOnlyWhenAsked() {
        val items = PlaylistEditor.generate(episodes, day, PlaylistSort.OLDEST_FIRST)
        val kept = PlaylistEditor.withoutFinished(items, setOf("a"), removeWhenFinished = false)
        assertEquals(items, kept)
        val dropped = PlaylistEditor.withoutFinished(items, setOf("a", "missing"), removeWhenFinished = true)
        assertEquals(listOf("c", "b"), dropped.map { it.episodeKey })
    }

    @Test
    fun newerPlaylistDocumentWinsAndEqualTimestampKeepsTheStoredOne() {
        val stored = ListeningPlaylist(updatedAt = 50, deviceId = "phone", items = listOf(entry("a", day)))
        val newer = stored.copy(updatedAt = 80, deviceId = "car", items = listOf(entry("b", day * 2)))
        val older = stored.copy(updatedAt = 10, deviceId = "car")
        assertEquals(newer, PlaylistResolver.pick(stored, newer))
        assertEquals(stored, PlaylistResolver.pick(stored, older))
        assertEquals(stored, PlaylistResolver.pick(stored, stored.copy()))
    }

    private fun candidate(key: String, publishedAt: Long, title: String) = PlaylistCandidate(
        episodeKey = key,
        publishedAt = publishedAt,
        title = title,
        podcastTitle = "Show",
        feedUrl = "https://example.com/feed",
        enclosureUrl = "https://cdn.example/$key.mp3",
    )

    private fun entry(key: String, publishedAt: Long) = PlaylistEntry(
        episodeKey = key,
        publishedAt = publishedAt,
        title = key,
        podcastTitle = "Show",
        feedUrl = "https://example.com/feed",
        enclosureUrl = "https://cdn.example/$key.mp3",
    )
}

class DownloadCleanupTest {
    @Test
    fun fileStaysUntilTheDelayHasPassed() {
        val completedAt = 1_000L
        val day = DownloadCleanup.DEFAULT_DELAY_MS
        assertFalse(DownloadCleanup.shouldDeleteDownload(completedAt, now = completedAt + day - 1, delayMs = day))
        assertTrue(DownloadCleanup.shouldDeleteDownload(completedAt, now = completedAt + day, delayMs = day))
        assertFalse(DownloadCleanup.shouldDeleteDownload(null, now = 9_000, delayMs = day))
    }

    @Test
    fun storageLimitRejectsAFileThatWouldOverflow() {
        val limit = 100L
        assertTrue(DownloadCleanup.canStore(usedBytes = 40, incomingBytes = 60, limitBytes = limit))
        assertFalse(DownloadCleanup.canStore(usedBytes = 40, incomingBytes = 61, limitBytes = limit))
        assertFalse(DownloadCleanup.canStore(usedBytes = 0, incomingBytes = 10, limitBytes = 0))
    }
}
