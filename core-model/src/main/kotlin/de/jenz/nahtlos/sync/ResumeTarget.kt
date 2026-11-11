package de.jenz.nahtlos.sync

import de.jenz.nahtlos.model.EpisodeProgress
import de.jenz.nahtlos.model.ProgressStatus

/**
 * The device that last listened picks the episode for every other device that is not playing.
 * Completed rows are not a resume point: the next episode, once started, has the newer timestamp.
 */
object ResumeTarget {
    data class Decision(
        val episodeKey: String,
        val positionMs: Long,
        /** False when the player already has this episode and only the position should move. */
        val switchItem: Boolean,
    )

    fun latest(progress: List<EpisodeProgress>): EpisodeProgress? =
        progress
            .map { it.normalized() }
            .filter { it.episodeKey.isNotEmpty() && it.status != ProgressStatus.COMPLETED }
            .maxWithOrNull(order)

    fun decide(
        playing: Boolean,
        currentKey: String?,
        ownDeviceId: String,
        progress: List<EpisodeProgress>,
    ): Decision? {
        if (playing) return null
        val newest = latest(progress) ?: return null
        val current = currentKey?.takeIf { it.isNotBlank() }
        if (current == null) {
            return Decision(newest.episodeKey, newest.positionMs, switchItem = true)
        }
        if (newest.deviceId == ownDeviceId.trim()) return null
        return Decision(
            episodeKey = newest.episodeKey,
            positionMs = newest.positionMs,
            switchItem = newest.episodeKey != current,
        )
    }

    private val order = compareBy<EpisodeProgress> { it.updatedAt }
        .thenBy { it.positionMs }
        .thenBy { it.serverReceivedAt ?: Long.MIN_VALUE }
}
