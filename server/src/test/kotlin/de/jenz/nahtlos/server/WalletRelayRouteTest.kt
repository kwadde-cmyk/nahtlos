package de.jenz.nahtlos.server

import de.jenz.nahtlos.model.BootstrapRequest
import de.jenz.nahtlos.model.BootstrapResponse
import de.jenz.nahtlos.model.NahtlosJson
import de.jenz.nahtlos.model.WalletCipherRequest
import de.jenz.nahtlos.model.WalletOpenRequest
import de.jenz.nahtlos.model.WalletOpenResponse
import de.jenz.nahtlos.model.WalletPollResponse
import de.jenz.nahtlos.wallet.WalletRelay
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WalletRelayRouteTest {
    private val uri = "nostr+walletconnect://" + "ab".repeat(32) +
        "?relay=wss://relay.example&secret=" + "cd".repeat(32)

    @Test
    fun serverStoresOnlyCiphertextAndGivesItToTheCarOnce() = testApplication {
        val dir = Files.createTempDirectory("nahtlos-wallet")
        val db = SyncDatabase(dir.resolve("sync.db").toString())
        application { module(db, ADMIN) }
        val http = createClient {
            install(ContentNegotiation) { json(NahtlosJson.json) }
            defaultRequest { contentType(ContentType.Application.Json) }
        }
        val car = NahtlosJson.json.decodeFromString(
            BootstrapResponse.serializer(),
            http.post("/v1/devices/bootstrap") {
                bearerAuth(ADMIN)
                setBody(BootstrapRequest("Auto"))
            }.bodyAsText(),
        )
        val offer = WalletRelay.newOffer(car.id)
        val opened = http.post("/v1/wallet-relay/open") {
            bearerAuth(car.token)
            setBody(WalletOpenRequest(offer.sessionId))
        }
        assertEquals(HttpStatusCode.OK, opened.status)
        val openBody = NahtlosJson.json.decodeFromString(WalletOpenResponse.serializer(), opened.bodyAsText())
        assertEquals(offer.sessionId, openBody.sessionId)

        val duplicate = http.post("/v1/wallet-relay/open") {
            bearerAuth(car.token)
            setBody(WalletOpenRequest(offer.sessionId))
        }
        assertEquals(HttpStatusCode.BadRequest, duplicate.status)

        val pending = poll(http, car.token, offer.sessionId)
        assertEquals(WalletPollResponse.PENDING, pending.status)
        assertEquals(null, pending.ciphertext)

        val cipher = WalletRelay.seal(offer.link(), uri, offer.fingerprint)
        val phone = http.post("/v1/wallet-relay/${offer.sessionId}/cipher") {
            bearerAuth(ADMIN)
            setBody(WalletCipherRequest(cipher))
        }
        assertEquals(HttpStatusCode.OK, phone.status)
        val snapshot = db.walletSnapshot()
        assertTrue(snapshot.contains(cipher))
        assertFalse(snapshot.contains("nostr+walletconnect"))
        assertFalse(snapshot.contains("secret="))
        assertFalse(snapshot.contains(uri))

        val stolen = http.get("/v1/wallet-relay/${offer.sessionId}") { bearerAuth(ADMIN) }
        assertEquals(HttpStatusCode.Forbidden, stolen.status)
        assertFalse(stolen.bodyAsText().contains(cipher))

        val ready = poll(http, car.token, offer.sessionId)
        assertEquals(WalletPollResponse.READY, ready.status)
        assertEquals(cipher, ready.ciphertext)
        assertEquals(uri, offer.open(ready.ciphertext!!))
        assertFalse(db.walletSnapshot().contains(cipher))

        val again = poll(http, car.token, offer.sessionId)
        assertEquals(WalletPollResponse.GONE, again.status)
        assertEquals(null, again.ciphertext)
        offer.wipe()
        db.close()
    }

    @Test
    fun plaintextUploadIsRejected() = testApplication {
        val db = SyncDatabase(Files.createTempDirectory("nahtlos-wallet").resolve("sync.db").toString())
        application { module(db, ADMIN) }
        val http = createClient {
            install(ContentNegotiation) { json(NahtlosJson.json) }
            defaultRequest { contentType(ContentType.Application.Json) }
        }
        val car = NahtlosJson.json.decodeFromString(
            BootstrapResponse.serializer(),
            http.post("/v1/devices/bootstrap") {
                bearerAuth(ADMIN)
                setBody(BootstrapRequest("Auto"))
            }.bodyAsText(),
        )
        val offer = WalletRelay.newOffer(car.id)
        http.post("/v1/wallet-relay/open") {
            bearerAuth(car.token)
            setBody(WalletOpenRequest(offer.sessionId))
        }
        val rejected = http.post("/v1/wallet-relay/${offer.sessionId}/cipher") {
            bearerAuth(ADMIN)
            setBody(WalletCipherRequest(uri))
        }
        assertEquals(HttpStatusCode.BadRequest, rejected.status)
        assertFalse(db.walletSnapshot().contains("nostr+walletconnect"))
        offer.wipe()
        db.close()
    }

    private suspend fun poll(
        http: io.ktor.client.HttpClient,
        token: String,
        sessionId: String,
    ): WalletPollResponse {
        val response = http.get("/v1/wallet-relay/$sessionId") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, response.status)
        return NahtlosJson.json.decodeFromString(WalletPollResponse.serializer(), response.bodyAsText())
    }

    companion object {
        const val ADMIN = "test-token"
    }
}
