package de.jenz.nahtlos.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingLinkTest {
    @Test
    fun roundTripKeepsServerAndCode() {
        val link = PairingLink.encode("https://podcast.example.com/", "k7p4qx")
        val target = PairingLink.decode(link)
        assertEquals("https://podcast.example.com", target?.server)
        assertEquals("K7P-4QX", target?.code)
        assertTrue(link.startsWith("nahtlos://pair?"))
    }

    @Test
    fun rejectsAmbiguousOrShortCodes() {
        assertEquals("K7P4QX", PairingCodes.normalize("k7p-4qx"))
        assertEquals(false, PairingCodes.isWellFormed("K7P-4Q"))
        assertEquals(false, PairingCodes.isWellFormed("O0I1L2"))
        assertNull(PairingLink.decode("https://example.com"))
        assertNull(PairingLink.decode("nahtlos://pair?server=ftp://x&code=K7P-4QX"))
    }
}
