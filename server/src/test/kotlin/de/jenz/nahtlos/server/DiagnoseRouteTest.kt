package de.jenz.nahtlos.server

import de.jenz.nahtlos.diagnose.DiagnoseNote
import de.jenz.nahtlos.diagnose.DiagnoseUpload
import de.jenz.nahtlos.model.BootstrapRequest
import de.jenz.nahtlos.model.BootstrapResponse
import de.jenz.nahtlos.model.NahtlosJson
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DiagnoseRouteTest {
    @Test
    fun carLogReachesThePhoneWithoutSecrets() = testApplication {
        val db = SyncDatabase(Files.createTempDirectory("nahtlos-diagnose").resolve("sync.db").toString())
        application { module(db, ADMIN) }
        val http = createClient {
            install(ContentNegotiation) { json(NahtlosJson.json) }
            defaultRequest { contentType(ContentType.Application.Json) }
        }
        val car = NahtlosJson.json.decodeFromString<BootstrapResponse>(
            http.post("/v1/devices/bootstrap") {
                bearerAuth(ADMIN)
                setBody(BootstrapRequest("Auto"))
            }.bodyAsText(),
        )
        val stored = http.put("/v1/diagnose") {
            bearerAuth(car.token)
            setBody(DiagnoseUpload(10, "Ordner root: 2 Einträge. Token bleibt draußen."))
        }
        assertEquals(HttpStatusCode.OK, stored.status)
        val rejected = http.put("/v1/diagnose") {
            bearerAuth(car.token)
            setBody(DiagnoseUpload(11, "nostr+walletconnect://abc"))
        }
        assertEquals(HttpStatusCode.BadRequest, rejected.status)
        val body = http.get("/v1/diagnose") { bearerAuth(ADMIN) }.bodyAsText()
        assertFalse(body.contains("nostr+walletconnect"))
        val notes = NahtlosJson.json.decodeFromString<List<DiagnoseNote>>(body)
        assertEquals(1, notes.size)
        assertEquals("Ordner root: 2 Einträge. Token bleibt draußen.", notes[0].body)
        db.close()
    }
}

private const val ADMIN = "diagnose-admin"
