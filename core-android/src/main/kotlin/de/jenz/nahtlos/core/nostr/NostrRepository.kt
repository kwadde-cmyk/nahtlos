package de.jenz.nahtlos.core.nostr

import de.jenz.nahtlos.core.AppLog
import de.jenz.nahtlos.core.sync.SettingsStore
import de.jenz.nahtlos.core.sync.SyncClient
import de.jenz.nahtlos.core.wallet.BoostService
import de.jenz.nahtlos.diagnose.DiagnoseText
import de.jenz.nahtlos.nostr.CommentAuthor
import de.jenz.nahtlos.nostr.CommentThread
import de.jenz.nahtlos.nostr.NostrConnectOffer
import de.jenz.nahtlos.nostr.NostrDraft
import de.jenz.nahtlos.model.NostrSignRequest
import de.jenz.nahtlos.model.NostrSignStatus
import de.jenz.nahtlos.nostr.NostrException
import de.jenz.nahtlos.nostr.NostrKeys
import de.jenz.nahtlos.nostr.NostrQueue
import de.jenz.nahtlos.nostr.PodcastNostr
import de.jenz.nahtlos.wallet.LnurlPay
import de.jenz.nahtlos.wallet.SignedEvent
import de.jenz.nahtlos.wallet.WalletException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class LoadedComments(
    val thread: CommentThread = CommentThread(emptyList(), 0, 0),
    val authors: Map<String, CommentAuthor> = emptyMap(),
)

