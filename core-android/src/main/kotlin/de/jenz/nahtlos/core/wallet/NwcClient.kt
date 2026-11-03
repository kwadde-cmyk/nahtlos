package de.jenz.nahtlos.core.wallet

import de.jenz.nahtlos.wallet.Nip44
import de.jenz.nahtlos.wallet.NostrEvent
import de.jenz.nahtlos.wallet.NwcRequests
import de.jenz.nahtlos.wallet.NwcUri
import de.jenz.nahtlos.wallet.SecretScrub
import de.jenz.nahtlos.wallet.WalletException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import javax.inject.Inject
import javax.inject.Singleton

class RelayUnreachable(message: String) : Exception(message)

data class WalletProbe(val alias: String, val balanceMsat: Long?, val methods: List<String>, val infoEvent: Boolean)

/**
 * Eine NIP-47-Anfrage pro Aufruf. Relays werden nur versucht, bis eines die Verbindung annimmt.
 * Danach gibt es keinen zweiten Sendeversuch.
 */
@Singleton
class NwcClient @Inject constructor(private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun probe(uri: NwcUri): WalletProbe {
        val infoEvent = readInfoEvent(uri)
        if (infoEvent != null && infoEvent.contains('?')) throw nip04()
        val fromEvent = infoEvent?.split(Regex("[\\s,]+")).orEmpty().filter { it.isNotBlank() }.map { it.lowercase() }
        val info = call(uri, "get_info", NwcRequests.emptyParams(), INFO_TIMEOUT_MS)
        val fromCall = info["methods"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.lowercase() }.orEmpty()
        val methods = (fromEvent + fromCall).distinct()
        val balance = if (methods.isEmpty() || "get_balance" in methods) {
            runCatching { call(uri, "get_balance", NwcRequests.emptyParams(), INFO_TIMEOUT_MS) }
                .getOrNull()?.get("balance")?.jsonPrimitive?.longish()
        } else {
            null
        }
        val alias = info["alias"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: "Wallet"
        return WalletProbe(alias, balance, methods, infoEvent != null)
    }

    suspend fun call(uri: NwcUri, method: String, paramsJson: String, timeoutMs: Long): JsonObject {
        val plain = NwcRequests.envelope(method, paramsJson)
        val content = Nip44.encryptTo(uri.secret, uri.walletPubkey, plain)
        val event = NostrEvent.signed(
            secretHex = uri.secret,
            kind = NwcRequests.REQUEST,
            tags = listOf(listOf("p", uri.walletPubkey)),
            content = content,
            createdAt = System.currentTimeMillis() / 1000,
        )
        var down: Exception? = null
        for (relay in uri.relays) {
            try {
                return exchange(relay, uri, event, timeoutMs)
            } catch (error: RelayUnreachable) {
                down = error
            }
        }
        throw RelayUnreachable(down?.message ?: "Kein Relay erreichbar.")
    }

    private suspend fun readInfoEvent(uri: NwcUri): String? {
        for (relay in uri.relays) {
            val socket = try {
                connect(relay)
            } catch (error: RelayUnreachable) {
                continue
            }
            try {
                val filter = """["REQ","info",{"kinds":[${NwcRequests.INFO}],"authors":["${uri.walletPubkey}"],"limit":1}]"""
                socket.send(filter)
                val deadline = System.nanoTime() + INFO_TIMEOUT_MS * 1_000_000
                while (true) {
                    val left = deadline - System.nanoTime()
                    if (left <= 0) return null
                    val text = withTimeoutOrNull(left / 1_000_000) { socket.incoming.receive() } ?: return null
                    val element = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonArray ?: continue
                    val kind = element.firstOrNull()?.jsonPrimitive?.contentOrNull ?: continue
                    if (kind == "EOSE") return null
                    if (kind != "EVENT") continue
                    val event = element.getOrNull(2)?.jsonObject ?: continue
                    if (event["kind"]?.jsonPrimitive?.intOrNull != NwcRequests.INFO) continue
                    return event["content"]?.jsonPrimitive?.contentOrNull
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                return null
            } finally {
                socket.close()
            }
        }
        return null
    }

    private suspend fun exchange(relay: String, uri: NwcUri, event: de.jenz.nahtlos.wallet.SignedEvent, timeoutMs: Long): JsonObject {
        val socket = connect(relay)
        try {
            socket.send("""["REQ","pay",{"kinds":[${NwcRequests.RESPONSE}],"#e":["${event.id}"],"limit":1}]""")
            socket.send("""["EVENT",${event.toJson()}]""")
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (true) {
                val left = deadline - System.nanoTime()
                if (left <= 0) throw WalletException("Das Wallet hat nicht rechtzeitig geantwortet.")
                val text = try {
                    withTimeoutOrNull((left / 1_000_000).coerceAtLeast(1)) { socket.incoming.receive() }
                        ?: throw WalletException("Das Wallet hat nicht rechtzeitig geantwortet.")
                } catch (error: WalletException) {
                    throw error
                } catch (error: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
                    throw WalletException("Die Verbindung zum Relay ist abgebrochen.")
                }
                val result = takeResponse(text, uri, event.id)
                if (result != null) return result
            }
        } finally {
            socket.close()
        }
    }

    private fun takeResponse(text: String, uri: NwcUri, eventId: String): JsonObject? {
        val element = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonArray ?: return null
        when (element.firstOrNull()?.jsonPrimitive?.contentOrNull) {
            "OK" -> {
                val id = element.getOrNull(1)?.jsonPrimitive?.contentOrNull
                val accepted = element.getOrNull(2)?.jsonPrimitive?.booleanOrNull
                if (accepted == false && (id == null || id == eventId)) {
                    val reason = element.getOrNull(3)?.jsonPrimitive?.contentOrNull
                    throw WalletException(SecretScrub.message(reason, uri.secret).ifBlank { "Das Relay hat die Anfrage abgelehnt." })
                }
            }
            "EVENT" -> {
                val event = element.getOrNull(2)?.jsonObject ?: return null
                if (event["kind"]?.jsonPrimitive?.intOrNull != NwcRequests.RESPONSE) return null
                val matches = event["tags"]?.jsonArray?.any { tag ->
                    val parts = tag.jsonArray
                    parts.getOrNull(0)?.jsonPrimitive?.contentOrNull == "e" &&
                        parts.getOrNull(1)?.jsonPrimitive?.contentOrNull == eventId
                } == true
                if (!matches) return null
                val content = event["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (content.contains('?')) throw nip04()
                val plain = try {
                    Nip44.decryptFrom(uri.secret, uri.walletPubkey, content)
                } catch (error: WalletException) {
                    if (error.message?.contains("NIP-04") == true) throw error
                    throw WalletException("Die Wallet-Antwort ließ sich nicht mit NIP-44 öffnen. NIP-04-Wallets werden nicht unterstützt.")
                }
                return parseResult(plain, uri.secret)
            }
        }
        return null
    }

    private fun parseResult(plain: String, secret: String): JsonObject {
        val obj = json.parseToJsonElement(plain).jsonObject
        val error = obj["error"]
        if (error != null && error !is JsonNull) {
            val message = runCatching { error.jsonObject["message"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                ?: error.jsonPrimitive.contentOrNull
                ?: "Das Wallet hat die Anfrage abgelehnt."
            throw WalletException(SecretScrub.message(message, secret))
        }
        return obj["result"] as? JsonObject ?: JsonObject(emptyMap())
    }

    private suspend fun connect(relay: String): Socket {
        if (!relay.startsWith("wss://") && !relay.startsWith("ws://")) {
            throw RelayUnreachable("Relay nicht erreichbar.")
        }
        val incoming = Channel<String>(Channel.BUFFERED)
        val opened = CompletableDeferred<Unit>()
        val web = http.newWebSocket(Request.Builder().url(relay).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!opened.isCompleted) opened.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                incoming.trySend(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!opened.isCompleted) opened.completeExceptionally(t)
                incoming.close(t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                incoming.close()
            }
        })
        val ready = try {
            withTimeoutOrNull(10_000) { opened.await() }
        } catch (error: kotlinx.coroutines.CancellationException) {
            web.cancel()
            throw error
        } catch (error: Exception) {
            web.cancel()
            throw RelayUnreachable("Relay nicht erreichbar.")
        }
        if (ready == null) {
            web.cancel()
            throw RelayUnreachable("Relay nicht erreichbar.")
        }
        return Socket(web, incoming)
    }

    private class Socket(val web: WebSocket, val incoming: Channel<String>) {
        fun send(text: String) {
            web.send(text)
        }

        fun close() {
            web.close(1000, null)
            incoming.close()
        }
    }

    private fun nip04(): WalletException =
        WalletException("Dieses Wallet spricht nur NIP-04. Nahtlos braucht NIP-44, zum Beispiel Alby Hub.")

    private fun kotlinx.serialization.json.JsonPrimitive.longish(): Long? = longOrNull ?: contentOrNull?.toLongOrNull()

    companion object {
        const val INFO_TIMEOUT_MS = 10_000L
        const val PAY_TIMEOUT_MS = 25_000L
    }
}
