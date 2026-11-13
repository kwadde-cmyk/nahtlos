package de.jenz.nahtlos.core.nostr

import de.jenz.nahtlos.nostr.NostrKeys
import de.jenz.nahtlos.nostr.PodcastNostr
import de.jenz.nahtlos.wallet.SignedEvent
import de.jenz.nahtlos.nostr.Nip46Messages
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Relays sprechen JSON. Ein ausgefallenes Relay lässt die anderen weiterlaufen. */
class NostrSockets(private val http: OkHttpClient) {
    suspend fun query(relays: List<String>, filters: List<String>, timeoutMs: Long): List<SignedEvent> = coroutineScope {
        relays.distinct().map { relay ->
            async { runCatching { read(relay, filters, timeoutMs) }.getOrDefault(emptyList()) }
        }.awaitAll().flatten().distinctBy { it.id }
    }

    suspend fun publish(relays: List<String>, event: SignedEvent, timeoutMs: Long): Int = coroutineScope {
        relays.distinct().map { relay ->
            async { runCatching { accepted(relay, event, timeoutMs) }.getOrDefault(false) }
        }.awaitAll().count { it }
    }

    /** Wartet auf das erste gültige Event, das [take] annimmt. Die Leitung wird neu aufgebaut, wenn sie abreißt. */
    suspend fun await(
        relays: List<String>,
        filter: String,
        outgoing: SignedEvent?,
        timeoutMs: Long,
        relaysUp: AtomicInteger,
        events: AtomicInteger,
        log: (String) -> Unit,
        take: (SignedEvent) -> Boolean,
    ): SignedEvent? = coroutineScope {
        val found = CompletableDeferred<SignedEvent>()
        val jobs = relays.distinct().map { relay ->
            launch {
                val hit = try {
                    watch(relay, filter, outgoing, timeoutMs, relaysUp, events, log, take)
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
                if (hit != null && !found.isCompleted) found.complete(hit)
            }
        }
        val winner = withTimeoutOrNull(timeoutMs) { found.await() }
        jobs.forEach { it.cancel() }
        winner
    }

    private suspend fun read(relay: String, filters: List<String>, timeoutMs: Long): List<SignedEvent> {
        val socket = connect(relay) ?: return emptyList()
        try {
            socket.send("""["REQ","nahtlos",${filters.joinToString(",")}]""")
            val found = mutableListOf<SignedEvent>()
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (true) {
                val left = deadline - System.nanoTime()
                if (left <= 0) return found
                val text = withTimeoutOrNull((left / 1_000_000).coerceAtLeast(1)) { socket.incoming.receive() } ?: return found
                if (text.trimStart().startsWith("[\"EOSE\"")) return found
                val event = PodcastNostr.eventFromRelay(text)?.takeIf { NostrKeys.valid(it) } ?: continue
                found += event
            }
        } finally {
            socket.close()
        }
    }

    private suspend fun accepted(relay: String, event: SignedEvent, timeoutMs: Long): Boolean {
        val socket = connect(relay) ?: return false
        try {
            socket.send("""["EVENT",${event.toJson()}]""")
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (true) {
                val left = deadline - System.nanoTime()
                if (left <= 0) return false
                val text = withTimeoutOrNull((left / 1_000_000).coerceAtLeast(1)) { socket.incoming.receive() } ?: return false
                val ack = PodcastNostr.publishAck(text) ?: continue
                if (ack.first == event.id) return ack.second
            }
        } finally {
            socket.close()
        }
    }

    private suspend fun watch(
        relay: String,
        filter: String,
        outgoing: SignedEvent?,
        timeoutMs: Long,
        relaysUp: AtomicInteger,
        events: AtomicInteger,
        log: (String) -> Unit,
        take: (SignedEvent) -> Boolean,
    ): SignedEvent? {
        val host = Nip46Messages.relayHost(relay)
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        var toldUp = false
        var toldDown = false
        while (System.nanoTime() < deadline) {
            val socket = connect(relay)
            if (socket == null) {
                if (!toldDown) {
                    log("NIP-46: Relay nicht erreichbar ($host)")
                    toldDown = true
                }
                delay(2_000)
                continue
            }
            if (!toldUp) {
                relaysUp.incrementAndGet()
                log("NIP-46: Relay verbunden ($host)")
                toldUp = true
            }
            try {
                socket.send("""["REQ","nahtlos",$filter]""")
                log("NIP-46: Abo aktiv ($host, Kind 24133)")
                if (outgoing != null) socket.send("""["EVENT",${outgoing.toJson()}]""")
                while (System.nanoTime() < deadline) {
                    val left = deadline - System.nanoTime()
                    if (left <= 0) return null
                    val text = try {
                        withTimeoutOrNull((left / 1_000_000).coerceAtLeast(1)) { socket.incoming.receive() }
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        null
                    } ?: break
                    val event = PodcastNostr.eventFromRelay(text) ?: continue
                    log("NIP-46: Event empfangen ($host, Kind ${event.kind})")
                    if (!NostrKeys.valid(event)) {
                        log("NIP-46: Signatur ungültig ($host)")
                        continue
                    }
                    events.incrementAndGet()
                    if (take(event)) return event
                }
            } finally {
                socket.close()
            }
            log("NIP-46: Leitung zu ($host)")
            delay(1_000)
        }
        return null
    }

    private suspend fun connect(relay: String): Socket? {
        if (!relay.startsWith("wss://") && !relay.startsWith("ws://")) return null
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
                incoming.close()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                incoming.close()
            }
        })
        val ready = try {
            withTimeoutOrNull(8_000) { opened.await() }
        } catch (_: Exception) {
            web.cancel()
            return null
        }
        if (ready == null) {
            web.cancel()
            return null
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
}
