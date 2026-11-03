package de.jenz.nahtlos.playlist

import kotlinx.serialization.Serializable

@Serializable
enum class PlaylistSort {
    OLDEST_FIRST,
    NEWEST_FIRST,
}

@Serializable
data class PlaylistCandidate(
    val episodeKey: String,
    val publishedAt: Long,
    val title: String,
    val podcastTitle: String,
    val feedUrl: String,
    val enclosureUrl: String,
)

@Serializable
data class PlaylistEntry(
    val episodeKey: String,
    val publishedAt: Long = 0,
    val title: String = "",
    val podcastTitle: String = "",
    val feedUrl: String = "",
    val enclosureUrl: String = "",
)

/**
 * One household queue: everything published since [sinceEpochMs], in [sort] order,
 * then edited by hand. [excludedKeys] are stays-out removals so a refresh does not
 * put them back. [updatedAt] is the client clock used for last-write-wins.
 */
@Serializable
data class ListeningPlaylist(
    val sinceEpochMs: Long = 0,
    val sort: PlaylistSort = PlaylistSort.OLDEST_FIRST,
    val removeWhenFinished: Boolean = true,
    val items: List<PlaylistEntry> = emptyList(),
    val excludedKeys: List<String> = emptyList(),
    val updatedAt: Long = 0,
    val deviceId: String = "",
    val serverReceivedAt: Long? = null,
) {
    fun validationError(): String? {
        if (updatedAt <= 0L) return "updatedAt fehlt"
        if (deviceId.isBlank() || deviceId.length > 128) return "deviceId fehlt oder ist zu lang"
        if (items.size > 2000) return "Playlist ist zu lang"
        if (items.any { it.episodeKey.isBlank() }) return "episodeKey fehlt"
        return null
    }
}

@Serializable
data class PlaylistWriteResponse(
    val accepted: Boolean,
    val stored: ListeningPlaylist,
)

object PlaylistResolver {
    /** Higher [ListeningPlaylist.updatedAt] wins. Equal timestamps keep [existing]. */
    fun pick(existing: ListeningPlaylist, incoming: ListeningPlaylist): ListeningPlaylist {
        if (existing.updatedAt != incoming.updatedAt) {
            return if (existing.updatedAt > incoming.updatedAt) existing else incoming
        }
        val existingReceived = existing.serverReceivedAt ?: Long.MIN_VALUE
        val incomingReceived = incoming.serverReceivedAt ?: Long.MIN_VALUE
        if (existingReceived != incomingReceived) {
            return if (existingReceived > incomingReceived) existing else incoming
        }
        return existing
    }

    /** A missing server document: no timestamp, no device, no items, no date. */
    fun isPlaceholder(playlist: ListeningPlaylist): Boolean =
        playlist.updatedAt <= 0L &&
            playlist.deviceId.isBlank() &&
            playlist.items.isEmpty() &&
            playlist.sinceEpochMs == 0L &&
            playlist.excludedKeys.isEmpty()

    /**
     * [remote] null means the server has no playlist route or the body was not a playlist.
     * An empty remote document never replaces local items. A stale empty row on the server
     * (often with a newer timestamp) is what wiped playlists until the database was reset.
     */
    fun merge(local: ListeningPlaylist, remote: ListeningPlaylist?): ListeningPlaylist {
        if (remote == null || isPlaceholder(remote)) return local
        if (local.items.isNotEmpty() && remote.items.isEmpty()) return local
        if (local.updatedAt <= 0L && local.items.isEmpty() && local.deviceId.isBlank()) return remote
        return pick(local, remote)
    }

    /**
     * When the server holds an empty playlist that is newer than the local one, the next PUT
     * must use a later [ListeningPlaylist.updatedAt] or the server keeps rejecting it.
     * Returns null when a normal push of [local] is enough.
     */
    fun repairStamp(local: ListeningPlaylist, remote: ListeningPlaylist): Long? {
        if (local.items.isEmpty() || remote.items.isNotEmpty() || isPlaceholder(remote)) return null
        if (remote.updatedAt < local.updatedAt) return null
        return remote.updatedAt + 1
    }

    /** What to keep after PUT. A rejected or empty server copy must not wipe local items. */
    fun afterPush(local: ListeningPlaylist, accepted: Boolean, stored: ListeningPlaylist?): ListeningPlaylist {
        if (stored == null) return local
        if (!accepted) return merge(local, stored)
        if (stored.items.isEmpty() && local.items.isNotEmpty()) return local
        return stored
    }
}

