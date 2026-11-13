package de.jenz.nahtlos.core.nostr

import de.jenz.nahtlos.nostr.Nip46Messages
import de.jenz.nahtlos.nostr.NostrConnectOffer
import de.jenz.nahtlos.nostr.NostrException
import de.jenz.nahtlos.nostr.NostrKeys
import de.jenz.nahtlos.nostr.PodcastNostr
import de.jenz.nahtlos.wallet.Bytes
import java.util.concurrent.atomic.AtomicInteger

/** NIP-46 vom Auto zu Amber. Der Sitzungsschlüssel verlässt diese Klasse nicht im Klartext. */
class Nip46Client(private val sockets: NostrSockets) {
    suspend fun awaitSigner(offer: NostrConnectOffer, timeoutMs: Long, log: (String) -> Unit = {}): String {
        val relays = Nip46Messages.listenRelays(offer.relays)
        val filter = """{"kinds":[${PodcastNostr.KIND_NIP46}],"#p":["${offer.clientPubkey}"]}"""
        val relaysUp = AtomicInteger()
        val events = AtomicInteger()
        val opened = AtomicInteger()
        log("NIP-46: warte auf ${relays.size} Relays")
        val event = sockets.await(relays, filter, null, timeoutMs, relaysUp, events, log) { incoming ->
            val reading = Nip46Messages.readConnect(offer.clientSecret, incoming, offer.authToken)
            log("NIP-46: Entschlüsselung ${reading.scheme}")
            log("NIP-46: Ergebnis ${reading.outcome}")
            if (reading.scheme == "nip44" || reading.scheme == "nip04") opened.incrementAndGet()
            reading.acceptedPubkey != null
        } ?: throw NostrException(Nip46Messages.timeoutMessage(relaysUp.get(), events.get(), opened.get()))
        return Nip46Messages.readConnect(offer.clientSecret, event, offer.authToken).acceptedPubkey
            ?: throw NostrException(Nip46Messages.timeoutMessage(relaysUp.get(), events.get(), opened.get()))
    }

    suspend fun call(
        clientSecret: String,
        remotePubkey: String,
        relays: List<String>,
        method: String,
        params: List<String>,
        timeoutMs: Long,
    ): String {
        val id = Bytes.hex(Bytes.random(8))
        val plain = Nip46Messages.requestJson(id, method, params)
        val event = Nip46Messages.wrap(clientSecret, remotePubkey, plain, System.currentTimeMillis() / 1000)
        val clientPub = NostrKeys.pubkey(clientSecret)
        val filter = """{"kinds":[${PodcastNostr.KIND_NIP46}],"#p":["$clientPub"],"authors":["$remotePubkey"]}"""
        val answer = sockets.await(relays, filter, event, timeoutMs, AtomicInteger(), AtomicInteger(), {}) { incoming ->
            incoming.pubkey == remotePubkey && Nip46Messages.open(clientSecret, incoming, id) != null
        } ?: throw NostrException("Amber hat nicht geantwortet.")
        val reply = Nip46Messages.open(clientSecret, answer, id) ?: throw NostrException("Amber hat nicht geantwortet.")
        val failure = reply.error
        if (!failure.isNullOrBlank()) throw NostrException("Amber: ${failure.take(160)}")
        return reply.result?.takeIf { it.isNotBlank() } ?: throw NostrException("Amber hat eine leere Antwort geschickt.")
    }
}
