package de.jenz.nahtlos.server

import de.jenz.nahtlos.model.BootstrapRequest
import de.jenz.nahtlos.model.BootstrapResponse
import de.jenz.nahtlos.model.ErrorBody
import de.jenz.nahtlos.model.NahtlosJson
import de.jenz.nahtlos.model.NostrIdentityBody
import de.jenz.nahtlos.model.NostrSignRequest
import de.jenz.nahtlos.model.NostrSignStatus
import de.jenz.nahtlos.model.NostrSignedBody
import de.jenz.nahtlos.nostr.NostrKeys
import de.jenz.nahtlos.wallet.NostrEvent
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NostrQueueRouteTest {
    private val admin = "test-token"
    @Test
    fun phonePublishesOnlyThePublicKeyAndSignsTheCarsDraft() = testApplication {
        val dir = Files.createTempDirectory("nahtlos-nostr")
        val db = SyncDatabase(dir.resolve("sync.db").toString())
        application { module(db, admin) }
        val http = createClient {
            install(ContentNegotiation) { json(NahtlosJson.json) }
            defaultRequest { contentType(ContentType.Application.Json) }
        }
        val phone = bootstrap(http, "Handy")
        val secret = NostrKeys.generateSecret()
        val pubkey = NostrKeys.pubkey(secret)
        http.put("/v1/nostr/identity") {
            bearerAuth(phone.token)
            setBody(NostrIdentityBody(pubkey, "Ada", "https://cdn.example/a.png"))
        }
        val stored = NahtlosJson.json.decodeFromString(
            NostrIdentityBody.serializer(),
            http.get("/v1/nostr/identity") { bearerAuth(phone.token) }.bodyAsText(),
        )
        assertEquals(pubkey, stored.pubkey)
        assertEquals("Ada", stored.name)
        assertFalse(stored.toString().contains(secret))

        val rejected = http.put("/v1/nostr/identity") {
            bearerAuth(phone.token)
            setBody(NostrIdentityBody(pubkey, "nsec1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq", null))
        }
        assertEquals(HttpStatusCode.BadRequest, rejected.status)

        val draft = NostrSignRequest(
            id = UUID.randomUUID().toString(),
            kind = 1111,
            content = "Gut",
            tags = listOf(listOf("I", "podcast:item:guid:abc"), listOf("K", "podcast:item:guid")),
            createdAt = 1_700_000_000,
        )
        val opened = NahtlosJson.json.decodeFromString(
            NostrSignStatus.serializer(),
            http.post("/v1/nostr/sign") {
                bearerAuth(phone.token)
                setBody(draft)
            }.bodyAsText(),
        )
        assertEquals(NostrSignStatus.PENDING, opened.status)
        val pending = NahtlosJson.json.decodeFromString<List<NostrSignStatus>>(
            http.get("/v1/nostr/sign") { bearerAuth(phone.token) }.bodyAsText(),
        )
        assertEquals(draft.content, pending.single().content)
        assertTrue(pending.single().event == null)

        val event = NostrEvent.signed(secret, draft.kind, draft.tags, draft.content, draft.createdAt)
        val signed = http.post("/v1/nostr/sign/${draft.id}") {
            bearerAuth(phone.token)
            setBody(NostrSignedBody(event.toJson()))
        }
        assertEquals(HttpStatusCode.OK, signed.status)
        val done = NahtlosJson.json.decodeFromString(
            NostrSignStatus.serializer(),
            http.get("/v1/nostr/sign/${draft.id}") { bearerAuth(phone.token) }.bodyAsText(),
        )
        assertEquals(NostrSignStatus.SIGNED, done.status)
        assertTrue(done.event?.contains(event.id) == true)
        assertFalse(done.event!!.contains(secret))

        val poisoned = http.post("/v1/nostr/sign") {
            bearerAuth(phone.token)
            setBody(draft.copy(id = UUID.randomUUID().toString(), content = "nostr+walletconnect://aa"))
        }
        assertEquals(HttpStatusCode.BadRequest, poisoned.status)
        assertEquals("Wallet-Link", NahtlosJson.json.decodeFromString(ErrorBody.serializer(), poisoned.bodyAsText()).error)
    }

    private suspend fun bootstrap(http: io.ktor.client.HttpClient, name: String): BootstrapResponse =
        NahtlosJson.json.decodeFromString(
            BootstrapResponse.serializer(),
            http.post("/v1/devices/bootstrap") {
                bearerAuth(admin)
                setBody(BootstrapRequest(name))
            }.bodyAsText(),
        )
}
