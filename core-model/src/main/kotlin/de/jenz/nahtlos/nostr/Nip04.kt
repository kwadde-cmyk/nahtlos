package de.jenz.nahtlos.nostr

import de.jenz.nahtlos.wallet.Bytes
import de.jenz.nahtlos.wallet.Secp256k1
import de.jenz.nahtlos.wallet.WalletException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * NIP-04, nur als Rückfall für NIP-46. NWC bleibt bei NIP-44.
 * Schlüssel ist die x-Koordinate, AES-256-CBC, Payload `base64?iv=base64`.
 */
object Nip04 {
    fun encrypt(secretHex: String, remotePublicHex: String, plaintext: String, iv: ByteArray = Bytes.random(16)): String {
        if (iv.size != 16) throw WalletException("NIP-04-IV muss 16 Byte haben")
        val encrypted = cipher(Cipher.ENCRYPT_MODE, secretHex, remotePublicHex, iv)
            .doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(encrypted) + "?iv=" + Base64.getEncoder().encodeToString(iv)
    }

    fun decrypt(secretHex: String, remotePublicHex: String, payload: String): String {
        val parts = payload.trim().split("?iv=", limit = 2)
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) throw WalletException("kein NIP-04")
        val iv = decode(parts[1])
        val data = decode(parts[0])
        if (iv.size != 16) throw WalletException("NIP-04-IV muss 16 Byte haben")
        val plain = cipher(Cipher.DECRYPT_MODE, secretHex, remotePublicHex, iv).doFinal(data)
        return String(plain, Charsets.UTF_8)
    }

    private fun cipher(mode: Int, secretHex: String, remotePublicHex: String, iv: ByteArray): Cipher {
        val key = Secp256k1.sharedX(secretHex, remotePublicHex)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher
    }

    private fun decode(value: String): ByteArray {
        val clean = value.trim().replace("\\s".toRegex(), "")
        val padded = when (clean.length % 4) {
            2 -> "$clean=="
            3 -> "$clean="
            else -> clean
        }
        return Base64.getDecoder().decode(padded)
    }
}
