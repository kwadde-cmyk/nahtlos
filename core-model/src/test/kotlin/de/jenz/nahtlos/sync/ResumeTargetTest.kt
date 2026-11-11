package de.jenz.nahtlos.sync

import de.jenz.nahtlos.model.EpisodeProgress
import de.jenz.nahtlos.model.ProgressStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResumeTargetTest {
    @Test
    fun playingDeviceKeepsItsEpisode() {
        val rows = listOf(
            progress("a", positionMs = 10_000, updatedAt = 1, deviceId = "phone"),
            progress("b", positionMs = 4_000, updatedAt = 9, deviceId = "car"),
        )
        assertNull(ResumeTarget.decide(playing = true, currentKey = "a", ownDeviceId = "phone", progress = rows))
    }

    @Test
    fun idleDeviceAdoptsTheEpisodeAnotherDeviceHeardLater() {
        val rows = listOf(
            progress("a", positionMs = 305_000, updatedAt = 2, deviceId = "phone"),
            progress("b", positionMs = 12_000, updatedAt = 8, deviceId = "car"),
        )
        val decision = ResumeTarget.decide(playing = false, currentKey = "a", ownDeviceId = "phone", progress = rows)
        assertEquals("b", decision?.episodeKey)
        assertEquals(12_000, decision?.positionMs)
        assertEquals(true, decision?.switchItem)
    }

    @Test
    fun sameEpisodeFromTheOtherDeviceOnlyMovesThePosition() {
        val rows = listOf(
            progress("a", positionMs = 10_000, updatedAt = 2, deviceId = "phone"),
            progress("a", positionMs = 80_000, updatedAt = 9, deviceId = "car"),
        )
        val decision = ResumeTarget.decide(playing = false, currentKey = "a", ownDeviceId = "phone", progress = rows)
        assertEquals("a", decision?.episodeKey)
        assertEquals(80_000, decision?.positionMs)
        assertEquals(false, decision?.switchItem)
    }

    @Test
    fun laterRewindOnTheOtherDeviceWins() {
        val rows = listOf(
            progress("a", positionMs = 500_000, updatedAt = 3, deviceId = "phone"),
            progress("a", positionMs = 4_000, updatedAt = 6, deviceId = "car"),
        )
        val decision = ResumeTarget.decide(playing = false, currentKey = "a", ownDeviceId = "phone", progress = rows)
        assertEquals(4_000, decision?.positionMs)
    }

    @Test
    fun ownLatestWriteDoesNotReplaceTheLoadedEpisode() {
        val rows = listOf(
            progress("a", positionMs = 20_000, updatedAt = 4, deviceId = "phone"),
            progress("b", positionMs = 90_000, updatedAt = 1, deviceId = "car"),
        )
        assertNull(ResumeTarget.decide(playing = false, currentKey = "a", ownDeviceId = "phone", progress = rows))
    }

    @Test
    fun emptyPlayerLoadsTheLatestEpisodeEvenFromThisDevice() {
        val rows = listOf(progress("b", positionMs = 15_000, updatedAt = 5, deviceId = "car"))
        val decision = ResumeTarget.decide(playing = false, currentKey = null, ownDeviceId = "car", progress = rows)
        assertEquals("b", decision?.episodeKey)
        assertEquals(true, decision?.switchItem)
    }

    @Test
    fun completedEpisodeDoesNotHideTheNewerInProgressOne() {
        val rows = listOf(
            progress("old", positionMs = 10_000, updatedAt = 2, deviceId = "phone"),
            progress("done", positionMs = 1_800_000, updatedAt = 20, deviceId = "car", status = ProgressStatus.COMPLETED),
            progress("next", positionMs = 1_000, updatedAt = 21, deviceId = "car"),
        )
        val decision = ResumeTarget.decide(playing = false, currentKey = "old", ownDeviceId = "phone", progress = rows)
        assertEquals("next", decision?.episodeKey)
    }

    @Test
    fun phoneToCarUsesTheSameRule() {
        val rows = listOf(
            progress("car-ep", positionMs = 40_000, updatedAt = 3, deviceId = "car"),
            progress("phone-ep", positionMs = 8_000, updatedAt = 11, deviceId = "phone"),
        )
        val decision = ResumeTarget.decide(playing = false, currentKey = "car-ep", ownDeviceId = "car", progress = rows)
        assertEquals("phone-ep", decision?.episodeKey)
        assertEquals(8_000, decision?.positionMs)
    }

    private fun progress(
        key: String,
        positionMs: Long,
        updatedAt: Long,
        deviceId: String,
        status: ProgressStatus = ProgressStatus.PAUSED,
    ) = EpisodeProgress(
        episodeKey = key,
        positionMs = positionMs,
        durationMs = 1_800_000,
        updatedAt = updatedAt,
        deviceId = deviceId,
        status = status,
    )
}
