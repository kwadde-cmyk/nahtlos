package de.jenz.nahtlos.nostr

import de.jenz.nahtlos.wallet.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NostrQueueTest {
    @Test
    fun secretsAndForeignKindsNeverEnterTheQueue() {
        assertEquals("Privater Schlüssel", NostrQueue.reject("hier nsec1abc"))
        assertEquals("Bunker", NostrQueue.reject("bunker://aa"))
        assertEquals("Nostr-Verbindung", NostrQueue.reject("nostrconnect://aa"))
        assertEquals("Wallet-Link", NostrQueue.reject("nostr+walletconnect://aa"))
        assertEquals("Invoice", NostrQueue.reject("lnbc1invoice"))
        assertNull(NostrQueue.reject("Ein normaler Kommentar"))
        assertEquals("Diese Art wird nicht signiert", NostrQueue.checkDraft("11111111-1111-1111-1111-111111111111", 3, "x", 10))
        assertEquals(
            "Kommentar ist leer",
            NostrQueue.checkDraft("11111111-1111-1111-1111-111111111111", 1111, "  ", 10),
        )
    }

    @Test
    fun aSignatureMustMatchTheDraftTheCarWrote() {
        val secret = NostrKeys.generateSecret()
        val tags = listOf(listOf("i", "podcast:item:guid:abc"))
        val event = NostrEvent.signed(secret, 7, tags, "+", 20)
        assertTrue(NostrQueue.sameRequest(7, "+", 20, tags, event))
        assertTrue(!NostrQueue.sameRequest(7, "anders", 20, tags, event))
    }
}
