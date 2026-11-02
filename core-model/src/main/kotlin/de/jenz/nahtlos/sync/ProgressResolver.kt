package de.jenz.nahtlos.sync

import de.jenz.nahtlos.model.EpisodeProgress

/**
 * Last-write-wins by client timestamp.
 *
 * 1. Higher [EpisodeProgress.updatedAt] wins (a later rewind beats an earlier, further position).
 * 2. Equal timestamp: higher [EpisodeProgress.positionMs] wins.
 * 3. Still equal: higher [EpisodeProgress.serverReceivedAt] wins (null counts as older).
 * 4. Full tie: [existing] is kept so retries stay idempotent.
 *
 * Both arguments are returned unchanged — callers may use reference equality.
 */
object ProgressResolver {
    fun pick(existing: EpisodeProgress, incoming: EpisodeProgress): EpisodeProgress {
        require(existing.episodeKey == incoming.episodeKey) {
            "Konflikt nur innerhalb einer Episode (${existing.episodeKey} vs ${incoming.episodeKey})"
        }
        val byClientTime = compareValues(existing.updatedAt, incoming.updatedAt)
        if (byClientTime != 0) return if (byClientTime > 0) existing else incoming

        val byPosition = compareValues(existing.positionMs, incoming.positionMs)
        if (byPosition != 0) return if (byPosition > 0) existing else incoming

        val existingReceived = existing.serverReceivedAt ?: Long.MIN_VALUE
        val incomingReceived = incoming.serverReceivedAt ?: Long.MIN_VALUE
        val byReceived = compareValues(existingReceived, incomingReceived)
        if (byReceived != 0) return if (byReceived > 0) existing else incoming

        return existing
    }

    fun sameClientWrite(a: EpisodeProgress, b: EpisodeProgress): Boolean =
        a.episodeKey == b.episodeKey &&
            a.updatedAt == b.updatedAt &&
            a.positionMs == b.positionMs &&
            a.deviceId == b.deviceId &&
            a.status == b.status
}
