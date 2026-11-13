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

    /** Klartext einer Antwort vom Signer, oder null wenn es nicht unsere ist. */
    fun open(clientSecret: String, event: SignedEvent, expectedId: String? = null): Nip46Reply? {
        if (event.kind != PodcastNostr.KIND_NIP46 || !NostrKeys.valid(event)) return null
        val plain = runCatching { Nip44.decryptFrom(clientSecret, event.pubkey, event.content) }.getOrNull() ?: return null
        val obj = runCatching { json.parseToJsonElement(plain).jsonObject }.getOrNull() ?: return null
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
            signerPubkey = event.pubkey,
        )
    }

    /**
     * Amber bestätigt `nostrconnect`, indem das Ergebnis dem Kennwort entspricht
     * oder ein Connect-Aufruf das Kennwort trägt. Der Autor des Events ist der Signer.
     */
    fun connectAck(clientSecret: String, event: SignedEvent, authToken: String): String? {
        val reply = open(clientSecret, event) ?: return null
        val confirmed = reply.result == authToken || reply.params.any { it == authToken }
        return reply.signerPubkey.takeIf { confirmed }
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

    private fun JsonObject.string(name: String): String? =
        this[name]?.jsonPrimitive?.contentOrNull
}

data class Nip46Reply(
    val id: String?,
    val method: String?,
    val result: String?,
    val error: String?,
    val params: List<String>,
    val signerPubkey: String,
)
