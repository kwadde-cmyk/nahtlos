package de.jenz.nahtlos.core.feed

object FeedRefresh {
    const val PENDING = "PENDING"
    const val LOADING = "LOADING"
    const val OK = "OK"
    const val FAILED = "FAILED"

    const val CONCURRENCY = 4
    const val TIMEOUT_MS = 45_000L
    const val EPISODE_BATCH = 40
}
