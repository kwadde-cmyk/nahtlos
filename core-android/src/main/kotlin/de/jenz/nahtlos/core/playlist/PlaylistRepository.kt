package de.jenz.nahtlos.core.playlist

import de.jenz.nahtlos.core.db.EpisodeCandidateRow
import de.jenz.nahtlos.core.db.NahtlosDatabase
import de.jenz.nahtlos.core.db.PlaylistDocEntity
import de.jenz.nahtlos.core.sync.ServerSettings
import de.jenz.nahtlos.core.sync.SyncClient
import de.jenz.nahtlos.core.sync.SettingsStore
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
        val remote = api.getPlaylist(current)
        val local = current()
        if (local.updatedAt == 0L && remote.updatedAt == 0L) return
        val winner = if (local.updatedAt == 0L) remote else PlaylistResolver.pick(local, remote)
        val localWins = winner.updatedAt == local.updatedAt && winner.deviceId == local.deviceId && winner.items == local.items
        if (localWins && local.updatedAt > remote.updatedAt) {
            push(current, local)
        } else if (!localWins) {
            store(winner, needsSync = false)
        }
        downloads.enqueue()
    }

    suspend fun applyRemote(remote: ListeningPlaylist) {
        val local = current()
        if (local.updatedAt == 0L || PlaylistResolver.pick(local, remote) === remote) {
            store(remote, needsSync = false)
            downloads.enqueue()
        } else if (local.updatedAt > remote.updatedAt) {
            val settings = settings.read()
            if (settings.configured) push(settings, local)
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
        val response = api.putPlaylist(settings, playlist)
        store(response.stored, needsSync = !response.accepted)
        if (!response.accepted) downloads.enqueue()
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
