package de.jenz.weiterhoeren.sync

import de.jenz.weiterhoeren.model.EpisodeProgress
import de.jenz.weiterhoeren.model.ProgressStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ProgressResolverTest {
    @Test
    fun phonePauseBeatsOlderCarPosition() {
        val car = progress(positionMs = 12_000, updatedAt = 1_000, deviceId = "car")
        val phone = progress(positionMs = 305_000, updatedAt = 2_000, deviceId = "phone", status = ProgressStatus.PAUSED)
        val winner = ProgressResolver.pick(car, phone)
        assertSame(phone, winner)
        assertEquals(305_000, winner.positionMs)
    }

    @Test
    fun carPauseBeatsOlderPhonePosition() {
        val phone = progress(positionMs = 305_000, updatedAt = 2_000, deviceId = "phone")
        val car = progress(positionMs = 480_000, updatedAt = 3_000, deviceId = "car", status = ProgressStatus.PAUSED)
        val winner = ProgressResolver.pick(phone, car)
        assertSame(car, winner)
    }

    @Test
    fun laterRewindWinsEvenIfPositionIsLower() {
        val played = progress(positionMs = 500_000, updatedAt = 100, deviceId = "phone")
        val rewind = progress(positionMs = 10_000, updatedAt = 200, deviceId = "phone")
        assertSame(rewind, ProgressResolver.pick(played, rewind))
    }

    @Test
    fun equalTimestampHigherPositionWins() {
        val low = progress(positionMs = 1_000, updatedAt = 50, deviceId = "phone")
        val high = progress(positionMs = 9_000, updatedAt = 50, deviceId = "car")
        assertSame(high, ProgressResolver.pick(low, high))
        assertSame(high, ProgressResolver.pick(high, low))
    }

    @Test
    fun equalTimestampAndPositionUsesServerReceiveTime() {
        val earlier = progress(positionMs = 4_000, updatedAt = 50, deviceId = "phone", serverReceivedAt = 10)
        val later = progress(positionMs = 4_000, updatedAt = 50, deviceId = "car", serverReceivedAt = 11)
        assertSame(later, ProgressResolver.pick(earlier, later))
    }

    @Test
    fun fullTieKeepsExistingSoRetriesStayIdempotent() {
        val existing = progress(positionMs = 4_000, updatedAt = 50, deviceId = "phone", serverReceivedAt = 10)
        val retry = progress(positionMs = 4_000, updatedAt = 50, deviceId = "phone", serverReceivedAt = 10)
        assertSame(existing, ProgressResolver.pick(existing, retry))
    }

    @Test
    fun missingServerReceiveTimeLosesToAStoredOneWhenClientFieldsMatch() {
        val stored = progress(positionMs = 4_000, updatedAt = 50, deviceId = "phone", serverReceivedAt = 10)
        val replay = progress(positionMs = 4_000, updatedAt = 50, deviceId = "phone", serverReceivedAt = null)
        assertSame(stored, ProgressResolver.pick(stored, replay))
    }

    private fun progress(
        positionMs: Long,
        updatedAt: Long,
        deviceId: String,
        status: ProgressStatus = ProgressStatus.PAUSED,
        serverReceivedAt: Long? = null,
    ) = EpisodeProgress(
        episodeKey = "pod|ep",
        positionMs = positionMs,
        durationMs = 1_800_000,
        updatedAt = updatedAt,
        deviceId = deviceId,
        status = status,
        serverReceivedAt = serverReceivedAt,
    )
}