object PlaylistEditor {
    fun generate(
        candidates: List<PlaylistCandidate>,
        sinceEpochMs: Long,
        sort: PlaylistSort,
        excludedKeys: Set<String> = emptySet(),
    ): List<PlaylistEntry> {
        return candidates
            .asSequence()
            .filter { it.publishedAt <= 0L || it.publishedAt >= sinceEpochMs }
            .filter { it.episodeKey !in excludedKeys }
            .distinctBy { it.episodeKey }
            .sortedWith(comparator(sort))
            .map { it.toEntry() }
            .toList()
    }

    /**
     * Keeps the manual order of episodes that still match the date.
     * Episodes that are new since the last build are appended in [sort] order.
     */
    fun refresh(
        current: List<PlaylistEntry>,
        candidates: List<PlaylistCandidate>,
        sinceEpochMs: Long,
        sort: PlaylistSort,
        excludedKeys: Set<String> = emptySet(),
    ): List<PlaylistEntry> {
        val knownKeys = candidates.map { it.episodeKey }.toSet()
        val byEnclosure = candidates
            .filter { it.enclosureUrl.isNotBlank() }
            .associateBy { it.enclosureUrl }
        val normalized = current.map { entry ->
            val matched = entry.enclosureUrl.takeIf { it.isNotBlank() }?.let { byEnclosure[it] }
            if (matched != null && matched.episodeKey != entry.episodeKey) matched.toEntry() else entry
        }.distinctBy { it.episodeKey }
        val generated = generate(candidates, sinceEpochMs, sort, excludedKeys).associateBy { it.episodeKey }
        val kept = normalized.filter { entry ->
            when {
                entry.episodeKey in excludedKeys -> false
                entry.episodeKey in generated -> true
                entry.episodeKey !in knownKeys && entry.enclosureUrl !in byEnclosure -> true
                else -> false
            }
        }
        val keptKeys = kept.map { it.episodeKey }.toSet()
        val fresh = generated.values
            .filter { it.episodeKey !in keptKeys }
            .sortedWith(entryComparator(sort))
        return kept + fresh
    }

    fun resort(items: List<PlaylistEntry>, sort: PlaylistSort): List<PlaylistEntry> =
        items.sortedWith(entryComparator(sort))

    fun reorder(items: List<PlaylistEntry>, keys: List<String>): List<PlaylistEntry> {
        if (keys.isEmpty() || items.isEmpty()) return items
        val byKey = items.associateBy { it.episodeKey }
        val used = LinkedHashSet<String>()
        val ordered = ArrayList<PlaylistEntry>(items.size)
        for (key in keys) {
            val entry = byKey[key] ?: continue
            if (used.add(key)) ordered.add(entry)
        }
        for (entry in items) {
            if (used.add(entry.episodeKey)) ordered.add(entry)
        }
        return ordered
    }

    fun move(items: List<PlaylistEntry>, index: Int, delta: Int): List<PlaylistEntry> {
        val target = index + delta
        if (index !in items.indices || target !in items.indices) return items
        val mutable = items.toMutableList()
        val item = mutable.removeAt(index)
        mutable.add(target, item)
        return mutable
    }

    fun remove(items: List<PlaylistEntry>, episodeKey: String): List<PlaylistEntry> =
        items.filterNot { it.episodeKey == episodeKey }

    fun nextAfter(items: List<PlaylistEntry>, episodeKey: String): PlaylistEntry? {
        val index = items.indexOfFirst { it.episodeKey == episodeKey }
        if (index < 0) return null
        return items.getOrNull(index + 1)
    }

    fun withoutFinished(
        items: List<PlaylistEntry>,
        finishedKeys: Set<String>,
        removeWhenFinished: Boolean,
    ): List<PlaylistEntry> {
        if (!removeWhenFinished || finishedKeys.isEmpty()) return items
        return items.filter { it.episodeKey !in finishedKeys }
    }

    private fun comparator(sort: PlaylistSort): Comparator<PlaylistCandidate> =
        if (sort == PlaylistSort.OLDEST_FIRST) {
            compareBy<PlaylistCandidate> { it.publishedAt }.thenBy { it.episodeKey }
        } else {
            compareByDescending<PlaylistCandidate> { it.publishedAt }.thenBy { it.episodeKey }
        }

    private fun entryComparator(sort: PlaylistSort): Comparator<PlaylistEntry> =
        if (sort == PlaylistSort.OLDEST_FIRST) {
            compareBy<PlaylistEntry> { it.publishedAt }.thenBy { it.episodeKey }
        } else {
            compareByDescending<PlaylistEntry> { it.publishedAt }.thenBy { it.episodeKey }
        }

    private fun PlaylistCandidate.toEntry() = PlaylistEntry(
        episodeKey = episodeKey,
        publishedAt = publishedAt,
        title = title,
        podcastTitle = podcastTitle,
        feedUrl = feedUrl,
        enclosureUrl = enclosureUrl,
    )
}
