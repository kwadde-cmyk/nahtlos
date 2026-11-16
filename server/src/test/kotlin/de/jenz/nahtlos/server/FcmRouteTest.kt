package de.jenz.nahtlos.server

import de.jenz.nahtlos.model.BootstrapRequest
import de.jenz.nahtlos.model.BootstrapResponse
import de.jenz.nahtlos.model.NahtlosJson
import de.jenz.nahtlos.model.NostrSignRequest
import de.jenz.nahtlos.model.PushTokenBody
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
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
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FcmRouteTest {
    private val admin = "test-token"

    @Test
    fun aNewSignRequestWakesThePhoneAndSecretsAreRefused() = testApplication {
        val dir = Files.createTempDirectory("nahtlos-fcm")
        val db = SyncDatabase(dir.resolve("sync.db").toString())
        var nudged = 0
        application { module(db, admin, nudge = SignNudge { nudged++ }) }
        val http = createClient {
            install(ContentNegotiation) { json(NahtlosJson.json) }
            defaultRequest { contentType(ContentType.Application.Json) }
        }
        val phone = NahtlosJson.json.decodeFromString(
            BootstrapResponse.serializer(),
            http.post("/v1/devices/bootstrap") {
                bearerAuth(admin)
                setBody(BootstrapRequest("Handy"))
            }.bodyAsText(),
        )
        val stored = http.put("/v1/nostr/push") {
            bearerAuth(phone.token)
            setBody(PushTokenBody("fcm-device-token-0123456789"))
        }
        assertEquals(HttpStatusCode.OK, stored.status)
        val rejected = http.put("/v1/nostr/push") {
            bearerAuth(phone.token)
            setBody(PushTokenBody("nsec1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"))
        }
        assertEquals(HttpStatusCode.BadRequest, rejected.status)

        val draft = NostrSignRequest(UUID.randomUUID().toString(), 7, "+", emptyList(), 20)
        http.post("/v1/nostr/sign") {
            bearerAuth(phone.token)
            setBody(draft)
        }
        http.post("/v1/nostr/sign") {
            bearerAuth(phone.token)
            setBody(draft)
        }
        assertEquals(1, nudged)
    }

    @Test
    fun wakeMessageIsOnlyASignalAndTheJwtVerifies() {
        val json = FcmMessages.wake("device-token-0123456789")
        assertTrue(json.contains("\"what\":\"sign\""))
        assertTrue(json.contains("\"priority\":\"HIGH\""))
        assertFalse(json.contains("notification"))
        assertFalse(json.contains("nsec"))
        assertFalse(json.contains("nostr+walletconnect"))

        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val jwt = FcmMessages.jwt("nahtlos@example.iam.gserviceaccount.com", keys.private, 1_700_000_000)
        val parts = jwt.split('.')
        assertEquals(3, parts.size)
        val payload = String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)
        assertTrue(payload.contains("firebase.messaging"))
        assertFalse(payload.contains("BEGIN"))
        val verify = Signature.getInstance("SHA256withRSA")
        verify.initVerify(keys.public)
        verify.update("${parts[0]}.${parts[1]}".toByteArray(Charsets.UTF_8))
        assertTrue(verify.verify(Base64.getUrlDecoder().decode(parts[2])))
        assertEquals("fcm:nahtlos", ServiceAccount("nahtlos@example.iam.gserviceaccount.com", keys.private).toString())
        assertFalse(ServiceAccount("nahtlos@example.iam.gserviceaccount.com", keys.private).toString().contains("PRIVATE"))
    }
}
