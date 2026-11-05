package de.jenz.nahtlos.wallet

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WalletRelayTest {
    private val uri = "nostr+walletconnect://" + "ab".repeat(32) +
        "?relay=wss://relay.example&secret=" + "cd".repeat(32)

    @Test
    fun qrCarriesTheKeyAndNotTheWalletLink() {
        val offer = WalletRelay.newOffer("car-1")
        val text = offer.qr()
        assertTrue(text.startsWith("nahtlos://wallet?v=1&"))
        assertFalse(text.contains("nostr+walletconnect"))
        assertFalse(text.contains("secret"))
        val parsed = WalletRelay.parseOffer(text)
        assertEquals(offer.sessionId, parsed?.sessionId)
        assertEquals(offer.deviceId, parsed?.deviceId)
        assertEquals(offer.fingerprint, parsed?.fingerprint)
        assertTrue(parsed!!.publicKey.contentEquals(offer.publicKey))
        offer.wipe()
    }

    @Test
    fun sealNeedsTheMatchingCodeAndHidesTheLink() {
        val offer = WalletRelay.newOffer("car-1")
        assertFailsWith<WalletException> {
            WalletRelay.seal(offer.link(), uri, confirmedFingerprint = "AAAA-AA")
        }
        val boxed = WalletRelay.seal(offer.link(), uri, offer.fingerprint)
        val raw = Base64.getDecoder().decode(boxed)
        assertFalse(String(raw, Charsets.ISO_8859_1).contains("nostr+walletconnect"))
        assertFalse(boxed.contains("secret="))
        assertEquals(uri, offer.open(boxed))
        offer.wipe()
    }

    @Test
    fun anotherKeyOrAnotherDeviceCannotOpenIt() {
        val car = WalletRelay.newOffer("car-1")
        val boxed = WalletRelay.seal(car.link(), uri, car.fingerprint)
        val other = WalletRelay.newOffer("car-2")
        assertFailsWith<WalletException> { other.open(boxed) }
        assertFailsWith<WalletException> { car.openForDevice("car-9", boxed) }
        assertNotEquals(car.fingerprint, other.fingerprint)
        car.wipe()
        other.wipe()
    }

    @Test
    fun wipedKeyCannotOpen() {
        val car = WalletRelay.newOffer("car-1")
        val boxed = WalletRelay.seal(car.link(), uri, car.fingerprint)
        car.wipe()
        assertFailsWith<IllegalStateException> { car.open(boxed) }
    }
}
