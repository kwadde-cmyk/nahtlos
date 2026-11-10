package de.jenz.nahtlos.playlist

import de.jenz.nahtlos.playback.SwipeAction
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
    fun reorderFollowsKeysAndKeepsRowsThatWereNotListed() {
        val start = PlaylistEditor.generate(episodes, day, PlaylistSort.OLDEST_FIRST)
        val reordered = PlaylistEditor.reorder(start, listOf("b", "a", "c"))
        assertEquals(listOf("b", "a", "c"), reordered.map { it.episodeKey })
        val partial = PlaylistEditor.reorder(start, listOf("c"))
        assertEquals(listOf("c", "a", "b"), partial.map { it.episodeKey })
        assertEquals(start, PlaylistEditor.reorder(start, emptyList()))
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
    fun refreshKeepsItemsWhoseEpisodesAreNotInTheDatabaseYet() {
        val pending = entry("missing", day * 4).copy(enclosureUrl = "https://cdn.example/missing.mp3")
        val current = listOf(pending, entry("a", day))
        val refreshed = PlaylistEditor.refresh(current, episodes, day, PlaylistSort.OLDEST_FIRST)
        assertEquals(listOf("missing", "a", "c", "b"), refreshed.map { it.episodeKey })
    }

    @Test
    fun refreshReplacesAYoutubeEnclosureWithTheAudioFile() {
        val broken = entry("a", day).copy(enclosureUrl = "https://youtu.be/XcYgUdvDxxA")
        val library = episodes.map {
            if (it.episodeKey == "a") it.copy(enclosureUrl = "https://serve.example/a.mp3") else it
        }
        val refreshed = PlaylistEditor.refresh(listOf(broken), library, day, PlaylistSort.OLDEST_FIRST)
        assertEquals("https://serve.example/a.mp3", refreshed.first { it.episodeKey == "a" }.enclosureUrl)
    }

    @Test
    fun realignLeavesAPlayableEnclosureUntouched() {
        val chosen = entry("a", day).copy(enclosureUrl = "https://cdn.example/hq.mp3")
        val aligned = PlaylistEditor.realignEnclosures(
            listOf(chosen),
            mapOf("a" to "https://cdn.example/low.mp3"),
        )
        assertEquals("https://cdn.example/hq.mp3", aligned.single().enclosureUrl)
    }

    @Test
    fun refreshRewritesAUrlKeyOnceTheGuidIsKnown() {
        val urlKey = entry("url:old", day * 2).copy(enclosureUrl = "https://cdn.example/c.mp3")
        val candidates = episodes + candidate("c", day * 2, "Mitte").copy(enclosureUrl = "https://cdn.example/c.mp3")
        val refreshed = PlaylistEditor.refresh(listOf(urlKey), candidates.distinctBy { it.episodeKey + it.enclosureUrl }, day, PlaylistSort.OLDEST_FIRST)
        assertEquals("c", refreshed.first().episodeKey)
        assertFalse(refreshed.any { it.episodeKey == "url:old" })
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

    @Test
    fun emptyRemoteDoesNotEraseLocalItemsUnlessItIsARealClear() {
        val local = ListeningPlaylist(
            sinceEpochMs = day,
            updatedAt = 100,
            deviceId = "phone",
            items = listOf(entry("a", day)),
        )
        assertEquals(local, PlaylistResolver.merge(local, null))
        assertEquals(local, PlaylistResolver.merge(local, ListeningPlaylist()))
        val olderEmpty = ListeningPlaylist(updatedAt = 40, deviceId = "car", items = emptyList())
        assertEquals(local, PlaylistResolver.merge(local, olderEmpty))
        val newerStub = ListeningPlaylist(updatedAt = 200, deviceId = "car", sinceEpochMs = day, items = emptyList())
        assertEquals(local, PlaylistResolver.merge(local, newerStub))
        val cleared = local.copy(updatedAt = 200, deviceId = "car", items = emptyList(), excludedKeys = listOf("a"))
        assertEquals(local, PlaylistResolver.merge(local, cleared))
        assertEquals(201L, PlaylistResolver.repairStamp(local, cleared))
        assertEquals(null, PlaylistResolver.repairStamp(local, olderEmpty))
        val newerItems = local.copy(updatedAt = 180, deviceId = "car", items = listOf(entry("b", day * 2)))
        assertEquals(newerItems, PlaylistResolver.merge(local, newerItems))
    }

    @Test
    fun undatedEpisodesStayInTheSinceDatePlaylist() {
        val undated = candidate("x", publishedAt = 0, title = "Ohne Datum")
        val playlist = PlaylistEditor.generate(episodes + undated, sinceEpochMs = day, PlaylistSort.OLDEST_FIRST)
        assertEquals(listOf("x", "a", "c", "b"), playlist.map { it.episodeKey })
    }

    @Test
    fun menuShowsOnlyActionsThatFitTheSelection() {
        val fresh = SwipeAction.matching(listOf("a", "b"), emptySet(), emptySet(), emptySet())
        assertEquals(
            listOf(SwipeAction.ADD_PLAYLIST, SwipeAction.DOWNLOAD, SwipeAction.MARK_PLAYED),
            fresh,
        )
        val done = SwipeAction.matching(
            listOf("a"),
            played = setOf("a"),
            inPlaylist = setOf("a"),
            downloaded = setOf("a"),
        )
        assertEquals(
            listOf(SwipeAction.REMOVE_PLAYLIST, SwipeAction.DELETE_DOWNLOAD, SwipeAction.MARK_UNPLAYED),
            done,
        )
        val mixed = SwipeAction.matching(
            listOf("a", "b"),
            played = setOf("a"),
            inPlaylist = setOf("b"),
            downloaded = setOf("a"),
        )
        assertEquals(
            listOf(
                SwipeAction.ADD_PLAYLIST,
                SwipeAction.REMOVE_PLAYLIST,
                SwipeAction.DOWNLOAD,
                SwipeAction.DELETE_DOWNLOAD,
                SwipeAction.MARK_PLAYED,
                SwipeAction.MARK_UNPLAYED,
            ),
            mixed,
        )
        assertTrue(SwipeAction.matching(emptyList(), emptySet(), emptySet(), emptySet()).isEmpty())
    }

    @Test
    fun menuTargetsOnlyEpisodesTheActionChanges() {
        val keys = listOf("a", "b")
        assertEquals(listOf("b"), SwipeAction.targets(SwipeAction.MARK_PLAYED, keys, setOf("a"), emptySet(), emptySet()))
        assertEquals(listOf("a"), SwipeAction.targets(SwipeAction.MARK_UNPLAYED, keys, setOf("a"), emptySet(), emptySet()))
        assertEquals(listOf("b"), SwipeAction.targets(SwipeAction.ADD_PLAYLIST, keys, emptySet(), setOf("a"), emptySet()))
        assertEquals(listOf("a"), SwipeAction.targets(SwipeAction.REMOVE_PLAYLIST, keys, emptySet(), setOf("a"), emptySet()))
        assertEquals(listOf("a"), SwipeAction.targets(SwipeAction.DELETE_DOWNLOAD, keys, emptySet(), emptySet(), setOf("a")))
        assertEquals(listOf("b"), SwipeAction.targets(SwipeAction.DOWNLOAD, keys, emptySet(), emptySet(), setOf("a")))
    }

    @Test
    fun swipeTogglesAgainstTheCurrentState() {
        assertEquals(SwipeAction.MARK_UNPLAYED, SwipeAction.MARK_PLAYED.resolved(played = true, inPlaylist = false, downloaded = false))
        assertEquals(SwipeAction.MARK_PLAYED, SwipeAction.MARK_UNPLAYED.resolved(played = false, inPlaylist = false, downloaded = false))
        assertEquals(SwipeAction.REMOVE_PLAYLIST, SwipeAction.ADD_PLAYLIST.resolved(played = false, inPlaylist = true, downloaded = false))
        assertEquals(SwipeAction.ADD_PLAYLIST, SwipeAction.REMOVE_PLAYLIST.resolved(played = false, inPlaylist = false, downloaded = false))
        assertEquals(SwipeAction.DELETE_DOWNLOAD, SwipeAction.DELETE_DOWNLOAD.resolved(played = false, inPlaylist = false, downloaded = true))
        assertEquals(SwipeAction.DOWNLOAD, SwipeAction.DELETE_DOWNLOAD.resolved(played = false, inPlaylist = false, downloaded = false))
    }

    @Test
    fun rejectedPushKeepsTheLocalPlaylist() {
        val local = ListeningPlaylist(sinceEpochMs = day, updatedAt = 100, deviceId = "phone", items = listOf(entry("a", day)))
        val storedEmpty = ListeningPlaylist(updatedAt = 90, deviceId = "server", items = emptyList())
        assertEquals(local, PlaylistResolver.afterPush(local, accepted = false, stored = storedEmpty))
        assertEquals(local, PlaylistResolver.afterPush(local, accepted = true, stored = ListeningPlaylist()))
        val echoed = local.copy(serverReceivedAt = 5)
        assertEquals(echoed, PlaylistResolver.afterPush(local, accepted = true, stored = echoed))
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
