package de.jenz.nahtlos.core

object SyncPolicy {
    const val HEARTBEAT_MS = 15_000L
    const val PLAY_GATE_MS = 1_500L
    const val NEAR_END_MS = 15_000L
    const val MIN_CONTINUE_MS = 3_000L
    const val FEED_STALE_MS = 6 * 60 * 60 * 1000L
    const val PULL_DEBOUNCE_MS = 2_000L
    const val SEEK_IGNORE_MS = 800L
}
