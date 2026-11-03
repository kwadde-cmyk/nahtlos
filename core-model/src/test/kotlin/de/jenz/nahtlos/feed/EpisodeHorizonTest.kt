package de.jenz.nahtlos.feed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EpisodeHorizonTest {
    @Test
    fun defaultIsFourWeeksAndAllHasNoCutoff() {
        assertEquals(EpisodeHorizon.WEEK_4, EpisodeHorizon.fromId(null))
        assertEquals(EpisodeHorizon.WEEK_4, EpisodeHorizon.fromId("nope"))
        val now = 1_700_000_000_000L
        assertEquals(now - 28L * 86_400_000L, EpisodeHorizon.WEEK_4.cutoffEpochMs(now))
        assertNull(EpisodeHorizon.ALL.cutoffEpochMs(now))
        assertEquals(
            listOf("1 Woche", "2 Wochen", "4 Wochen", "3 Monate", "6 Monate", "1 Jahr", "Alle"),
            EpisodeHorizon.entries.map { it.label },
        )
    }
}
