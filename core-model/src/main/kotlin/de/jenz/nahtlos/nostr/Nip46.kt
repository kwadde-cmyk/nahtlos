package de.jenz.nahtlos.nostr

import de.jenz.nahtlos.wallet.Bytes
import de.jenz.nahtlos.wallet.Nip44
import de.jenz.nahtlos.wallet.NostrEvent
import de.jenz.nahtlos.wallet.SignedEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * `nostrconnect://` fürs Auto. Der private Sitzungsschlüssel bleibt auf dem
 * Gerät, das die URI erzeugt. In der URI stehen nur der öffentliche Schlüssel,
 * die Relays und ein kurzes Kennwort, damit ein fremder Signer die Sitzung
 * nicht übernimmt. Das Kennwort ist kein nsec.
 */
data class NostrConnectOffer(
    val clientSecret: String,
    val clientPubkey: String,
    val relays: List<String>,
    val authToken: String,
    val perms: String,
    val name: String,
) {
    val uri: String
        get() = buildString {
            append("nostrconnect://").append(clientPubkey).append('?')
            append(relays.joinToString("&") { "relay=" + encode(it) })
            append("&secret=").append(authToken)
            append("&perms=").append(encode(perms))
            append("&name=").append(encode(name))
        }

    override fun toString(): String = "nostrconnect:${clientPubkey.take(8)}… relays=${relays.size}"

    companion object {
        const val PERMS = "sign_event:1,sign_event:1111,sign_event:7,sign_event:9734"

        fun create(relays: List<String>, name: String = "Nahtlos Auto"): NostrConnectOffer {
            val secret = NostrKeys.generateSecret()
            return NostrConnectOffer(
                clientSecret = secret,
                clientPubkey = NostrKeys.pubkey(secret),
                relays = relays.map { it.trimEnd('/') }.distinct().take(4),
                authToken = Bytes.hex(Bytes.random(8)),
                perms = PERMS,
                name = name,
            )
        }

        fun parse(raw: String): NostrConnectOffer? {
            val text = raw.trim()
            if (!text.startsWith("nostrconnect://")) return null
            val body = text.removePrefix("nostrconnect://")
            val pubkey = body.substringBefore('?').trim().lowercase()
            if (!pubkey.matches(HEX_64)) return null
            val params = query(body.substringAfter('?', ""))
            val relays = params.filter { it.first == "relay" }.map { it.second.trim().trimEnd('/') }
                .filter { it.startsWith("wss://") || it.startsWith("ws://") }
            val token = params.firstOrNull { it.first == "secret" }?.second?.trim().orEmpty()
            if (relays.isEmpty() || token.isEmpty() || token.length > 128) return null
            return NostrConnectOffer(
                clientSecret = "",
                clientPubkey = pubkey,
                relays = relays,
                authToken = token,
                perms = params.firstOrNull { it.first == "perms" }?.second ?: PERMS,
                name = params.firstOrNull { it.first == "name" }?.second ?: "Nahtlos Auto",
            )
        }

        private fun query(raw: String): List<Pair<String, String>> = raw.split('&').mapNotNull { part ->
            if (part.isBlank()) return@mapNotNull null
            val eq = part.indexOf('=')
            val key = decode(if (eq >= 0) part.substring(0, eq) else part)
            val value = decode(if (eq >= 0) part.substring(eq + 1) else "")
            key to value
        }

        private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)
        private fun decode(value: String): String = URLDecoder.decode(value, Charsets.UTF_8)
        private val HEX_64 = Regex("[0-9a-fA-F]{64}")
    }
}

/** `bunker://` , falls ein Signer die Verbindung selbst ausstellt. Enthält keinen nsec. */
data class BunkerUri(val signerPubkey: String, val relays: List<String>, val authToken: String?) {
    override fun toString(): String = "bunker:${signerPubkey.take(8)}… relays=${relays.size}"

