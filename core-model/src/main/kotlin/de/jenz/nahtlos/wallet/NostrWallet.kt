package de.jenz.nahtlos.wallet

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** nostr+walletconnect://pubkey?relay=wss://…&secret=… — das Secret steht nie in [toString]. */
data class NwcUri(
    val walletPubkey: String,
    val relays: List<String>,
    val secret: String,
) {
    val clientPubkey: String get() = Secp256k1.publicKey(secret)

    override fun toString(): String = "nwc:${walletPubkey.take(8)}… relays=${relays.size}"

    companion object {
        fun parse(raw: String): NwcUri {
            val trimmed = raw.trim()
            val prefix = "nostr+walletconnect://"
            if (!trimmed.startsWith(prefix, ignoreCase = true)) {
                throw WalletException("Das ist kein NWC-Link. Er beginnt mit nostr+walletconnect://")
            }
            val rest = trimmed.substring(prefix.length)
            val queryAt = rest.indexOf('?')
            val pubkey = (if (queryAt >= 0) rest.substring(0, queryAt) else rest).trim()
            if (!pubkey.matches(HEX_64)) throw WalletException("Der Wallet-Schlüssel im Link ist ungültig.")
            val relays = mutableListOf<String>()
            var secret: String? = null
            val query = if (queryAt >= 0) rest.substring(queryAt + 1) else ""
            for (part in query.split('&')) {
                if (part.isBlank()) continue
                val eq = part.indexOf('=')
                val key = decode(if (eq >= 0) part.substring(0, eq) else part)
                val value = decode(if (eq >= 0) part.substring(eq + 1) else "")
                when (key) {
                    "relay" -> if (value.startsWith("wss://") || value.startsWith("ws://")) relays += value
                    "secret" -> secret = value.lowercase()
                }
            }
            val clientSecret = secret ?: throw WalletException("Im Link fehlt das Secret.")
            if (!clientSecret.matches(HEX_64)) throw WalletException("Das Secret im Link ist ungültig.")
            if (relays.isEmpty()) throw WalletException("Im Link fehlt ein Relay (wss://…).")
            return NwcUri(pubkey.lowercase(), relays, clientSecret)
        }

        private fun decode(value: String): String =
            URLDecoder.decode(value, StandardCharsets.UTF_8)

        private val HEX_64 = Regex("[0-9a-fA-F]{64}")
    }
}

object NostrEvent {
    fun signed(
        secretHex: String,
        kind: Int,
        tags: List<List<String>>,
        content: String,
        createdAt: Long,
        aux: ByteArray = Bytes.random(32),
    ): SignedEvent {
        val pubkey = Secp256k1.publicKey(secretHex)
        val preimage = idPreimage(pubkey, createdAt, kind, tags, content)
        val id = Bytes.hex(Bytes.sha256(preimage.toByteArray(Charsets.UTF_8)))
        val sig = Bytes.hex(Secp256k1.sign(secretHex, Bytes.parseHex(id), aux))
        return SignedEvent(id, pubkey, createdAt, kind, tags, content, sig)
    }

    fun idPreimage(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): String {
        val tagsJson = tags.joinToString(",", prefix = "[", postfix = "]") { tag ->
            tag.joinToString(",", prefix = "[", postfix = "]") { jsonString(it) }
        }
        return "[0,${jsonString(pubkey)},$createdAt,$kind,$tagsJson,${jsonString(content)}]"
    }

    fun jsonString(value: String): String {
        val out = StringBuilder(value.length + 2)
        out.append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch.code < 0x20) out.append("\\u%04x".format(ch.code)) else out.append(ch)
            }
        }
        out.append('"')
        return out.toString()
    }
}

data class SignedEvent(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
) {
    fun toJson(): String {
        val tagsJson = tags.joinToString(",", prefix = "[", postfix = "]") { tag ->
            tag.joinToString(",", prefix = "[", postfix = "]") { NostrEvent.jsonString(it) }
        }
        return buildString {
            append("{\"id\":${NostrEvent.jsonString(id)}")
            append(",\"pubkey\":${NostrEvent.jsonString(pubkey)}")
            append(",\"created_at\":$createdAt")
            append(",\"kind\":$kind")
            append(",\"tags\":$tagsJson")
            append(",\"content\":${NostrEvent.jsonString(content)}")
            append(",\"sig\":${NostrEvent.jsonString(sig)}}")
        }
    }
}

object SecretScrub {
    fun message(text: String?, secret: String? = null): String {
        var clean = de.jenz.nahtlos.diagnose.DiagnoseText.scrub(text.orEmpty())
        if (!secret.isNullOrBlank() && secret.length >= 8) clean = clean.replace(secret, "[entfernt]")
        return clean.take(280).ifBlank { "Unbekannter Wallet-Fehler" }
    }
}
