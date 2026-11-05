package de.jenz.nahtlos.wallet

import de.jenz.nahtlos.sync.PairingCodes
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.hpke.HPKE
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/**
 * Handy schickt den NWC-Link ans Auto, ohne dass der Server ihn lesen kann.
 *
 * Das Auto erzeugt ein HPKE-Empfängerschlüsselpaar (X25519, HKDF-SHA256, AES-128-GCM)
 * und zeigt den öffentlichen Schlüssel nur als QR. Der Server sieht den Schlüssel nie,
 * deshalb kann er ihn nicht gegen seinen eigenen tauschen. Das Handy verschlüsselt erst,
 * nachdem der Kurzcode auf beiden Schirmen verglichen wurde. Der Klartext bleibt lokal.
 */
object WalletRelay {
    const val TTL_SECONDS = 300
    const val MAX_URI_CHARS = 2048
    private val INFO = "nahtlos-wallet-v1".toByteArray(Charsets.UTF_8)

    fun newOffer(deviceId: String): Offer {
        require(deviceId.isNotBlank()) { "Geräte-ID fehlt" }
        val key = HeldKey.generate()
        val sessionId = UUID.randomUUID().toString()
        return Offer(
            sessionId = sessionId,
            deviceId = deviceId,
            publicKey = key.publicKey.copyOf(),
            fingerprint = fingerprint(sessionId, key.publicKey),
            key = key,
        )
    }

    fun parseOffer(raw: String): OfferLink? {
        val text = raw.trim()
        if (!text.startsWith("nahtlos://wallet")) return null
        val query = text.substringAfter('?', "")
        if (query.isEmpty()) return null
        val params = mutableMapOf<String, String>()
        for (part in query.split('&')) {
            val index = part.indexOf('=')
            if (index <= 0) continue
            params[part.substring(0, index)] = java.net.URLDecoder.decode(part.substring(index + 1), Charsets.UTF_8)
        }
        if (params["v"] != "1") return null
        val sessionId = params["s"]?.trim().orEmpty()
        val deviceId = params["d"]?.trim().orEmpty()
        val keyHex = params["k"]?.trim().orEmpty()
        if (!sessionId.matches(UUID_RE) || deviceId.isEmpty() || deviceId.length > 80) return null
        val publicKey = runCatching { Bytes.parseHex(keyHex) }.getOrNull() ?: return null
        if (publicKey.size != 32) return null
        return OfferLink(sessionId, deviceId, publicKey, fingerprint(sessionId, publicKey))
    }

    fun link(offer: OfferLink): String {
        val key = Bytes.hex(offer.publicKey)
        return "nahtlos://wallet?v=1&s=${offer.sessionId}&d=${encode(offer.deviceId)}&k=$key"
    }

    fun fingerprint(sessionId: String, publicKey: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(
            Bytes.concat("nahtlos-wallet-fp-v1".toByteArray(), sessionId.toByteArray(), publicKey),
        )
        val alphabet = PairingCodes.ALPHABET
        val raw = CharArray(6) { index -> alphabet[(digest[index].toInt() and 0xff) % alphabet.length] }
        return PairingCodes.format(String(raw))
    }

    fun aad(sessionId: String, deviceId: String): ByteArray =
        "nahtlos-wallet-v1\n$sessionId\n$deviceId".toByteArray(Charsets.UTF_8)

    /** Verschlüsselt nur nach ausdrücklichem Code-Vergleich. Der Rückgabewert ist Base64, kein Klartext. */
    fun seal(offer: OfferLink, uri: String, confirmedFingerprint: String): String {
        if (confirmedFingerprint != offer.fingerprint) {
            throw WalletException("Der Code stimmt nicht mit dem Auto überein.")
        }
        val clean = uri.trim()
        if (clean.length > MAX_URI_CHARS || clean.contains('\n') || clean.contains('\r')) {
            throw WalletException("Der Wallet-Link ist unbrauchbar.")
        }
        if (!clean.startsWith("nostr+walletconnect://")) {
            throw WalletException("Das ist kein NWC-Link.")
        }
        val boxed = HeldKey.seal(offer.publicKey, aad(offer.sessionId, offer.deviceId), clean.toByteArray(Charsets.UTF_8))
        if (String(boxed, Charsets.ISO_8859_1).contains("nostr+walletconnect")) {
            throw WalletException("Die Verschlüsselung hat den Link nicht verborgen.")
        }
        return Base64.getEncoder().encodeToString(boxed)
    }

    fun open(key: HeldKey, offer: OfferLink, ciphertextBase64: String): String {
        val boxed = try {
            Base64.getDecoder().decode(ciphertextBase64)
        } catch (_: IllegalArgumentException) {
            throw WalletException("Die Sendung ist beschädigt.")
        }
        val plain = try {
            key.open(aad(offer.sessionId, offer.deviceId), boxed)
        } catch (gone: IllegalStateException) {
            throw gone
        } catch (_: Exception) {
            throw WalletException("Die Sendung lässt sich nicht öffnen.")
        }
        val uri = plain.toString(Charsets.UTF_8).trim()
        plain.fill(0)
        if (!uri.startsWith("nostr+walletconnect://") || uri.length > MAX_URI_CHARS) {
            throw WalletException("Die Sendung enthält keinen Wallet-Link.")
        }
        return uri
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8)

    private val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
}

data class OfferLink(
    val sessionId: String,
    val deviceId: String,
    val publicKey: ByteArray,
    val fingerprint: String,
)

class Offer(
    val sessionId: String,
    val deviceId: String,
    val publicKey: ByteArray,
    val fingerprint: String,
    private val key: HeldKey,
) {
    fun link(): OfferLink = OfferLink(sessionId, deviceId, publicKey, fingerprint)

    fun qr(): String = WalletRelay.link(link())

    fun open(ciphertextBase64: String): String = WalletRelay.open(key, link(), ciphertextBase64)

    fun openForDevice(deviceId: String, ciphertextBase64: String): String =
        WalletRelay.open(key, OfferLink(sessionId, deviceId, publicKey, fingerprint), ciphertextBase64)

    fun wipe() = key.wipe()
}

/** Privater Schlüssel bleibt im Speicher des Autos und wird nach Gebrauch genullt. */
class HeldKey private constructor(
    val publicKey: ByteArray,
    private var pair: AsymmetricCipherKeyPair?,
) {
    fun open(aad: ByteArray, ciphertext: ByteArray): ByteArray {
        val current = pair ?: error("Schlüssel ist schon gelöscht")
        val engine = hpke()
        val encSize = engine.getEncSize()
        require(ciphertext.size > encSize + 16) { "Sendung ist zu kurz" }
        val context = engine.setupBaseR(ciphertext.copyOfRange(0, encSize), current, INFO)
        return context.open(aad, ciphertext.copyOfRange(encSize, ciphertext.size))
    }

    fun wipe() {
        pair = null
    }

    companion object {
        private val INFO = "nahtlos-wallet-v1".toByteArray(Charsets.UTF_8)

        fun generate(): HeldKey {
            val engine = hpke()
            val pair = engine.generatePrivateKey()
            return HeldKey(engine.serializePublicKey(pair.public), pair)
        }

        fun seal(publicKey: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
            val engine = hpke()
            val context = engine.setupBaseS(engine.deserializePublicKey(publicKey), INFO)
            return Bytes.concat(context.encapsulation, context.seal(aad, plaintext))
        }

        private fun hpke() = HPKE(
            HPKE.mode_base,
            HPKE.kem_X25519_SHA256,
            HPKE.kdf_HKDF_SHA256,
            HPKE.aead_AES_GCM128,
        )
    }
}
