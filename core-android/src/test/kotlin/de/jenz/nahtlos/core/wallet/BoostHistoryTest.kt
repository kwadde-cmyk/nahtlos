package de.jenz.nahtlos.core.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoostHistoryTest {
    @Test
    fun historyNamesRecipientAmountAndStatusWithoutTheWalletLink() {
        val secret = "nostr+walletconnect://aa?relay=wss://x&secret=bb"
        val preimage = "deadbeef".repeat(8)
        val payment = StoredPayment(
            id = "p1",
            atMs = 1_700_000_000_000,
            podcast = "Nodesignal",
            episode = "Recap",
            totalSats = 210,
            splits = listOf(
                StoredSplit(
                    name = "Studio",
                    type = "node",
                    address = "03abcdef",
                    sats = 200,
                    preimage = preimage,
                    status = "ok",
                ),
                StoredSplit(
                    name = "Fee",
                    type = "lnaddress",
                    address = "fee@example.com",
                    sats = 10,
                    preimage = preimage,
                    fee = true,
                    status = "failed",
                    error = secret,
                    bolt11 = "lnbc1secretinvoice",
                ),
            ),
        )
        val history = payment.toHistory()
        assertEquals("Nodesignal", history.podcast)
        assertEquals("Recap", history.episode)
        assertEquals(210, history.totalSats)
        assertEquals("Studio", history.recipients[0].name)
        assertEquals("03abcdef", history.recipients[0].address)
        assertEquals("gesendet", history.recipients[0].status)
        assertEquals("fehlgeschlagen", history.recipients[1].status)
        assertTrue(history.canRetry)
        val shown = history.recipients.joinToString { "${it.name}|${it.address}|${it.sats}|${it.status}" }
        assertFalse(shown.contains("nostr+walletconnect"))
        assertFalse(shown.contains(preimage))
        assertFalse(shown.contains("lnbc1"))
    }
}
