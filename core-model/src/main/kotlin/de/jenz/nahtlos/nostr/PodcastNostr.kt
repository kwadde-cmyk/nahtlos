package de.jenz.nahtlos.nostr

import de.jenz.nahtlos.wallet.NostrEvent
import de.jenz.nahtlos.wallet.SignedEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Entwurf ohne Schlüssel. Der Signer setzt pubkey, id und sig. */
data class NostrDraft(
    val kind: Int,
    val content: String,
    val tags: List<List<String>>,
    val createdAt: Long,
)

data class CommentAuthor(val name: String, val picture: String?, val lud16: String?)

data class EpisodeComment(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val body: String,
    val kind: Int,
    val likes: Set<String>,
    val zappedSats: Long,
    val replies: List<EpisodeComment>,
)

data class CommentThread(
    val comments: List<EpisodeComment>,
    val episodeLikes: Int,
    val episodeZaps: Long,
)

/**
 * Erstes Tippen nach einem erfolgreichen Zap sendet nichts. Erst das zweite
 * Tippen auf denselben Empfänger zahlt noch einmal.
 */
data class ZapSafety(val sentTarget: String? = null, val armed: Boolean = false) {
    fun tap(target: String): Pair<ZapSafety, Boolean> {
        if (sentTarget == target && !armed) return copy(armed = true) to false
        return this to true
    }

    fun sent(target: String): ZapSafety = ZapSafety(sentTarget = target, armed = false)

    fun label(target: String, idle: String): String = when {
        sentTarget == target && armed -> "Nochmal tippen"
        sentTarget == target -> "Zap gesendet"
        else -> idle
    }
}

/**
 * Kommentare, Likes und Zaps einer Folge.
 *
 * Geschrieben wird ein Kommentar der obersten Ebene als Kind 1111 (NIP-22),
 * mit denselben GUID-Tags, die NIP-73 und das Beispiel in NIP-22 festlegen.
 * Gelesen wird zusätzlich Kind 1 mit demselben `i`-Tag: so schreibt Fountain
 * seine Kommentare, und Hopp fragt genau diese beiden Filter ab. Antworten auf
 * einen Kind-1-Kommentar bleiben Kind 1 (NIP-10), damit der Faden bei Fountain
 * sichtbar bleibt. Antworten auf Kind 1111 bleiben Kind 1111.
 */
object PodcastNostr {
    const val KIND_PROFILE = 0
    const val KIND_NOTE = 1
    const val KIND_REACTION = 7
    const val KIND_COMMENT = 1111
    const val KIND_ZAP_REQUEST = 9734
    const val KIND_ZAP_RECEIPT = 9735
    const val KIND_NIP46 = 24133
    const val ITEM_KIND = "podcast:item:guid"
    const val FEED_KIND = "podcast:guid"
    const val FOUNTAIN = "wss://relay.fountain.fm"

    val DEFAULT_RELAYS = listOf(
        FOUNTAIN,
        "wss://relay.damus.io",
        "wss://nos.lol",
        "wss://relay.nostr.band",
        "wss://relay.primal.net",
    )

    fun itemTag(episodeGuid: String): String = "$ITEM_KIND:${episodeGuid.trim()}"

    fun feedTag(podcastGuid: String): String = "$FEED_KIND:${podcastGuid.trim()}"

    fun relaysFrom(text: String?): List<String> {
        val parsed = text.orEmpty().split('\n', ',', ' ')
            .map { it.trim().trimEnd('/') }
            .filter { it.startsWith("wss://") || it.startsWith("ws://") }
            .distinct()
        return (parsed.ifEmpty { DEFAULT_RELAYS }).take(8)
    }

    /** Relays, die im QR der Auto-Anmeldung stehen. Weniger Zeilen, derselbe Anfang. */
    fun sessionRelays(configured: List<String>): List<String> = configured.take(4).ifEmpty { DEFAULT_RELAYS.take(4) }

    fun topLevelComment(
        episodeGuid: String,
        podcastGuid: String?,
        showTitle: String?,
        episodeTitle: String?,
        page: String?,
        text: String,
        createdAt: Long,
    ): NostrDraft {
        val body = text.trim()
        require(body.isNotEmpty())
        val hint = page?.takeIf { it.startsWith("http") }.orEmpty()
        val item = itemTag(episodeGuid)
        val tags = buildList {
            add(tag("I", item, hint))
            add(listOf("K", ITEM_KIND))
            add(tag("i", item, hint))
            add(listOf("k", ITEM_KIND))
            if (!podcastGuid.isNullOrBlank()) {
                add(listOf("i", feedTag(podcastGuid)))
                add(listOf("k", FEED_KIND))
            }
        }
        return NostrDraft(KIND_COMMENT, withEpisodeLine(body, showTitle, episodeTitle, page), tags, createdAt)
    }