    companion object {
        fun parse(raw: String): BunkerUri? {
            val text = raw.trim()
            if (!text.startsWith("bunker://")) return null
            val body = text.removePrefix("bunker://")
            val pubkey = body.substringBefore('?').substringBefore('/').trim().lowercase()
            if (!pubkey.matches(Regex("[0-9a-fA-F]{64}"))) return null
            val params = body.substringAfter('?', "").split('&').mapNotNull { part ->
                if (part.isBlank()) return@mapNotNull null
                val eq = part.indexOf('=')
                val key = URLDecoder.decode(if (eq >= 0) part.substring(0, eq) else part, Charsets.UTF_8)
                val value = URLDecoder.decode(if (eq >= 0) part.substring(eq + 1) else "", Charsets.UTF_8)
                key to value
            }
            val relays = params.filter { it.first == "relay" }.map { it.second.trim().trimEnd('/') }
                .filter { it.startsWith("wss://") || it.startsWith("ws://") }
            if (relays.isEmpty()) return null
            return BunkerUri(pubkey, relays, params.firstOrNull { it.first == "secret" }?.second?.takeIf { it.isNotBlank() })
        }
    }
}

object Nip46Messages {
    private val json = Json { ignoreUnknownKeys = true }

    fun requestJson(id: String, method: String, params: List<String>): String {
        val encoded = params.joinToString(",") { NostrEvent.jsonString(it) }
        return """{"id":${NostrEvent.jsonString(id)},"method":${NostrEvent.jsonString(method)},"params":[$encoded]}"""
    }

    fun wrap(clientSecret: String, remotePubkey: String, plaintext: String, createdAt: Long): SignedEvent =
        NostrEvent.signed(
            clientSecret,
            PodcastNostr.KIND_NIP46,
            listOf(listOf("p", remotePubkey)),
            Nip44.encryptTo(clientSecret, remotePubkey, plaintext),
            createdAt,
        )

    /** Relays aus dem Code zuerst, danach ein paar, auf die Amber sonst ausweicht. */
    fun listenRelays(offerRelays: List<String>): List<String> {
        val extra = listOf(
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.nostr.band",
            "wss://relay.primal.net",
            "wss://relay.nsec.app",
        )
        return (offerRelays + extra).map { it.trim().trimEnd('/') }.filter { it.startsWith("wss://") || it.startsWith("ws://") }
            .distinct()
            .take(6)
    }

    fun relayHost(url: String): String {
        val rest = url.substringAfter("://", url).substringBefore('/').substringBefore('?')
        val named = if ('@' in rest) rest.substringAfterLast('@') else rest
        return named.substringBefore(':').ifBlank { "relay" }
    }

    /** Klartext einer Antwort vom Signer, oder null wenn es nicht unsere ist. */
    fun open(clientSecret: String, event: SignedEvent, expectedId: String? = null): Nip46Reply? {
        if (event.kind != PodcastNostr.KIND_NIP46 || !NostrKeys.valid(event)) return null
        val plain = decrypt(clientSecret, event).text ?: return null
        return parseReply(plain, event.pubkey, expectedId)
    }

    /**
     * Was eine Connect-Antwort taugt, ohne den Klartext nach außen zu geben.
     * Amber schickt `ack` oder das Kennwort aus `nostrconnect://`. Beides gilt.
     * NIP-44 zuerst, NIP-04 nur wenn das nicht aufgeht.
     */
    fun readConnect(clientSecret: String, event: SignedEvent, authToken: String): ConnectReading {
        if (event.kind != PodcastNostr.KIND_NIP46) return ConnectReading(null, "falsches-kind", "keins")
        if (!NostrKeys.valid(event)) return ConnectReading(null, "signatur", "keins")
        val opened = decrypt(clientSecret, event)
        val plain = opened.text ?: return ConnectReading(null, "fehlgeschlagen", "keins")
        val reply = parseReply(plain, event.pubkey) ?: return ConnectReading(null, opened.scheme, "kein-json")
        val outcome = outcome(reply, plain, authToken)
        val accepted = event.pubkey.takeIf { outcome == "ack" || outcome == "kennwort" }
        return ConnectReading(accepted, opened.scheme, outcome)
    }

