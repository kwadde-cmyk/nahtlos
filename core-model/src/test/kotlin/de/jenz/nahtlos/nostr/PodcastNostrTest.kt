package de.jenz.nahtlos.nostr

import de.jenz.nahtlos.diagnose.DiagnoseText
import de.jenz.nahtlos.wallet.Bytes
import de.jenz.nahtlos.wallet.NostrEvent
import de.jenz.nahtlos.wallet.Secp256k1
import de.jenz.nahtlos.wallet.SignedEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PodcastNostrTest {
    private val episode = "d98d189b-dc7b-45b1-8720-d4b98690f31f"
    private val show = "c90e609a-df1e-596a-bd5e-57bcc8aad6cc"

    @Test
    fun bip340RoundTripMatchesTheFirstOfficialVector() {
        val secret = "0000000000000000000000000000000000000000000000000000000000000003"
        val message = ByteArray(32)
        val signature = Secp256k1.sign(secret, message, ByteArray(32))
        assertEquals(
            "e907831f80848d1069a5371b402410364bdf1c5f8307b0084c55f1ce2dca821525f66a4a85ea8b71e482a74f382d2ce5ebeee8fdb2172f477df4900d310536c0",
            Bytes.hex(signature),
        )
        assertTrue(Secp256k1.verify(Secp256k1.publicKey(secret), message, signature))
        assertFalse(Secp256k1.verify(Secp256k1.publicKey(secret), ByteArray(32) { 1 }, signature))
    }

    @Test
    fun topLevelCommentUsesNip22PodcastTagsAndFountainNotesStayVisible() {
        val draft = PodcastNostr.topLevelComment(
            episodeGuid = episode,
            podcastGuid = show,
            showTitle = "Beispiel",
            episodeTitle = "Folge eins",
            page = "https://example.com/ep",
            text = "Sehr gut",
            createdAt = 1_700_000_000,
        )
        assertEquals(PodcastNostr.KIND_COMMENT, draft.kind)
        assertEquals(listOf("I", "podcast:item:guid:$episode", "https://example.com/ep"), draft.tags[0])
        assertEquals(listOf("K", "podcast:item:guid"), draft.tags[1])
        assertEquals(listOf("i", "podcast:item:guid:$episode", "https://example.com/ep"), draft.tags[2])
        assertEquals(listOf("k", "podcast:item:guid"), draft.tags[3])
        assertTrue(draft.tags.any { it.size >= 2 && it[0] == "i" && it[1] == "podcast:guid:$show" })
        assertTrue(draft.content.startsWith("Sehr gut"))
        assertEquals("Sehr gut", PodcastNostr.visibleBody(draft.content))

        val fountain = fake(
            id = "aa",
            kind = 1,
            tags = listOf(
                listOf("i", "podcast:item:guid:$episode"),
                listOf("k", "podcast:item:guid"),
            ),
            content = "Von Fountain\n\nhttps://fountain.fm/episode/z1y9TMQRuqXl2awyrQxg",
        )
        val own = fake(id = "bb", kind = 1111, tags = draft.tags, content = draft.content)
        val thread = PodcastNostr.thread(listOf(fountain, own), episode)
        assertEquals(setOf("Von Fountain", "Sehr gut"), thread.comments.map { it.body }.toSet())
        assertTrue(PodcastNostr.commentFilters(episode)[0].contains("\"#i\""))
        assertTrue(PodcastNostr.commentFilters(episode)[1].contains("\"#I\""))
    }

    @Test
    fun repliesLikesAndZapsKeepTheTagsFountainAndHoppRead() {
        val answer = PodcastNostr.reply(
            episodeGuid = episode,
            podcastGuid = show,
            page = null,
            parentId = "parent",
            parentPubkey = "ab".repeat(32),
            parentKind = 1111,
            rootId = null,
            text = "Antwort",
            createdAt = 10,
        )
        assertEquals(1111, answer.kind)
        assertEquals("parent", answer.tags.first { it[0] == "e" }[1])
        assertTrue(answer.tags.any { it == listOf("k", "1111") })
        assertTrue(answer.tags.any { it[0] == "p" })

        val noteReply = PodcastNostr.reply(
            episodeGuid = episode,
            podcastGuid = null,
            page = null,
            parentId = "note",
            parentPubkey = "cd".repeat(32),
            parentKind = 1,
            rootId = "root",
            text = "Auch",
            createdAt = 11,
        )
        assertEquals(1, noteReply.kind)
        assertEquals("reply", noteReply.tags.first { it[0] == "e" && it[1] == "note" }[3])

        val like = PodcastNostr.likeComment("parent", "ab".repeat(32), 1111, "wss://relay.fountain.fm", 12)
        assertEquals(7, like.kind)
        assertEquals("+", like.content)
        val episodeLike = PodcastNostr.likeEpisode(episode, show, 13)
        assertTrue(episodeLike.tags.any { it[0] == "i" && it[1] == "podcast:item:guid:$episode" })

        val zap = PodcastNostr.zapRequest(
            amountMsat = 21_000,
            recipientPubkey = "11".repeat(32),
            lnurl = "lnurl1example",
            relays = listOf(PodcastNostr.FOUNTAIN),
            comment = "Danke",
            createdAt = 14,
            eventId = "parent",
            eventKind = 1111,
            episodeGuid = episode,
        )
        assertEquals(9734, zap.kind)
        assertEquals(listOf("amount", "21000"), zap.tags.first { it[0] == "amount" })
        assertTrue(zap.tags.any { it[0] == "e" && it[1] == "parent" })
        assertTrue(zap.tags.any { it[0] == "i" && it[1] == "podcast:item:guid:$episode" })
        assertTrue(zap.tags.first()[0] == "relays" && PodcastNostr.FOUNTAIN in zap.tags.first())
    }

    @Test
    fun profilePictureAndNameComeFromKindZero() {
        val author = PodcastNostr.profile("""{"display_name":"Ada","picture":"https://cdn.example/a.png","lud16":"ada@getalby.com"}""")
        assertEquals("Ada", author.name)
        assertEquals("https://cdn.example/a.png", author.picture)
        assertEquals("ada@getalby.com", author.lud16)
        assertEquals(null, PodcastNostr.profile("""{"picture":"http://insecure.example/a.png"}""").picture)
    }

    @Test
    fun carOfferCarriesNoPrivateKey() {
        val offer = NostrConnectOffer.create(PodcastNostr.DEFAULT_RELAYS)
        assertTrue(offer.uri.startsWith("nostrconnect://${offer.clientPubkey}?"))
        assertFalse(offer.uri.contains(offer.clientSecret))
        assertTrue(offer.uri.contains("secret=${offer.authToken}"))
        assertEquals(offer.clientPubkey, NostrConnectOffer.parse(offer.uri)?.clientPubkey)
        assertEquals("", NostrConnectOffer.parse(offer.uri)?.clientSecret)
        assertFalse(offer.toString().contains(offer.clientSecret))
        assertFalse(offer.toString().contains(offer.authToken))
        val bunker = BunkerUri.parse("bunker://${offer.clientPubkey}?relay=wss://nos.lol&secret=abc")
        assertNotNull(bunker)
        assertFalse(bunker.toString().contains("abc"))
    }

    @Test
    fun logsDropKeysBunkerLinksAndInvoices() {
        val raw = "nsec1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq bunker://abcd nostrconnect://abcd?secret=ffff"
        val clean = DiagnoseText.scrub(raw)
        assertFalse(clean.contains("nsec1"))
        assertFalse(clean.contains("bunker://"))
        assertFalse(clean.contains("nostrconnect://"))
        assertFalse(clean.contains("ffff"))
        assertEquals("Privater Schlüssel", DiagnoseText.reject(raw))
    }

    @Test
    fun shownotesLoseTagsAndASecondZapNeedsASecondTap() {
        assertEquals("Hallo\n\nWelt", PlainNotes.from("<p>Hallo</p><p>Welt</p>", ""))
        assertEquals("", PlainNotes.from("", "  "))
        val safety = ZapSafety()
        val (afterSend, send) = safety.tap("comment-1")
        assertTrue(send)
        val locked = afterSend.sent("comment-1")
        assertEquals("Zap gesendet", locked.label("comment-1", "Zap"))
        val (armed, again) = locked.tap("comment-1")
        assertFalse(again)
        assertEquals("Nochmal tippen", armed.label("comment-1", "Zap"))
        val (_, third) = armed.tap("comment-1")
        assertTrue(third)
    }

    @Test
    fun signedEventVerifiesAndABadSignatureDoesNot() {
        val secret = NostrKeys.generateSecret()
        val event = NostrEvent.signed(secret, 1, listOf(listOf("i", "podcast:item:guid:$episode")), "Hallo", 1_700_000_000)
        assertTrue(NostrKeys.valid(event))
        assertFalse(NostrKeys.valid(event.copy(content = "Anders")))
        assertTrue(Bech32.valid(Bech32.encode("lnurl", "https://example.com/.well-known/lnurlp/ada".toByteArray())))
    }

    private fun fake(id: String, kind: Int, tags: List<List<String>>, content: String) = SignedEvent(
        id = id,
        pubkey = "ee".repeat(32),
        createdAt = 20,
        kind = kind,
        tags = tags,
        content = content,
        sig = "00",
    )
}
