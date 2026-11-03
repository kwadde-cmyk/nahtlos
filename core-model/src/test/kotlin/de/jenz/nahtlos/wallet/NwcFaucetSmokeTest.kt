package de.jenz.nahtlos.wallet

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Ein Wegwerf-Wallet von faucet.nwc.dev. Der Connection-String bleibt im Test
 * und wird nicht mitgeschrieben.
 */
class NwcFaucetSmokeTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun getInfoOnThePublicFaucet() {
        val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
        val created = http.send(
            HttpRequest.newBuilder(URI.create("https://faucet.nwc.dev?balance=1000"))
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertTrue(created.statusCode() in 200..299, "Faucet HTTP ${created.statusCode()}")
        val connection = NwcUri.parse(created.body().trim())
        val messages = LinkedBlockingQueue<String>()
        val socket = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(15)).buildAsync(
            URI.create(connection.relays.first()),
            object : WebSocket.Listener {
                private val buffer = StringBuilder()
                override fun onOpen(webSocket: WebSocket) {
                    webSocket.request(1)
                }

                override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
                    buffer.append(data)
                    if (last) {
                        messages.add(buffer.toString())
                        buffer.setLength(0)
                    }
                    webSocket.request(1)
                    return CompletableFuture.completedFuture(null)
                }
            },
        ).join()
        try {
            socket.sendText(
                """["REQ","info",{"kinds":[13194],"authors":["${connection.walletPubkey}"],"limit":1}]""",
                true,
            ).join()
            val info = waitFor(messages, kind = 13194, author = connection.walletPubkey, requestId = null)
            val parsed = WalletInfo.parse(info.content, info.tags)
            parsed.requireNip44()
            val request = NwcProtocol.request(connection, "get_info", "{}", System.currentTimeMillis() / 1000)
            assertTrue(request.tags.any { it == listOf("encryption", "nip44_v2") })
            assertTrue(request.tags.any { it == listOf("p", connection.walletPubkey) })
            socket.sendText(
                """["REQ","pay",{"kinds":[23195],"#e":["${request.id}"],"limit":1}]""",
                true,
            ).join()
            socket.sendText("""["EVENT",${request.toJson()}]""", true).join()
            val response = waitFor(messages, kind = 23195, author = null, requestId = request.id)
            val plain = NwcProtocol.decryptResponse(connection, response.content)
            val failure = NwcProtocol.failureMessage(plain)
            assertTrue(failure == null, failure ?: "")
            assertTrue(plain.contains("get_info"))
        } finally {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join()
        }
    }

    private fun waitFor(messages: LinkedBlockingQueue<String>, kind: Int, author: String?, requestId: String?): Heard {
        val deadline = System.nanoTime() + 25_000_000_000L
        while (System.nanoTime() < deadline) {
            val text = messages.poll(400, TimeUnit.MILLISECONDS) ?: continue
            val element = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonArray ?: continue
            when (element.firstOrNull()?.jsonPrimitive?.contentOrNull) {
                "OK" -> {
                    val accepted = element.getOrNull(2)?.jsonPrimitive?.contentOrNull
                    if (accepted == "false") {
                        throw AssertionError("Relay lehnt das Event ab")
                    }
                }
                "EVENT" -> {
                    val event = element.getOrNull(2)?.jsonObject ?: continue
                    if (event["kind"]?.jsonPrimitive?.int != kind) continue
                    val pubkey = event["pubkey"]?.jsonPrimitive?.contentOrNull?.lowercase()
                    if (author != null && pubkey != author) continue
                    val tags = event["tags"]?.jsonArray?.map { tag ->
                        tag.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
                    }.orEmpty()
                    if (requestId != null && tags.none { it.getOrNull(0) == "e" && it.getOrNull(1) == requestId }) continue
                    return Heard(event["content"]?.jsonPrimitive?.contentOrNull.orEmpty(), tags)
                }
            }
        }
        throw AssertionError("Keine Antwort der Art $kind")
    }

    private data class Heard(val content: String, val tags: List<List<String>>)
}
