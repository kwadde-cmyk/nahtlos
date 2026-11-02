package de.jenz.nahtlos.server

import de.jenz.nahtlos.model.BootstrapRequest
import de.jenz.nahtlos.model.BootstrapResponse
import de.jenz.nahtlos.model.DeviceSummary
import de.jenz.nahtlos.model.ErrorBody
import de.jenz.nahtlos.model.NahtlosJson
import de.jenz.nahtlos.model.PairApproveRequest
import de.jenz.nahtlos.model.PairApproveResponse
import de.jenz.nahtlos.model.PairPollResponse
import de.jenz.nahtlos.model.PairStartResponse
import de.jenz.nahtlos.sync.PairingCodes
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
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
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PairingTest {
    @Test
    fun codeIsSingleUseAndDeviceTokenReplacesTheAdminToken() = testApplication {
        val db = tempDb()
        application { module(db, ADMIN) }
        val http = jsonClient()

        val started = start(http)
        assertEquals(300, started.expiresIn)
        assertTrue(PairingCodes.isWellFormed(started.code))
        assertFalse(started.code.any { it == '0' || it == 'O' || it == '1' || it == 'I' || it == 'L' })

        val pending = poll(http, started.pairingId)
        assertEquals(PairPollResponse.PENDING, pending.status)

        val approved = http.post("/v1/pair/approve") {
            bearerAuth(ADMIN)
            setBody(PairApproveRequest(code = started.code.lowercase(), name = "Auto"))
        }
        assertEquals(HttpStatusCode.OK, approved.status)
        val approval = NahtlosJson.json.decodeFromString(PairApproveResponse.serializer(), approved.bodyAsText())
        assertEquals("Auto", approval.name)

        val again = http.post("/v1/pair/approve") {
            bearerAuth(ADMIN)
            setBody(PairApproveRequest(code = started.code, name = "Noch ein Auto"))
        }
        assertEquals(HttpStatusCode.BadRequest, again.status)

        val granted = poll(http, started.pairingId)
        assertEquals(PairPollResponse.APPROVED, granted.status)
        assertTrue(!granted.token.isNullOrBlank())
        val second = poll(http, started.pairingId)
        assertEquals(PairPollResponse.CONSUMED, second.status)
        assertEquals(null, second.token)

        val withDevice = http.get("/v1/devices") { bearerAuth(granted.token!!) }
        assertEquals(HttpStatusCode.OK, withDevice.status)
        val devices = NahtlosJson.json.decodeFromString<List<DeviceSummary>>(withDevice.bodyAsText())
        assertEquals(approval.deviceId, devices.single().id)
        assertEquals("Auto", devices.single().name)
        assertFalse(devices.single().revoked)

        val revoked = http.delete("/v1/devices/${approval.deviceId}") { bearerAuth(ADMIN) }
        assertEquals(HttpStatusCode.NoContent, revoked.status)
        val after = http.get("/v1/subscriptions") { bearerAuth(granted.token!!) }
        assertEquals(HttpStatusCode.Unauthorized, after.status)
        val listed = NahtlosJson.json.decodeFromString<List<DeviceSummary>>(
            http.get("/v1/devices") { bearerAuth(ADMIN) }.bodyAsText(),
        )
        assertTrue(listed.single().revoked)
        db.close()
    }

    @Test
    fun twoStartsGetDifferentCodes() = testApplication {
        val db = tempDb()
        application { module(db, ADMIN) }
        val http = jsonClient()
        val first = start(http)
        val second = start(http)
        assertNotEquals(first.code, second.code)
        assertNotEquals(first.pairingId, second.pairingId)
        db.close()
    }

    @Test
    fun expiredCodeCannotBeApproved() = testApplication {
        val db = tempDb()
        val now = AtomicLong(1_700_000_000_000)
        application { module(db, ADMIN, clock = { now.get() }) }
        val http = jsonClient()
        val started = start(http)
        now.addAndGet(6 * 60_000L)
        val approved = http.post("/v1/pair/approve") {
            bearerAuth(ADMIN)
            setBody(PairApproveRequest(started.code, "Auto"))
        }
        assertEquals(HttpStatusCode.BadRequest, approved.status)
        val polled = poll(http, started.pairingId)
        assertEquals(PairPollResponse.EXPIRED, polled.status)
        db.close()
    }

    @Test
    fun startIsRateLimited() = testApplication {
        val db = tempDb()
        application { module(db, ADMIN) }
        val http = jsonClient()
        repeat(PairingGuard.MAX_STARTS) {
            assertEquals(HttpStatusCode.OK, http.post("/v1/pair/start").status)
        }
        val blocked = http.post("/v1/pair/start")
        assertEquals(HttpStatusCode.TooManyRequests, blocked.status)
        assertTrue(NahtlosJson.json.decodeFromString(ErrorBody.serializer(), blocked.bodyAsText()).error.contains("warten"))
        db.close()
    }

    @Test
    fun wrongCodesLockTheApprover() = testApplication {
        val db = tempDb()
        application { module(db, ADMIN) }
        val http = jsonClient()
        repeat(PairingGuard.MAX_FAILURES) { attempt ->
            val response = http.post("/v1/pair/approve") {
                bearerAuth(ADMIN)
                setBody(PairApproveRequest("ZZZ-ZZ${attempt}", "Auto"))
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, "attempt $attempt")
        }
        val started = start(http)
        val locked = http.post("/v1/pair/approve") {
            bearerAuth(ADMIN)
            setBody(PairApproveRequest(started.code, "Auto"))
        }
        assertEquals(HttpStatusCode.TooManyRequests, locked.status)
        db.close()
    }

    @Test
    fun adminTokenBootstrapsOnePhoneToken() = testApplication {
        val db = tempDb()
        application { module(db, ADMIN) }
        val http = jsonClient()
        val created = http.post("/v1/devices/bootstrap") {
            bearerAuth(ADMIN)
            setBody(BootstrapRequest("Handy"))
        }
        assertEquals(HttpStatusCode.OK, created.status)
        val phone = NahtlosJson.json.decodeFromString(BootstrapResponse.serializer(), created.bodyAsText())
        assertEquals("Handy", phone.name)
        assertTrue(phone.token.length >= 32)
        val listed = http.get("/v1/devices") { bearerAuth(phone.token) }
        assertEquals(HttpStatusCode.OK, listed.status)
        val again = http.post("/v1/devices/bootstrap") {
            bearerAuth(phone.token)
            setBody(BootstrapRequest("Noch ein Handy"))
        }
        assertEquals(HttpStatusCode.Forbidden, again.status)
        val stillAdmin = http.get("/v1/devices") { bearerAuth(ADMIN) }
        assertEquals(HttpStatusCode.OK, stillAdmin.status)
        db.close()
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.start(
        http: io.ktor.client.HttpClient,
    ): PairStartResponse {
        val response = http.post("/v1/pair/start")
        assertEquals(HttpStatusCode.OK, response.status)
        return NahtlosJson.json.decodeFromString(PairStartResponse.serializer(), response.bodyAsText())
    }

    private suspend fun poll(http: io.ktor.client.HttpClient, pairingId: String): PairPollResponse {
        val response = http.get("/v1/pair/poll?pairingId=$pairingId")
        assertEquals(HttpStatusCode.OK, response.status)
        return NahtlosJson.json.decodeFromString(PairPollResponse.serializer(), response.bodyAsText())
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.jsonClient() = createClient {
        install(ContentNegotiation) { json(NahtlosJson.json) }
        defaultRequest { contentType(ContentType.Application.Json) }
    }

    private fun tempDb(): SyncDatabase {
        val dir = Files.createTempDirectory("nahtlos-pair")
        return SyncDatabase(dir.resolve("sync.db").toString())
    }

    companion object {
        const val ADMIN = "test-token"
    }
}
