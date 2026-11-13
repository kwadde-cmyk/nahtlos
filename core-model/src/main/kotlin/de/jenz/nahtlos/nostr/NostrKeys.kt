package de.jenz.nahtlos.nostr

import de.jenz.nahtlos.wallet.Bytes
import de.jenz.nahtlos.wallet.NostrEvent
import de.jenz.nahtlos.wallet.Secp256k1
import de.jenz.nahtlos.wallet.SignedEvent
import java.security.MessageDigest

class NostrException(message: String) : Exception(message)

/**
 * Schlüssel, die nur eine NIP-46-Sitzung verschlüsseln. Das ist nicht der
 * Schlüssel des Hörers. Der bleibt im externen Signer.
 */
object NostrKeys {
    fun generateSecret(): String {
        repeat(8) {
            val hex = Bytes.hex(Bytes.random(32))
            if (runCatching { Secp256k1.publicKey(hex) }.isSuccess) return hex
        }
        error("Kein Sitzungsschlüssel")
    }

    fun pubkey(secretHex: String): String = Secp256k1.publicKey(secretHex)

    fun valid(event: SignedEvent): Boolean {
        if (!event.id.matches(HEX_64) || !event.pubkey.matches(HEX_64) || !event.sig.matches(HEX_128)) return false
        val expected = Bytes.hex(Bytes.sha256(NostrEvent.idPreimage(event.pubkey, event.createdAt, event.kind, event.tags, event.content).toByteArray()))
        if (!MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), event.id.toByteArray(Charsets.UTF_8))) return false
        val signature = runCatching { Bytes.parseHex(event.sig) }.getOrNull() ?: return false
        val hash = runCatching { Bytes.parseHex(event.id) }.getOrNull() ?: return false
        return runCatching { Secp256k1.verify(event.pubkey, hash, signature) }.getOrDefault(false)
    }

    private val HEX_64 = Regex("[0-9a-f]{64}")
    private val HEX_128 = Regex("[0-9a-f]{128}")
}
