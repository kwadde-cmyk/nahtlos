package de.jenz.nahtlos.core.playlist

import de.jenz.nahtlos.core.db.EpisodeCandidateRow
import de.jenz.nahtlos.core.db.NahtlosDatabase
import de.jenz.nahtlos.core.db.PlaylistDocEntity
import de.jenz.nahtlos.core.AppLog
import de.jenz.nahtlos.core.sync.ServerSettings
import de.jenz.nahtlos.core.sync.SyncClient
import de.jenz.nahtlos.core.sync.SyncHttpException
import de.jenz.nahtlos.core.sync.SettingsStore
import kotlinx.coroutines.CancellationException
import de.jenz.nahtlos.model.NahtlosJson
import de.jenz.nahtlos.model.ProgressStatus
import de.jenz.nahtlos.playlist.ListeningPlaylist
import de.jenz.nahtlos.playlist.PlaylistCandidate
import de.jenz.nahtlos.playlist.PlaylistEditor
import de.jenz.nahtlos.playlist.PlaylistEntry
import de.jenz.nahtlos.playlist.PlaylistResolver
import de.jenz.nahtlos.playlist.PlaylistSort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistRepository @Inject constructor(
    private val db: NahtlosDatabase,
    private val api: SyncClient,
    private val settings: SettingsStore,
    private val downloads: DownloadScheduler,
) {
    fun observe(): Flow<ListeningPlaylist> = db.playlist().observe().map { row ->
        row?.json?.let { decode(it) } ?: ListeningPlaylist()
    }

    suspend fun current(): ListeningPlaylist = db.playlist().get()?.let { decode(it.json) } ?: ListeningPlaylist()

    suspend fun createFromDate(sinceEpochMs: Long, sort: PlaylistSort) {
        val existing = current()
        val items = PlaylistEditor.generate(candidates(), sinceEpochMs, sort, existing.excludedKeys.toSet())
        write(existing.copy(sinceEpochMs = sinceEpochMs, sort = sort, items = items), sync = true)
    }

    suspend fun refresh() {
        val existing = current()
        if (existing.sinceEpochMs <= 0L) return
        val items = PlaylistEditor.refresh(
            existing.items,
            candidates(),
            existing.sinceEpochMs,
            existing.sort,
            existing.excludedKeys.toSet(),
        )
        write(existing.copy(items = items), sync = true)
    }

    suspend fun setSort(sort: PlaylistSort) {
        val existing = current()
        write(existing.copy(sort = sort, items = PlaylistEditor.resort(existing.items, sort)), sync = true)
    }

    suspend fun setRemoveWhenFinished(enabled: Boolean) {
        write(current().copy(removeWhenFinished = enabled), sync = true)
    }

    suspend fun move(index: Int, delta: Int) {
        val existing = current()
        write(existing.copy(items = PlaylistEditor.move(existing.items, index, delta)), sync = true)
    }

    suspend fun reorder(keys: List<String>) {
        val existing = current()
        val items = PlaylistEditor.reorder(existing.items, keys)
        if (items.map { it.episodeKey } == existing.items.map { it.episodeKey }) return
        write(existing.copy(items = items), sync = true)
    }

    suspend fun add(entries: List<PlaylistEntry>) {
        if (entries.isEmpty()) return
        val existing = current()
        val known = existing.items.map { it.episodeKey }.toSet()
        val fresh = entries.filter { it.episodeKey.isNotBlank() && it.episodeKey !in known }
        val allowed = entries.map { it.episodeKey }.toSet()
        write(
            existing.copy(
                items = existing.items + fresh,
                excludedKeys = existing.excludedKeys.filter { it !in allowed },
            ),
            sync = true,
        )
    }

    suspend fun restore(entry: PlaylistEntry, index: Int) {
        val existing = current()
        val items = existing.items.filterNot { it.episodeKey == entry.episodeKey }.toMutableList()
        items.add(index.coerceIn(0, items.size), entry)
        write(
            existing.copy(
                items = items,
                excludedKeys = existing.excludedKeys.filter { it != entry.episodeKey },
            ),
            sync = true,
        )
    }

    suspend fun remove(episodeKey: String) {
        val existing = current()
        val excluded = (existing.excludedKeys + episodeKey).distinct()
        write(
            existing.copy(items = PlaylistEditor.remove(existing.items, episodeKey), excludedKeys = excluded),
            sync = true,
        )
    }

    /**
     * Marks the episode finished in the queue. Returns the next episode key when this one
     * was part of the playlist, so the player can continue.
     */
    suspend fun onEpisodeFinished(episodeKey: String): String? {
        val existing = current()
        val next = PlaylistEditor.nextAfter(existing.items, episodeKey)?.episodeKey
        db.downloads().markCompleted(episodeKey, System.currentTimeMillis())
        if (existing.removeWhenFinished && existing.items.any { it.episodeKey == episodeKey }) {
            dropFinished(existing, setOf(episodeKey))
        }
        return next
    }

    suspend fun dropRemotelyFinished() {
        val finished = db.progress().all()
            .filter { it.status == ProgressStatus.COMPLETED.name }
            .map { it.episodeKey }
            .toSet()
        val existing = current()
        if (!existing.removeWhenFinished) return
        val updated = PlaylistEditor.withoutFinished(existing.items, finished, true)
        if (updated.size != existing.items.size) dropFinished(existing, finished)
    }

    suspend fun pull() {
        val current = settings.read()
        if (!current.configured) return
        val remote = try {
            api.getPlaylist(current)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AppLog.record("Sync", "Playlist-Abruf fehlgeschlagen, lokal behalten: ${error.message}", error)
            return
        }
        if (remote == null) {
            AppLog.record(
                "Sync",
                "Server kennt /v1/playlist nicht (404). Lokale Playlist bleibt. Den Server auf dem Pi aktualisieren.",
            )
            return
        }
        val local = current()
        val merged = PlaylistResolver.merge(local, remote)
        if (merged != local) {
            store(merged, needsSync = false)
        } else if (local.updatedAt > 0L && (PlaylistResolver.isPlaceholder(remote) || local.updatedAt > remote.updatedAt)) {
            push(current, local)
        }
        downloads.enqueue()
    }

    suspend fun applyRemote(remote: ListeningPlaylist) {
        val local = current()
        val merged = PlaylistResolver.merge(local, remote)
        if (merged != local) {
            store(merged, needsSync = false)
            downloads.enqueue()
        } else if (local.updatedAt > remote.updatedAt && local.updatedAt > 0L) {
            val current = settings.read()
            if (current.configured) push(current, local)
        }
    }

    private suspend fun dropFinished(existing: ListeningPlaylist, finished: Set<String>) {
        val items = PlaylistEditor.withoutFinished(existing.items, finished, true)
        if (items == existing.items) return
        val excluded = (existing.excludedKeys + finished.filter { key -> existing.items.any { it.episodeKey == key } }).distinct()
        write(existing.copy(items = items, excludedKeys = excluded), sync = true)
    }

    private suspend fun write(playlist: ListeningPlaylist, sync: Boolean) {
        val stamped = playlist.copy(
            updatedAt = System.currentTimeMillis(),
            deviceId = settings.read().deviceId,
        )
        store(stamped, needsSync = true)
        downloads.enqueue()
        val current = settings.read()
        if (sync && current.configured) push(current, stamped)
    }

    private suspend fun push(settings: ServerSettings, playlist: ListeningPlaylist) {
        try {
            val response = api.putPlaylist(settings, playlist)
            val adopted = PlaylistResolver.afterPush(playlist, response.accepted, response.stored)
            store(adopted, needsSync = !response.accepted && adopted == playlist)
            if (!response.accepted) {
                AppLog.record("Sync", "Server hat die Playlist abgelehnt. Lokal behalten (${playlist.items.size} Folgen).")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: SyncHttpException) {
            AppLog.record(
                "Sync",
                "Playlist-Upload HTTP ${error.code}: ${error.message}. Lokal behalten. " +
                    "404 heißt, der Server auf dem Pi ist älter als die Playlist-Schnittstelle.",
                error,
            )
        } catch (error: Exception) {
            AppLog.record("Sync", "Playlist-Upload fehlgeschlagen, lokal behalten: ${error.message}", error)
        }
    }

    private suspend fun store(playlist: ListeningPlaylist, needsSync: Boolean) {
        db.playlist().upsert(
            PlaylistDocEntity(
                json = NahtlosJson.json.encodeToString(ListeningPlaylist.serializer(), playlist),
                needsSync = needsSync,
            ),
        )
    }

    private suspend fun candidates(): List<PlaylistCandidate> =
        db.episodes().candidates().map { it.toCandidate() }

    private fun decode(json: String): ListeningPlaylist =
        runCatching { NahtlosJson.json.decodeFromString<ListeningPlaylist>(json) }.getOrDefault(ListeningPlaylist())

    private fun EpisodeCandidateRow.toCandidate() = PlaylistCandidate(
        episodeKey = episodeKey,
        publishedAt = publishedAt,
        title = title,
        podcastTitle = podcastTitle,
        feedUrl = feedUrl,
        enclosureUrl = enclosureUrl,
    )
}

fun ListeningPlaylist.entry(episodeKey: String): PlaylistEntry? = items.firstOrNull { it.episodeKey == episodeKey }
