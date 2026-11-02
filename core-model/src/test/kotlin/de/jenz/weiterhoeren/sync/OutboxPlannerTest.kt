package de.jenz.weiterhoeren.sync

import de.jenz.weiterhoeren.model.EpisodeProgress
import de.jenz.weiterhoeren.model.ProgressStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OutboxPlannerTest {
    @Test
    fun keepsOnlyTheLatestQueuedWritePerEpisode() {
        val older = progress("ep", positionMs = 1_000, updatedAt = 10)
        val newer = progress("ep", positionMs = 5_000, updatedAt = 20)
        val plan = OutboxPlanner.plan(listOf(older, newer), emptyMap())
        assertEquals(listOf(newer), plan.toUpload)
        assertEquals(listOf(older), plan.toDrop)
    }

    @Test
    fun uploadsWhenTheCarWasOfflineAndIsNewer() {
        val remote = progress("ep", positionMs = 10_000, updatedAt = 100, deviceId = "phone")
        val queued = progress("ep", positionMs = 80_000, updatedAt = 500, deviceId = "car")
        val plan = OutboxPlanner.plan(listOf(queued), mapOf("ep" to remote))
        assertEquals(listOf(queued), plan.toUpload)
        assertTrue(plan.toDrop.isEmpty())
    }

    @Test
    fun dropsTheOutboxWhenThePhoneAlreadyHasANewerPosition() {
        val remote = progress("ep", positionMs = 200_000, updatedAt = 900, deviceId = "phone")
        val stale = progress("ep", positionMs = 50_000, updatedAt = 100, deviceId = "car")
        val plan = OutboxPlanner.plan(listOf(stale), mapOf("ep" to remote))
        assertTrue(plan.toUpload.isEmpty())
        assertEquals(listOf(stale), plan.toDrop)
    }

    @Test
    fun dropsAWriteTheServerAlreadyStored() {
        val stored = progress("ep", positionMs = 12_000, updatedAt = 40, deviceId = "phone", serverReceivedAt = 41)
        val queued = stored.copy(serverReceivedAt = null)
        val plan = OutboxPlanner.plan(listOf(queued), mapOf("ep" to stored))
        assertTrue(plan.toUpload.isEmpty())
        assertEquals(1, plan.toDrop.size)
    }

    @Test
    fun plansEachEpisodeIndependently() {
        val uploadA = progress("a", positionMs = 3_000, updatedAt = 20, deviceId = "car")
        val dropB = progress("b", positionMs = 1_000, updatedAt = 5, deviceId = "car")
        val remoteB = progress("b", positionMs = 9_000, updatedAt = 30, deviceId = "phone")
        val plan = OutboxPlanner.plan(listOf(uploadA, dropB), mapOf("b" to remoteB))
        assertEquals(listOf(uploadA), plan.toUpload)
        assertEquals(listOf(dropB), plan.toDrop)
    }

    private fun progress(
        key: String,
        positionMs: Long,
        updatedAt: Long,
        deviceId: String = "car",
        serverReceivedAt: Long? = null,
    ) = EpisodeProgress(
        episodeKey = key,
        positionMs = positionMs,
        durationMs = 60_000,
        updatedAt = updatedAt,
        deviceId = deviceId,
        status = ProgressStatus.PAUSED,
        serverReceivedAt = serverReceivedAt,
    )
}