@Singleton
class NostrRepository @Inject constructor(
    http: OkHttpClient,
    private val settings: SettingsStore,
    private val identity: IdentityStore,
    private val boosts: BoostService,
    private val api: SyncClient,
) {
    private val sockets = NostrSockets(http)
    private val nip46 = Nip46Client(sockets)
    private val lnurl = http.newBuilder().callTimeout(12, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()

    fun relays(): List<String> = PodcastNostr.relaysFrom(settings.nostrRelays())

    fun identity(): StoredIdentity = identity.read()

    fun relayText(): String = settings.nostrRelays().ifBlank { PodcastNostr.DEFAULT_RELAYS.joinToString("\n") }

    fun saveRelays(text: String) {
        settings.saveNostrRelays(text)
    }

    suspend fun loginAmber(amber: AmberSigner) {
        val (pubkey, pkg) = amber.login()
        identity.saveAmber(pubkey, pkg)
        refreshProfile()
        AppLog.record("Nostr", "Angemeldet über Amber, Schlüssel ${pubkey.take(8)}.")
    }

    fun openCarCode(amber: AmberSigner, raw: String) {
        val offer = NostrConnectOffer.parse(raw.trim()) ?: throw NostrException("Das ist kein Auto-Code.")
        amber.open(offer.uri)
    }

    suspend fun connectCar(onUri: (String) -> Unit, onStatus: (String) -> Unit = {}) {
        val offer = NostrConnectOffer.create(PodcastNostr.sessionRelays(relays()), "Nahtlos Auto")
        identity.savePending(offer)
        try {
            val remote = coroutineScope {
                val waiting = async {
                    nip46.awaitSigner(offer, 90_000) { line ->
                        AppLog.record("Nostr", line)
                        onStatus(line)
                    }
                }
                onUri(offer.uri)
                waiting.await()
            }
            val user = readPubkey(offer, remote)
            if (!user.matches(Regex("[0-9a-f]{64}"))) throw NostrException("Amber hat keinen öffentlichen Schlüssel genannt.")
            identity.commitCar(user, offer, remote)
            refreshProfile()
            AppLog.record("Nostr", "Auto verbunden, Schlüssel ${user.take(8)}.")
        } catch (error: kotlinx.coroutines.CancellationException) {
            identity.clearPending()
            throw error
        } catch (error: Exception) {
            identity.clearPending()
            throw error
        }
    }

    private suspend fun readPubkey(offer: NostrConnectOffer, remote: String): String {
        val first = runCatching {
            nip46.call(offer.clientSecret, remote, offer.relays, "get_public_key", emptyList(), 30_000)
        }
        val raw = first.getOrElse { error ->
            if (error.message?.contains("permission", ignoreCase = true) != true) throw error
            delay(1_500)
            nip46.call(offer.clientSecret, remote, offer.relays, "get_public_key", emptyList(), 30_000)
        }
        return raw.trim().lowercase()
    }

    fun logout() {
        identity.logout()
        AppLog.record("Nostr", "Abgemeldet.")
    }

    suspend fun refreshProfile() {
        val current = identity.read()
        if (current.userPubkey.length != 64) return
        val found = sockets.query(
            relays(),
            listOf("""{"kinds":[0],"authors":["${current.userPubkey}"],"limit":1}"""),
            6_000,
        ).filter { it.kind == 0 && it.pubkey == current.userPubkey && NostrKeys.valid(it) }.maxByOrNull { it.createdAt }
            ?: return
        val author = PodcastNostr.profile(found.content)
        identity.saveProfile(author.name.ifBlank { current.userPubkey.take(8) }, author.picture)
    }

    suspend fun load(episodeGuid: String): LoadedComments {
        if (episodeGuid.isBlank()) return LoadedComments()
        val relays = relays()
        val first = sockets.query(relays, PodcastNostr.commentFilters(episodeGuid), 7_000)
        val ids = first.filter { it.kind == 1 || it.kind == 1111 }.map { it.id }
        val second = if (ids.isEmpty()) emptyList() else sockets.query(relays, PodcastNostr.threadFilters(ids), 7_000)
        val events = (first + second).filter { NostrKeys.valid(it) }.distinctBy { it.id }
        val thread = PodcastNostr.thread(events, episodeGuid)
        val people = thread.comments.flatMap { comment -> listOf(comment.pubkey) + comment.replies.map { it.pubkey } }.toSet()
        return LoadedComments(thread, authors(people, relays))
    }

    suspend fun publish(draft: NostrDraft) {
        val onThisDevice = identity.read().amber
        val event = sign(draft)
        if (onThisDevice) publishEvent(event)
        AppLog.record("Nostr", "Kind ${draft.kind} veröffentlicht.")
    }

    suspend fun publishEvent(event: SignedEvent) {
        val took = sockets.publish(relays(), event, 8_000)
        if (took == 0) throw NostrException("Kein Relay hat den Beitrag angenommen.")
    }

    suspend fun pushIdentity() {
        val current = identity.read()
        if (!current.amber) throw NostrException("Zuerst mit Amber anmelden.")
        val server = settings.read()
        if (!server.configured) throw NostrException("Zuerst den Server koppeln.")
        api.putNostrIdentity(server, current.userPubkey, current.name, current.picture)
        AppLog.record("Nostr", "Konto ans Auto gegeben, Schlüssel ${current.userPubkey.take(8)}.")
    }

    suspend fun pullIdentity(): String {
        val server = settings.read()
        if (!server.configured) throw NostrException("Zuerst das Handy koppeln. Der Server fehlt.")
        val found = api.getNostrIdentity(server) ?: throw NostrException("Auf dem Handy liegt noch kein Nostr-Konto.")
        if (!found.pubkey.matches(Regex("[0-9a-f]{64}"))) throw NostrException("Das Konto vom Server ist ungültig.")
        identity.savePublic(found.pubkey, found.name, found.picture)
        refreshProfile()
        return "Konto ${found.name.ifBlank { found.pubkey.take(8) }} ist auf dem Auto."
    }

    suspend fun zap(
        address: String,
        sats: Long,
        note: String,
        podcast: String,
        episode: String,
        episodeGuid: String?,
        podcastGuid: String?,
        eventId: String? = null,
        eventKind: Int? = null,
        eventAuthor: String? = null,
    ): String {
        boosts.ensureBudget(sats)
        val recipient = withContext(Dispatchers.IO) { LnurlPay.nostrPubkey(address, ::fetch) }
        val draft = PodcastNostr.zapRequest(
            amountMsat = sats * 1000,
            recipientPubkey = recipient,
            lnurl = PodcastNostr.lnurlFor(address),
            relays = relays(),
            comment = note,
            createdAt = System.currentTimeMillis() / 1000,
            eventId = eventId,
            eventKind = eventKind,
            eventAuthor = eventAuthor,
            episodeGuid = episodeGuid,
            podcastGuid = podcastGuid,
        )
        val signed = sign(draft)
        val invoice = withContext(Dispatchers.IO) { LnurlPay.zapInvoice(address, sats * 1000, signed.toJson(), ::fetch) }
        return boosts.payZapInvoice(sats, invoice, podcast, episode, address.substringBefore('@'), address)
    }

    private suspend fun sign(draft: NostrDraft): SignedEvent {
        val current = identity.read()
        val amber = AmberSignerHolder.signer
        if (current.amber) {
            val signer = amber ?: throw NostrException("Amber ist gerade nicht erreichbar.")
            return signer.sign(current.signerPackage!!, current.userPubkey, draft)
        }
        if (settings.read().configured && current.userPubkey.length == 64) {
            return signViaServer(draft, current.userPubkey)
        }
        if (current.bunker) {
            val secret = current.clientSecret ?: throw NostrException("Nicht angemeldet.")
            val remote = current.remoteSigner ?: throw NostrException("Nicht angemeldet.")
            val json = nip46.call(
                secret,
                remote,
                relays(),
                "sign_event",
                listOf(de.jenz.nahtlos.nostr.Nip46Messages.unsignedForSigner(draft)),
                60_000,
            )
            val event = runCatching {
                val element = jsonParser.parseToJsonElement(json)
                val obj = when (element) {
                    is kotlinx.serialization.json.JsonObject -> element
                    is kotlinx.serialization.json.JsonPrimitive ->
                        jsonParser.parseToJsonElement(element.content).let { it as kotlinx.serialization.json.JsonObject }
                    else -> throw NostrException("Amber hat kein Event zurückgegeben.")
                }
                PodcastNostr.parseEvent(obj)
            }.getOrNull() ?: throw NostrException("Amber hat kein Event zurückgegeben.")
            if (!NostrKeys.valid(event) || event.pubkey != current.userPubkey) {
                throw NostrException("Amber hat ein anderes Event zurückgegeben.")
            }
            return event
        }
        throw NostrException("Nicht angemeldet.")
    }

    private suspend fun signViaServer(draft: NostrDraft, pubkey: String): SignedEvent {
        val server = settings.read()
        val id = java.util.UUID.randomUUID().toString()
        api.postNostrDraft(
            server,
            NostrSignRequest(id, draft.kind, draft.content, draft.tags, draft.createdAt),
        )
        val deadline = System.currentTimeMillis() + NostrQueue.TTL_MS
        while (System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(2_000)
            val row = api.pollNostrSign(server, id)
            when (row.status) {
                NostrSignStatus.SIGNED -> {
                    val event = parseSigned(row.event) ?: throw NostrException("Das Handy hat kein gültiges Event zurückgegeben.")
                    if (!NostrKeys.valid(event) || event.pubkey != pubkey) {
                        throw NostrException("Die Signatur passt nicht zum Konto.")
                    }
                    return event
                }
                NostrSignStatus.FAILED -> throw NostrException(row.error ?: "Das Handy hat abgelehnt.")
                NostrSignStatus.EXPIRED -> throw NostrException(NostrQueue.TIMEOUT)
            }
        }
        throw NostrException(NostrQueue.TIMEOUT)
    }

    private fun parseSigned(raw: String?): SignedEvent? {
        if (raw.isNullOrBlank()) return null
        val element = runCatching { jsonParser.parseToJsonElement(raw) }.getOrNull() as? kotlinx.serialization.json.JsonObject
            ?: return null
        return PodcastNostr.parseEvent(element)
    }

    private suspend fun authors(people: Set<String>, relays: List<String>): Map<String, CommentAuthor> {
        if (people.isEmpty()) return emptyMap()
        val listed = people.joinToString(",") { "\"$it\"" }
        val found = sockets.query(relays, listOf("""{"kinds":[0],"authors":[$listed],"limit":${people.size}}"""), 6_000)
        return found.filter { it.kind == 0 && it.pubkey in people && NostrKeys.valid(it) }
            .groupBy { it.pubkey }
            .mapValues { (_, events) -> PodcastNostr.profile(events.maxBy { it.createdAt }.content) }
    }

    private fun fetch(url: String): String {
        val response = lnurl.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute()
        response.use {
            if (!it.isSuccessful) throw WalletException("LNURL HTTP ${it.code}")
            return it.body?.string().orEmpty()
        }
    }

    private companion object {
        val jsonParser = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    }
}

/** Amber braucht einen Context. Die Activity setzt ihn, bevor sie anmeldet. */
object AmberSignerHolder {
    @Volatile
    var signer: AmberSigner? = null
}

fun Throwable.nostrMessage(): String {
    val text = when (this) {
        is NostrException -> message
        is WalletException -> message
        else -> message
    }
    return DiagnoseText.scrub(text.orEmpty()).ifBlank { "Nostr ist gerade nicht erreichbar." }
}
