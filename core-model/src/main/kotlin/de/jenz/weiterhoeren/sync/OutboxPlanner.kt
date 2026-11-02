package de.jenz.weiterhoeren.sync

import de.jenz.weiterhoeren.model.EpisodeProgress

data class FlushPlan(
    /** Latest local write per episode that is still newer than the remote document. */
    val toUpload: List<EpisodeProgress>,
    /** Superseded local rows, or rows the remote side already won. */
    val toDrop: List<EpisodeProgress>,
)

/**
 * Decides which queued offline writes are still worth sending.
 * Remote state is optional: pass an empty map when the server could not be read
 * and every latest local row will be uploaded. The server applies the same LWW rule.
 */
object OutboxPlanner {
    private val latestFirst: Comparator<EpisodeProgress> =
        compareByDescending<EpisodeProgress> { it.updatedAt }
            .thenByDescending { it.positionMs }

    fun plan(
        queued: List<EpisodeProgress>,
        remoteByEpisode: Map<String, EpisodeProgress>,
    ): FlushPlan {
        if (queued.isEmpty()) return FlushPlan(emptyList(), emptyList())
        val upload = mutableListOf<EpisodeProgress>()
        val drop = mutableListOf<EpisodeProgress>()
        for ((key, entries) in queued.groupBy { it.episodeKey }) {
            val ordered = entries.sortedWith(latestFirst)
            val latest = ordered.first()
            drop += ordered.drop(1)
            val remote = remoteByEpisode[key]
            if (remote == null) {
                upload += latest
                continue
            }
            val winner = ProgressResolver.pick(remote, latest)
            val localWins = winner === latest
            val alreadyStored = ProgressResolver.sameClientWrite(remote, latest)
            if (localWins && !alreadyStored) upload += latest else drop += latest
        }
        return FlushPlan(upload, drop)
    }
}
