package de.jenz.nahtlos.core.library

import de.jenz.nahtlos.core.db.NahtlosDatabase
import de.jenz.nahtlos.core.playlist.DownloadScheduler
import de.jenz.nahtlos.core.playlist.DownloadStore
import de.jenz.nahtlos.core.playlist.ParkedDownload
import de.jenz.nahtlos.core.playlist.PlaylistRepository
import de.jenz.nahtlos.core.sync.SyncRepository
import de.jenz.nahtlos.model.ProgressStatus
import de.jenz.nahtlos.playback.SwipeAction
import de.jenz.nahtlos.playlist.PlaylistEntry
import javax.inject.Inject
import javax.inject.Singleton

class Reversible(
    val message: String,
    val undo: suspend () -> Unit,
    val commit: suspend () -> Unit = {},
)

@Singleton
class EpisodeCommands @Inject constructor(
    private val db: NahtlosDatabase,
    private val sync: SyncRepository,
    private val playlists: PlaylistRepository,
    private val downloads: DownloadStore,
    private val scheduler: DownloadScheduler,
) {
    suspend fun run(action: SwipeAction, keys: List<String>): Reversible? = execute(action, keys)

    /** Resolves each episode against its current state, so a bulk "Gehört" unmarks ones already played. */
    suspend fun runToggled(action: SwipeAction, keys: List<String>): Reversible? {
        val ids = keys.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return null
        val inPlaylist = playlists.current().items.map { it.episodeKey }.toSet()
        val groups = ids.groupBy { key ->
            action.resolved(
                played = db.progress().get(key)?.status == ProgressStatus.COMPLETED.name,
                inPlaylist = key in inPlaylist,
                downloaded = downloads.existingFile(key) != null,
            )
        }
        val parts = groups.mapNotNull { (resolved, group) -> execute(resolved, group) }
        if (parts.isEmpty()) return null
        if (parts.size == 1) return parts.first()
        return Reversible(
            message = parts.joinToString { it.message },
            undo = { parts.forEach { it.undo() } },
            commit = { parts.forEach { it.commit() } },
        )
    }

    private suspend fun execute(action: SwipeAction, keys: List<String>): Reversible? {
        val ids = keys.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return null
        return when (action) {
            SwipeAction.MARK_PLAYED -> mark(ids, played = true)
            SwipeAction.MARK_UNPLAYED -> mark(ids, played = false)
            SwipeAction.ADD_PLAYLIST -> add(ids)
            SwipeAction.REMOVE_PLAYLIST -> remove(ids)
            SwipeAction.DELETE_DOWNLOAD -> deleteDownloads(ids)
            SwipeAction.DOWNLOAD -> download(ids)
        }
    }

    private suspend fun mark(keys: List<String>, played: Boolean): Reversible {
        val previous = keys.map { it to db.progress().get(it) }
        for (key in keys) {
            val duration = db.episodes().get(key)?.durationMs ?: 0L
            if (played) sync.recordPlayback(key, duration, duration, ProgressStatus.COMPLETED)
            else sync.recordPlayback(key, 0, duration, ProgressStatus.PAUSED)
        }
        return Reversible(
            message = if (played) "Als gehört markiert" else "Als ungehört markiert",
            undo = {
                for ((key, row) in previous) {
                    if (row == null) db.progress().deleteKeys(listOf(key))
                    else sync.recordPlayback(
                        key,
                        row.positionMs,
                        row.durationMs,
                        runCatching { ProgressStatus.valueOf(row.status) }.getOrDefault(ProgressStatus.PAUSED),
                    )
                }
            },
        )
    }

    private suspend fun add(keys: List<String>): Reversible {
        val entries = keys.mapNotNull { entry(it) }
        playlists.add(entries)
        return Reversible(
            message = "Zur Playlist hinzugefügt",
            undo = { entries.forEach { playlists.remove(it.episodeKey) } },
        )
    }

    private suspend fun remove(keys: List<String>): Reversible {
        val playlist = playlists.current()
        val removed = playlist.items.withIndex().filter { it.value.episodeKey in keys.toSet() }
        keys.forEach { playlists.remove(it) }
        return Reversible(
            message = "Aus der Playlist genommen",
            undo = { removed.sortedBy { it.index }.forEach { playlists.restore(it.value, it.index) } },
        )
    }

    private suspend fun download(keys: List<String>): Reversible {
        keys.forEach { scheduler.enqueueEpisode(it) }
        return Reversible(message = "Download gestartet", undo = {})
    }

    private suspend fun deleteDownloads(keys: List<String>): Reversible {
        val parked = keys.mapNotNull { downloads.park(it) }
        return Reversible(
            message = "Download gelöscht",
            undo = { parked.forEach { downloads.restore(it) } },
            commit = { parked.forEach { downloads.discard(it) } },
        )
    }

    private suspend fun entry(key: String): PlaylistEntry? {
        val episode = db.episodes().get(key) ?: return null
        val podcast = db.podcasts().get(episode.podcastId)
        return PlaylistEntry(
            episodeKey = episode.episodeKey,
            publishedAt = episode.publishedAt ?: 0L,
            title = episode.title,
            podcastTitle = podcast?.title.orEmpty(),
            feedUrl = podcast?.feedUrl.orEmpty(),
            enclosureUrl = episode.enclosureUrl,
        )
    }
}
