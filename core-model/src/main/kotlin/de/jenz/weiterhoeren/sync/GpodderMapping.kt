package de.jenz.weiterhoeren.sync

/**
 * Seam for a later gPodder episode-action adapter. Not served yet.
 *
 * Classic gPodder stores `play` actions with whole seconds (`position`, `total`),
 * an ISO timestamp and usually the enclosure URL as the episode key. Weiterhören
 * keeps milliseconds and prefers `podcast:guid` + item GUID. A future adapter can
 * project one [de.jenz.weiterhoeren.model.EpisodeProgress] into that shape without
 * changing the LWW document.
 */
object GpodderMapping {
    const val ACTION_PLAY = "play"
    const val ACTION_DOWNLOAD = "download"
    const val ACTION_DELETE = "delete"
    const val ACTION_NEW = "new"

    fun toSeconds(positionMs: Long): Long = positionMs.coerceAtLeast(0) / 1000

    fun fromSeconds(positionSeconds: Long): Long = positionSeconds.coerceAtLeast(0) * 1000
}
