package de.jenz.nahtlos.playlist

/**
 * A finished episode's file stays until [delayMs] after the device first noticed
 * it was played. The playlist drop is separate and can happen as soon as it is finished.
 */
object DownloadCleanup {
    const val DEFAULT_DELAY_MS = 24L * 60 * 60 * 1000

    fun shouldDeleteDownload(completedAt: Long?, now: Long, delayMs: Long): Boolean {
        if (completedAt == null || completedAt <= 0L || delayMs < 0L) return false
        return now - completedAt >= delayMs
    }

    fun canStore(usedBytes: Long, incomingBytes: Long, limitBytes: Long): Boolean {
        if (limitBytes <= 0L || incomingBytes < 0L || usedBytes < 0L) return false
        return usedBytes <= limitBytes && usedBytes + incomingBytes <= limitBytes
    }
}
