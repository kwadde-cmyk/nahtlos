package de.jenz.nahtlos.playback

/**
 * How far ahead playback should stay buffered, and how large the on-disk LRU cache
 * must be so a tunnel does not evict that window.
 *
 * Sizing assumes a generous podcast bitrate ([MEMORY_BITRATE_BPS]). A quieter feed
 * fits the full time window; a much louder one can hit the byte cap first.
 */
object PlaybackCachePolicy {
    const val DEFAULT_AHEAD_MINUTES = 10
    const val MIN_AHEAD_MINUTES = 2
    const val MAX_AHEAD_MINUTES = 30

    /** Planning bitrate: 320 kbit/s covers most spoken-word and many music feeds. */
    const val MEMORY_BITRATE_BPS = 320_000L

    const val START_PLAYBACK_MS = 1_000
    const val REBUFFER_PLAYBACK_MS = 2_500
    const val BACK_BUFFER_MS = 60_000

    private const val MIN_MEMORY_BYTES = 8L * 1024 * 1024
    private const val MAX_MEMORY_BYTES = 64L * 1024 * 1024
    private const val MIN_DISK_CACHE_BYTES = 64L * 1024 * 1024
    private const val MAX_DISK_CACHE_BYTES = 512L * 1024 * 1024

    fun clampMinutes(minutes: Int): Int = minutes.coerceIn(MIN_AHEAD_MINUTES, MAX_AHEAD_MINUTES)

    fun aheadMs(minutes: Int): Long = clampMinutes(minutes) * 60_000L

    fun bytesForDuration(durationMs: Long, bitrateBps: Long): Long {
        if (durationMs <= 0L || bitrateBps <= 0L) return 0L
        return bitrateBps * durationMs / 8_000L
    }

    /**
     * In-memory cap for the load controller. 20% above the planning bitrate so the
     * time target is reached before the allocator stops the download.
     */
    fun memoryTargetBytes(aheadMinutes: Int): Int {
        val raw = bytesForDuration(aheadMs(aheadMinutes), MEMORY_BITRATE_BPS)
        val withMargin = raw + raw / 5
        return withMargin.coerceIn(MIN_MEMORY_BYTES, MAX_MEMORY_BYTES).toInt()
    }

    /**
     * Disk cache holds the read-ahead, the same span already played (so a rewind
     * stays cached), and one extra window. LRU drops the oldest spans past that.
     */
    fun diskCacheBytes(aheadMinutes: Int): Long {
        val window = bytesForDuration(aheadMs(aheadMinutes), MEMORY_BITRATE_BPS)
        return (window * 3).coerceIn(MIN_DISK_CACHE_BYTES, MAX_DISK_CACHE_BYTES)
    }
}
