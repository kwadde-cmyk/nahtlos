package de.jenz.nahtlos.nostr

import de.jenz.nahtlos.wallet.Nip44
import de.jenz.nahtlos.wallet.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Nip46AckTest {
    private val client = NostrKeys.generateSecret()
    private val signer = NostrKeys.generateSecret()
    private val clientPub = NostrKeys.pubkey(client)
    private val signerPub = NostrKeys.pubkey(signer)
    private val token = "c0ffee"

    @Test
    fun ackAndSecretBothCountAndAWrongResultDoesNot() {
        val ack = reading(Nip44.encryptTo(signer, clientPub, """{"id":"1","result":"ack"}"""))
        assertEquals("nip44", ack.scheme)
        assertEquals("ack", ack.outcome)
        assertEquals(signerPub, ack.acceptedPubkey)

        val secret = reading(Nip44.encryptTo(signer, clientPub, """{"id":"1","result":"$token"}"""))
        assertEquals("kennwort", secret.outcome)
        assertEquals(signerPub, secret.acceptedPubkey)

        val viaParams = reading(
            Nip44.encryptTo(signer, clientPub, """{"id":"1","method":"connect","params":["$signerPub","$token"]}"""),
        )
        assertEquals("kennwort", viaParams.outcome)
        assertEquals(signerPub, viaParams.acceptedPubkey)

        val rejected = reading(Nip44.encryptTo(signer, clientPub, """{"id":"1","result":"nein"}"""))
        assertEquals("passt-nicht", rejected.outcome)
        assertNull(rejected.acceptedPubkey)
        assertFalse(rejected.outcome.contains(token))
    }

    @Test
    fun nip04AckIsAcceptedWhenNip44DoesNotOpenIt() {
        val payload = Nip04.encrypt(signer, clientPub, """{"id":"1","result":"ack"}""")
        val reading = reading(payload)
        assertEquals("nip04", reading.scheme)
        assertEquals("ack", reading.outcome)
        assertEquals(signerPub, reading.acceptedPubkey)
        assertEquals(signerPub, Nip46Messages.connectAck(client, event(payload), token))
    }

    @Test
    fun brokenCipherAndBadSignatureStayClosed() {
        val broken = reading("nicht-lesbar")
        assertEquals("fehlgeschlagen", broken.scheme)
        assertNull(broken.acceptedPubkey)
        val forged = event(Nip44.encryptTo(signer, clientPub, """{"id":"1","result":"ack"}""")).copy(sig = "ab".repeat(64))
        val reading = Nip46Messages.readConnect(client, forged, token)
        assertEquals("signatur", reading.scheme)
        assertEquals("keins", reading.outcome)
    }

    @Test
    fun timeoutNamesTheStepThatStopped() {
        assertTrue(Nip46Messages.timeoutMessage(0, 0, 0).contains("Kein Relay"))
        assertTrue(Nip46Messages.timeoutMessage(2, 0, 0).contains("nicht an"))
        assertTrue(Nip46Messages.timeoutMessage(2, 1, 0).contains("Entschlüsselung"))
        assertTrue(Nip46Messages.timeoutMessage(2, 1, 1).contains("passte nicht"))
        assertEquals("relay.damus.io", Nip46Messages.relayHost("wss://user:secret@relay.damus.io:443/path"))
        assertFalse(Nip46Messages.relayHost("wss://user:secret@relay.damus.io").contains("secret"))
    }

    private fun reading(payload: String) = Nip46Messages.readConnect(client, event(payload), token)

    private fun event(payload: String) = NostrEvent.signed(
        signer,
        24133,
        listOf(listOf("p", clientPub)),
        payload,
        1_700_000_000,
    )
}