    fun reply(
        episodeGuid: String,
        podcastGuid: String?,
        page: String?,
        parentId: String,
        parentPubkey: String,
        parentKind: Int,
        rootId: String?,
        text: String,
        createdAt: Long,
        relayHint: String = "",
    ): NostrDraft {
        val body = text.trim()
        require(body.isNotEmpty())
        val hint = page?.takeIf { it.startsWith("http") }.orEmpty()
        val item = itemTag(episodeGuid)
        if (parentKind == KIND_COMMENT) {
            return NostrDraft(
                KIND_COMMENT,
                body,
                listOf(
                    tag("I", item, hint),
                    listOf("K", ITEM_KIND),
                    listOf("e", parentId, relayHint, parentPubkey),
                    listOf("k", KIND_COMMENT.toString()),
                    listOf("p", parentPubkey),
                ),
                createdAt,
            )
        }
        val root = rootId?.ifBlank { null } ?: parentId
        val tags = buildList {
            add(listOf("e", root, relayHint, "root"))
            add(listOf("e", parentId, relayHint, "reply"))
            add(listOf("p", parentPubkey))
            add(tag("i", item, hint))
            add(listOf("k", ITEM_KIND))
            if (!podcastGuid.isNullOrBlank()) add(listOf("i", feedTag(podcastGuid)))
        }
        return NostrDraft(KIND_NOTE, body, tags, createdAt)
    }

    fun likeComment(commentId: String, author: String, kind: Int, relayHint: String, createdAt: Long): NostrDraft =
        NostrDraft(
            KIND_REACTION,
            "+",
            listOf(
                listOf("e", commentId, relayHint, author),
                listOf("p", author),
                listOf("k", kind.toString()),
            ),
            createdAt,
        )

    fun likeEpisode(episodeGuid: String, podcastGuid: String?, createdAt: Long): NostrDraft {
        val tags = buildList {
            add(listOf("i", itemTag(episodeGuid)))
            add(listOf("k", ITEM_KIND))
            if (!podcastGuid.isNullOrBlank()) add(listOf("i", feedTag(podcastGuid)))
        }
        return NostrDraft(KIND_REACTION, "+", tags, createdAt)
    }

    fun zapRequest(
        amountMsat: Long,
        recipientPubkey: String,
        lnurl: String,
        relays: List<String>,
        comment: String,
        createdAt: Long,
        eventId: String? = null,
        eventKind: Int? = null,
        eventAuthor: String? = null,
        episodeGuid: String? = null,
        podcastGuid: String? = null,
    ): NostrDraft {
        val tags = buildList {
            add(listOf("relays") + relays.take(4))
            add(listOf("amount", amountMsat.toString()))
            add(listOf("lnurl", lnurl))
            add(listOf("p", recipientPubkey))
            if (!eventId.isNullOrBlank()) {
                add(listOf("e", eventId))
                if (!eventAuthor.isNullOrBlank() && eventAuthor != recipientPubkey) add(listOf("p", eventAuthor))
                if (eventKind != null) add(listOf("k", eventKind.toString()))
            }
            if (!episodeGuid.isNullOrBlank()) {
                add(listOf("i", itemTag(episodeGuid)))
                add(listOf("k", ITEM_KIND))
                if (!podcastGuid.isNullOrBlank()) add(listOf("i", feedTag(podcastGuid)))
            }
        }
        return NostrDraft(KIND_ZAP_REQUEST, comment.trim(), tags, createdAt)
    }

    fun lnurlFor(address: String): String {
        val parts = address.trim().split("@")
        require(parts.size == 2)
        val url = "https://${parts[1].trim()}/.well-known/lnurlp/${parts[0].trim()}"
        return Bech32.encode("lnurl", url.toByteArray(Charsets.UTF_8))
    }

    fun commentFilters(episodeGuid: String): List<String> {
        val item = NostrEvent.jsonString(itemTag(episodeGuid))
        return listOf(
            """{"kinds":[1,1111],"#i":[$item],"limit":200}""",
            """{"kinds":[1111],"#I":[$item],"limit":200}""",
            """{"kinds":[7],"#i":[$item],"limit":100}""",
        )
    }

