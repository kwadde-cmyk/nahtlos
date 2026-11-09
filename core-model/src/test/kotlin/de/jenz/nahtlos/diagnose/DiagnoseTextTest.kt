package de.jenz.nahtlos.diagnose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnoseTextTest {
    @Test
    fun secretsAreRemovedBeforeALogCanBeShared() {
        val raw = "Fehler nostr+walletconnect://abc?secret=ffff token=geheim lnbc1invoicepreimage preimage=0123456789abcdef0123456789abcdef"
        val clean = DiagnoseText.scrub(raw)
        assertFalse(clean.contains("nostr+walletconnect"))
        assertFalse(clean.contains("lnbc1"))
        assertFalse(clean.contains("geheim"))
        assertFalse(clean.contains("0123456789abcdef"))
        assertTrue(clean.contains("[nwc]"))
        assertEquals(null, DiagnoseText.reject(clean))
        assertEquals("Wallet-Link", DiagnoseText.reject("siehe nostr+walletconnect://aa"))
    }
}