    fun connectAck(clientSecret: String, event: SignedEvent, authToken: String): String? =
        readConnect(clientSecret, event, authToken).acceptedPubkey

    fun timeoutMessage(relaysUp: Int, events: Int, opened: Int): String = when {
        relaysUp <= 0 -> "Kein Relay erreichbar. Das Auto hat den Code gezeigt, aber keine Verbindung bekommen."
        events <= 0 -> "Zeit abgelaufen. Die Relays waren verbunden, eine Antwort von Amber kam nicht an."
        opened <= 0 -> "Zeit abgelaufen. Ein Event kam an, die Entschlüsselung ist fehlgeschlagen."
        else -> "Zeit abgelaufen. Amber hat geantwortet, die Bestätigung passte nicht zu diesem Code."
    }

    fun unsignedForSigner(draft: NostrDraft): String = buildString {
        append("{\"kind\":${draft.kind}")
        append(",\"created_at\":${draft.createdAt}")
        append(",\"content\":${NostrEvent.jsonString(draft.content)}")
        val tags = draft.tags.joinToString(",") { tag ->
            tag.joinToString(",", prefix = "[", postfix = "]") { NostrEvent.jsonString(it) }
        }
        append(",\"tags\":[$tags]}")
    }

    private fun decrypt(clientSecret: String, event: SignedEvent): Opened {
        runCatching { Nip44.decryptFrom(clientSecret, event.pubkey, event.content) }.getOrNull()?.let {
            return Opened("nip44", it)
        }
        runCatching { Nip04.decrypt(clientSecret, event.pubkey, event.content) }.getOrNull()?.let {
            return Opened("nip04", it)
        }
        return Opened("fehlgeschlagen", null)
    }

    private fun parseReply(plain: String, signerPubkey: String, expectedId: String? = null): Nip46Reply? {
        val trimmed = plain.trim()
        if (!trimmed.startsWith("{")) {
            return Nip46Reply(null, null, trimmed, null, emptyList(), signerPubkey)
        }
        val obj = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: return null
        val id = obj.string("id")
        if (expectedId != null && id != expectedId) return null
        val result = obj["result"]?.let { element ->
            when (element) {
                is JsonPrimitive -> element.contentOrNull
                else -> element.toString()
            }
        }
        return Nip46Reply(
            id = id,
            method = obj.string("method"),
            result = result,
            error = obj.string("error"),
            params = obj["params"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
            signerPubkey = signerPubkey,
        )
    }

    private fun outcome(reply: Nip46Reply, plain: String, authToken: String): String {
        val result = reply.result?.trim().orEmpty()
        val token = authToken.trim()
        if (result.equals("ack", ignoreCase = true) || plain.trim().equals("ack", ignoreCase = true)) return "ack"
        if (token.isNotEmpty() && (result == token || plain.trim() == token || reply.id == token || reply.params.any { it == token })) {
            return "kennwort"
        }
        return "passt-nicht"
    }

    private fun JsonObject.string(name: String): String? =
        this[name]?.jsonPrimitive?.contentOrNull

    private data class Opened(val scheme: String, val text: String?)
}

/** Ergebnis einer Connect-Antwort. [acceptedPubkey] ist leer, wenn sie nicht gilt. Die Felder enthalten kein Geheimnis. */
data class ConnectReading(
    val acceptedPubkey: String?,
    val scheme: String,
    val outcome: String,
)

data class Nip46Reply(
    val id: String?,
    val method: String?,
    val result: String?,
    val error: String?,
    val params: List<String>,
    val signerPubkey: String,
)
