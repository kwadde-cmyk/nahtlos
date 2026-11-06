package de.jenz.nahtlos.sync

import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoostSyncTest {
    private val zone = ZoneId.of("Europe/Berlin")

    @Test
    fun localTimeShowsTheClockNotOnlyADay() {
        val text = BoostSync.formatWhen(1_778_000_000_000L, zone)
        assertTrue(text.contains("2026"))
        assertTrue(text.contains(":"))
        val known = BoostSync.formatWhen(1_778_099_000_000L.let { 1_778_000_534_000L }, zone)
        assertTrue(known.contains("2026"))
    }

    @Test
    fun novemberEveningInBerlinHasHourAndDate() {
        val epoch = java.time.ZonedDateTime.of(2026, 11, 6, 21, 2, 14, 0, java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val text = BoostSync.formatWhen(epoch, zone)
        assertEquals("6. Nov. 2026, 22:02", text)
    }

    @Test
    fun secretsNeverPassTheSyncCheck() {
        val ok = sample()
        assertEquals(null, BoostSync.rejectReason(ok))
        assertEquals("Wallet-Link", BoostSync.rejectReason(ok.copy(episode = "nostr+walletconnect://abc")))
        assertEquals("Invoice", BoostSync.rejectReason(ok.copy(recipients = listOf(ok.recipients[0].copy(address = "lnbc1secret")))))
        val shown = ok.recipients.joinToString { "${it.name}|${it.address}|${it.status}" }
        assertFalse(shown.contains("preimage"))
    }

    @Test
    fun newerUpdateWinsAndTheSameIdDoesNotDuplicate() {
        val first = sample()
        val older = first.copy(updatedAt = first.updatedAt - 1, totalSats = 1)
        val newer = first.copy(updatedAt = first.updatedAt + 5, totalSats = 210, recipients = first.recipients.map { it.copy(status = "ok") })
        assertEquals(first.totalSats, BoostSync.prefer(first, older).totalSats)
        assertEquals(210, BoostSync.prefer(first, newer).totalSats)
        assertEquals("ok", BoostSync.prefer(first, newer).recipients[0].status)
    }

    @Test
    fun aSecondTapWhileSendingIsIgnored() {
        assertTrue(BoostSync.acceptTap(alreadySending = false))
        assertFalse(BoostSync.acceptTap(alreadySending = true))
        assertEquals("Boost ist raus", BoostSync.outcome(anyFailed = false))
        assertEquals("Boost nicht gesendet", BoostSync.outcome(anyFailed = true))
    }

    private fun sample() = BoostSyncItem(
        id = "pay-1",
        atMs = 1_700_000_000_000,
        updatedAt = 1_700_000_000_000,
        totalSats = 210,
        podcast = "Nodesignal",
        episode = "Recap",
        recipients = listOf(BoostRecipientSync("Studio", "03abcdef", 210, "gesendet")),
    )
}