    fun threadFilters(ids: List<String>): List<String> =
        ids.distinct().chunked(80).map { chunk ->
            val listed = chunk.joinToString(",") { NostrEvent.jsonString(it) }
            """{"kinds":[1,1111,7,9735],"#e":[$listed],"limit":300}"""
        }

    fun namesEpisode(event: SignedEvent, episodeGuid: String): Boolean {
        val item = itemTag(episodeGuid)
        return event.tags.any { it.size >= 2 && (it[0] == "i" || it[0] == "I") && it[1] == item }
    }

    fun thread(events: List<SignedEvent>, episodeGuid: String): CommentThread {
        val item = itemTag(episodeGuid)
        val notes = events.filter { it.kind == KIND_NOTE || it.kind == KIND_COMMENT }.distinctBy { it.id }
        val named = notes.filter { namesEpisode(it, episodeGuid) }.map { it.id }.toMutableSet()
        fun parentOf(event: SignedEvent): String? {
            val es = event.tags.filter { it.size >= 2 && it[0] == "e" }
            return if (event.kind == KIND_COMMENT) {
                es.firstOrNull()?.get(1)
            } else {
                es.firstOrNull { it.getOrNull(3) == "reply" }?.get(1)
                    ?: es.firstOrNull { it.getOrNull(3) == "root" }?.get(1)
                    ?: es.lastOrNull { it.getOrNull(3).isNullOrEmpty() }?.get(1)
            }
        }
        val parent = notes.associate { it.id to parentOf(it) }
        var grew = true
        while (grew) {
            grew = false
            for (event in notes) {
                if (event.id in named) continue
                val above = parent[event.id]
                if (above != null && above in named) {
                    named += event.id
                    grew = true
                }
            }
        }
        val kept = notes.filter { it.id in named }.associateBy { it.id }
        val likes = events.filter { it.kind == KIND_REACTION && it.content.trim() != "-" }
        fun likeTargets(event: SignedEvent): String? =
            event.tags.lastOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
        val likesBy = likes.mapNotNull { reaction ->
            likeTargets(reaction)?.takeIf { it in kept }?.let { it to reaction.pubkey }
        }.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
        val episodeLikes = likes.count { reaction ->
            likeTargets(reaction) == null && reaction.tags.any { it.size >= 2 && it[0] == "i" && it[1] == item }
        }
        val zaps = events.filter { it.kind == KIND_ZAP_RECEIPT }.distinctBy { it.id }
        val zapped = zaps.mapNotNull { receipt ->
            val target = receipt.tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1) ?: return@mapNotNull null
            if (target !in kept) return@mapNotNull null
            zapSats(receipt)?.let { target to it }
        }.groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
        val episodeZaps = zaps.sumOf { receipt ->
            val onEpisode = receipt.tags.any { it.size >= 2 && (it[0] == "i" || it[0] == "I") && it[1] == item } ||
                zapRequest(receipt)?.let { namesEpisode(it, episodeGuid) } == true
            val onComment = receipt.tags.any { it.size >= 2 && it[0] == "e" && it[1] in kept }
            if (onEpisode && !onComment) zapSats(receipt) ?: 0L else 0L
        }
        fun top(id: String): String {
            var at = id
            while (true) {
                val above = parent[at]
                if (above == null || above !in kept) return at
                at = above
            }
        }
        fun node(event: SignedEvent, replies: List<EpisodeComment>): EpisodeComment = EpisodeComment(
            id = event.id,
            pubkey = event.pubkey,
            createdAt = event.createdAt,
            body = visibleBody(event.content),
            kind = event.kind,
            likes = likesBy[event.id].orEmpty(),
            zappedSats = zapped[event.id] ?: 0L,
            replies = replies,
        )
        val byTop = kept.values.groupBy { top(it.id) }
        val comments = byTop.mapNotNull { (topId, members) ->
            val head = kept[topId] ?: return@mapNotNull null
            val replies = members.filter { it.id != topId }.sortedBy { it.createdAt }.map { node(it, emptyList()) }
            node(head, replies)
        }.sortedByDescending { it.createdAt }
        return CommentThread(comments, episodeLikes, episodeZaps)
    }

    fun profile(content: String): CommentAuthor {
        val obj = runCatching { json.parseToJsonElement(content).jsonObject }.getOrNull()
        fun text(vararg names: String) = names.firstNotNullOfOrNull { name ->
            obj?.get(name)?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        }
        val picture = text("picture")?.takeIf { it.startsWith("https://") }
        val lud = text("lud16")?.takeIf { '@' in it }
        return CommentAuthor(text("display_name", "displayName", "name").orEmpty(), picture, lud)
    }

    fun visibleBody(content: String): String = content
        .replace(EMBED, "")
        .replace(FOUNTAIN_TAIL, "")
        .replace(EPISODE_TAIL, "")
        .trim()

    fun parseEvent(element: JsonObject): SignedEvent? {
        val id = element.string("id") ?: return null
        val pubkey = element.string("pubkey") ?: return null
        val created = element["created_at"]?.jsonPrimitive?.longOrNull ?: return null
        val kind = element["kind"]?.jsonPrimitive?.intOrNull ?: return null
        val content = element["content"]?.jsonPrimitive?.contentOrNull ?: return null
        val sig = element.string("sig") ?: return null
        val tags = element["tags"]?.jsonArray?.map { tag ->
            tag.jsonArray.map { item ->
                val primitive = item.jsonPrimitive
                primitive.contentOrNull ?: primitive.longOrNull?.toString() ?: ""
            }
        } ?: return null
        return SignedEvent(id.lowercase(), pubkey.lowercase(), created, kind, tags, content, sig.lowercase())
    }

    fun eventFromRelay(text: String): SignedEvent? = runCatching {
        val array = json.parseToJsonElement(text) as? JsonArray ?: return null
        if (array.firstOrNull()?.jsonPrimitive?.contentOrNull != "EVENT") return null
        parseEvent(array.getOrNull(2)?.jsonObject ?: return null)
    }.getOrNull()

    fun publishAck(text: String): Triple<String, Boolean, String>? = runCatching {
        val array = json.parseToJsonElement(text) as? JsonArray ?: return null
        if (array.firstOrNull()?.jsonPrimitive?.contentOrNull != "OK") return null
        val id = array.getOrNull(1)?.jsonPrimitive?.contentOrNull ?: return null
        val accepted = array.getOrNull(2)?.jsonPrimitive?.contentOrNull == "true"
        val reason = array.getOrNull(3)?.jsonPrimitive?.contentOrNull.orEmpty()
        Triple(id, accepted, reason)
    }.getOrNull()

    private fun zapRequest(receipt: SignedEvent): SignedEvent? {
        val description = receipt.tags.firstOrNull { it.size >= 2 && it[0] == "description" }?.get(1) ?: return null
        val obj = runCatching { json.parseToJsonElement(description).jsonObject }.getOrNull() ?: return null
        return parseEvent(obj)
    }

    private fun zapSats(receipt: SignedEvent): Long? {
        val request = zapRequest(receipt) ?: return null
        if (request.kind != KIND_ZAP_REQUEST || !NostrKeys.valid(request)) return null
        val msat = request.tags.firstOrNull { it.size >= 2 && it[0] == "amount" }?.get(1)?.toLongOrNull() ?: return null
        return (msat / 1000).takeIf { it > 0 }
    }

    private fun tag(name: String, value: String, hint: String): List<String> =
        if (hint.isBlank()) listOf(name, value) else listOf(name, value, hint)

    private fun withEpisodeLine(body: String, show: String?, episode: String?, page: String?): String {
        val name = listOfNotNull(show?.trim()?.ifBlank { null }, episode?.trim()?.ifBlank { null })
            .joinToString(" – ")
            .replace('\n', ' ')
            .takeIf { it.isNotBlank() } ?: return body
        val link = page?.takeIf { it.startsWith("http") }?.let { "\n$it" }.orEmpty()
        return "$body\n\n🎧 $name$link"
    }

    private fun JsonObject.string(name: String): String? =
        this[name]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private val json = Json { ignoreUnknownKeys = true }
    private val EMBED = Regex("""nostr:(nevent|note|naddr|npub|nprofile)1[02-9ac-hj-np-z]+""")
    private val FOUNTAIN_TAIL = Regex("""\s*https://fountain\.fm/(episode|show)/\S+\s*$""")
    private val EPISODE_TAIL = Regex("""\n\n🎧 [^\n]+(\nhttps?://\S+)?$""")
}

/** Shownotes als Fließtext. Im Auto gibt es kein HTML. */
object PlainNotes {
    fun from(html: String?, description: String?): String {
        val source = html?.takeIf { it.isNotBlank() } ?: description.orEmpty()
        if (source.isBlank()) return ""
        return source
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</p>"), "\n\n")
            .replace(Regex("(?i)<li[^>]*>"), "\n• ")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("[\\t ]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
            .take(4_000)
    }
}
