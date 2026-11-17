package de.jenz.nahtlos.sync

import de.jenz.nahtlos.model.NahtlosJson
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
        assertEquals("Boost gesendet", BoostSync.outcome(anyFailed = false))
        assertEquals("Boost nicht gesendet", BoostSync.outcome(anyFailed = true))
    }

    @Test
    fun messageSyncKeepsTextAndRejectsSecretsAndLength() {
        val withNote = sample().copy(message = "Danke für die Folge", senderName = "Ada")
        assertEquals(null, BoostSync.rejectReason(withNote))
        assertEquals("Wallet-Link", BoostSync.rejectReason(withNote.copy(message = "nostr+walletconnect://aa")))
        assertEquals("Invoice", BoostSync.rejectReason(withNote.copy(senderName = "lnbc1invoice")))
        assertEquals("Privater Schlüssel", BoostSync.rejectReason(withNote.copy(message = "hier nsec1secret")))
        assertEquals("Nachricht zu lang", BoostSync.rejectReason(withNote.copy(message = "x".repeat(501))))
        val kept = BoostSync.retainMessage(withNote, withNote.copy(updatedAt = withNote.updatedAt + 5, message = "", totalSats = 21))
        assertEquals("Danke für die Folge", kept.message)
        assertEquals("Ada", kept.senderName)
        assertEquals(21, kept.totalSats)
        val replaced = BoostSync.retainMessage(withNote, withNote.copy(updatedAt = withNote.updatedAt + 5, message = "Neu"))
        assertEquals("Neu", replaced.message)
        val oldClient = NahtlosJson.json.decodeFromString<BoostSyncItem>(
            """{"id":"pay-1","atMs":1,"updatedAt":1,"totalSats":1,"podcast":"A","episode":"B","recipients":[]}""",
        )
        assertEquals("", oldClient.message)
        assertEquals("", oldClient.senderName)
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
