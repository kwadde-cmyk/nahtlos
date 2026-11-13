package de.jenz.nahtlos.core.nostr

import de.jenz.nahtlos.nostr.Nip46Messages
import de.jenz.nahtlos.nostr.NostrConnectOffer
import de.jenz.nahtlos.nostr.NostrException
import de.jenz.nahtlos.nostr.NostrKeys
import de.jenz.nahtlos.nostr.PodcastNostr
import de.jenz.nahtlos.wallet.Bytes

/** NIP-46 vom Auto zu Amber. Der Sitzungsschlüssel verlässt diese Klasse nicht im Klartext. */
class Nip46Client(private val sockets: NostrSockets) {
    suspend fun awaitSigner(offer: NostrConnectOffer, timeoutMs: Long): String {
        val since = (System.currentTimeMillis() / 1000) - 30
        val filter = """{"kinds":[${PodcastNostr.KIND_NIP46}],"#p":["${offer.clientPubkey}"],"since":$since}"""
        val event = sockets.await(offer.relays, filter, null, timeoutMs) { incoming ->
            Nip46Messages.connectAck(offer.clientSecret, incoming, offer.authToken) != null
        } ?: throw NostrException("Amber hat nicht geantwortet. Das Handy muss erreichbar sein und die Anmeldung erlauben.")
        return Nip46Messages.connectAck(offer.clientSecret, event, offer.authToken)
            ?: throw NostrException("Die Antwort von Amber passt nicht zu diesem Auto.")
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
        val since = (System.currentTimeMillis() / 1000) - 10
        val clientPub = NostrKeys.pubkey(clientSecret)
        val filter = """{"kinds":[${PodcastNostr.KIND_NIP46}],"#p":["$clientPub"],"authors":["$remotePubkey"],"since":$since}"""
        val answer = sockets.await(relays, filter, event, timeoutMs) { incoming ->
            incoming.pubkey == remotePubkey && Nip46Messages.open(clientSecret, incoming, id) != null
        } ?: throw NostrException("Amber hat nicht geantwortet.")
        val reply = Nip46Messages.open(clientSecret, answer, id) ?: throw NostrException("Amber hat nicht geantwortet.")
        val failure = reply.error
        if (!failure.isNullOrBlank()) throw NostrException("Amber: ${failure.take(160)}")
        return reply.result?.takeIf { it.isNotBlank() } ?: throw NostrException("Amber hat eine leere Antwort geschickt.")
    }
}
