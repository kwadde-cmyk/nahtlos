package de.jenz.nahtlos.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackCachePolicyTest {
    @Test
    fun defaultAheadIsTenMinutes() {
        assertEquals(10, PlaybackCachePolicy.DEFAULT_AHEAD_MINUTES)
        assertEquals(600_000L, PlaybackCachePolicy.aheadMs(10))
    }

    @Test
    fun minutesAreClampedToTheSupportedRange() {
        assertEquals(2, PlaybackCachePolicy.clampMinutes(0))
        assertEquals(2, PlaybackCachePolicy.clampMinutes(2))
        assertEquals(30, PlaybackCachePolicy.clampMinutes(90))
        assertEquals(120_000L, PlaybackCachePolicy.aheadMs(1))
        assertEquals(1_800_000L, PlaybackCachePolicy.aheadMs(30))
    }

    @Test
    fun byteEstimateMatchesBitrateTimesDuration() {
        // 128 kbit/s for 10 minutes = 9.6 MB.
        assertEquals(9_600_000L, PlaybackCachePolicy.bytesForDuration(600_000L, 128_000L))
        assertEquals(0L, PlaybackCachePolicy.bytesForDuration(0, 128_000L))
        assertEquals(0L, PlaybackCachePolicy.bytesForDuration(600_000L, 0))
    }

    @Test
    fun tenMinuteSpeechFeedFitsInTheMemoryTarget() {
        val speechBytes = PlaybackCachePolicy.bytesForDuration(
            PlaybackCachePolicy.aheadMs(10),
            128_000L,
        )
        assertTrue(PlaybackCachePolicy.memoryTargetBytes(10) >= speechBytes)
        assertTrue(PlaybackCachePolicy.memoryTargetBytes(10) <= 64 * 1024 * 1024)
    }

    @Test
    fun diskCacheCoversTheReadAheadTwiceAndHasAFloor() {
        val window = PlaybackCachePolicy.bytesForDuration(
            PlaybackCachePolicy.aheadMs(10),
            PlaybackCachePolicy.MEMORY_BITRATE_BPS,
        )
        assertTrue(PlaybackCachePolicy.diskCacheBytes(10) >= window * 2)
        assertEquals(64L * 1024 * 1024, PlaybackCachePolicy.diskCacheBytes(2))
        assertTrue(PlaybackCachePolicy.diskCacheBytes(30) <= 512L * 1024 * 1024)
        assertTrue(PlaybackCachePolicy.diskCacheBytes(30) > PlaybackCachePolicy.diskCacheBytes(10))
    }
}
