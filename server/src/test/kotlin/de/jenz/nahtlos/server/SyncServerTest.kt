package de.jenz.nahtlos.server

import de.jenz.nahtlos.model.EpisodeProgress
import de.jenz.nahtlos.model.ErrorBody
import de.jenz.nahtlos.model.ProgressBatchRequest
import de.jenz.nahtlos.model.ProgressBatchResponse
import de.jenz.nahtlos.model.ProgressStatus
import de.jenz.nahtlos.model.ProgressWriteResponse
import de.jenz.nahtlos.model.Subscription
import de.jenz.nahtlos.model.NahtlosJson
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.client.plugins.defaultRequest
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.client.plugins.websocket.WebSockets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.withTimeout

class SyncServerTest {
    @Test
    fun healthDoesNotNeedAToken() = testApplication {
        val db = tempDb()
        application { module(db, TOKEN) }
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("ok"))
        db.close()
    }

    @Test
    fun rejectsMissingAndWrongTokens() = testApplication {
        val db = tempDb()
        application { module(db, TOKEN) }
        val http = jsonClient()
        val missing = http.put("/v1/progress") { setBody(sample(updatedAt = 10, positionMs = 1)) }
        assertEquals(HttpStatusCode.Unauthorized, missing.status)
        val wrong = http.put("/v1/progress") {
            bearerAuth("nope")
            setBody(sample(updatedAt = 10, positionMs = 1))
        }
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        db.close()
    }

    @Test
    fun phonePauseThenCarReadReturnsTheSameMillisecond() = testApplication {
        val db = tempDb()
        application { module(db, TOKEN) }
        val http = jsonClient()
        val paused = sample(updatedAt = 2_000, positionMs = 305_000, deviceId = "phone", status = ProgressStatus.PAUSED)
        val put = http.put("/v1/progress") {
            bearerAuth(TOKEN)
            setBody(paused)
        }
        assertEquals(HttpStatusCode.OK, put.status)
        val written = NahtlosJson.json.decodeFromString(ProgressWriteResponse.serializer(), put.bodyAsText())
        assertTrue(written.accepted)
        assertEquals(305_000, written.stored.positionMs)

        val got = http.get("/v1/progress?episodeKey=pod|ep") { bearerAuth(TOKEN) }
        val stored = NahtlosJson.json.decodeFromString(EpisodeProgress.serializer(), got.bodyAsText())
        assertEquals(305_000, stored.positionMs)
        assertEquals("phone", stored.deviceId)
        assertEquals(2_000, stored.updatedAt)
        db.close()
    }

    @Test
    fun olderWriteDoesNotOverwriteAndNewerRewindDoes() = testApplication {
        val db = tempDb()
        application { module(db, TOKEN) }
        val http = jsonClient()
        http.put("/v1/progress") {
            bearerAuth(TOKEN)
            setBody(sample(updatedAt = 500, positionMs = 80_000, deviceId = "car"))
        }
        val older = http.put("/v1/progress") {
            bearerAuth(TOKEN)
            setBody(sample(updatedAt = 100, positionMs = 400_000, deviceId = "phone"))
        }
        val rejected = NahtlosJson.json.decodeFromString(ProgressWriteResponse.serializer(), older.bodyAsText())
        assertFalse(rejected.accepted)
        assertEquals(80_000, rejected.stored.positionMs)
        assertEquals("car", rejected.stored.deviceId)

        val rewind = http.put("/v1/progress") {
            bearerAuth(TOKEN)
            setBody(sample(updatedAt = 900, positionMs = 5_000, deviceId = "phone", status = ProgressStatus.PAUSED))
        }
        val accepted = NahtlosJson.json.decodeFromString(ProgressWriteResponse.serializer(), rewind.bodyAsText())
        assertTrue(accepted.accepted)
        assertEquals(5_000, accepted.stored.positionMs)
        db.close()
    }

    @Test
    fun equalTimestampHigherPositionWins() = testApplication {
        val db = tempDb()
        application { module(db, TOKEN) }
        val http = jsonClient()
        http.put("/v1/progress") {
            bearerAuth(TOKEN)
            setBody(sample(updatedAt = 50, positionMs = 1_000, deviceId = "phone"))
        }
        val higher = http.put("/v1/progress") {
            bearerAuth(TOKEN)
            setBody(sample(updatedAt = 50, positionMs = 9_000, deviceId = "car"))
        }
        val body = NahtlosJson.json.decodeFromString(ProgressWriteResponse.serializer(), higher.bodyAsText())
        assertTrue(body.accepted)
        assertEquals(9_000, body.stored.positionMs)
        db.close()
    }

    @Test
    fun identicalRetryIsAcceptedWithoutChangingThePosition() = testApplication {
        val db = tempDb()
        application { module(db, TOKEN) }
        val http = jsonClient()
        val body = sample(updatedAt = 40, positionMs = 12_000, deviceId = "phone")
        http.put("/v1/progress") {
            bearerAuth(TOKEN)
            setBody(body)
        }
        val retry = http.put("/v1/progress") {
            bearerAuth(TOKEN)
            setBody(body)
        }
        val parsed = NahtlosJson.json.decodeFromString(ProgressWriteResponse.serializer(), retry.bodyAsText())
        assertTrue(parsed.accepted)
        assertEquals(12_000, parsed.stored.positionMs)
        assertEquals("phone", parsed.stored.deviceId)
        db.close()
    }

    @Test
    fun batchAndSubscriptionsRoundTrip() = testApplication {
        val db = tempDb()
        application { module(db, TOKEN) }
        val http = jsonClient()
        val created = http.put("/v1/subscriptions") {
            bearerAuth(TOKEN)
            setBody(Subscription(feedUrl = "https://example.com/feed/", title = "Kabine", podcastGuid = "guid"))
        }
        assertEquals(HttpStatusCode.OK, created.status)
        val list = NahtlosJson.json.decodeFromString<List<Subscription>>(
            http.get("/v1/subscriptions") { bearerAuth(TOKEN) }.bodyAsText(),
        )
        assertEquals("https://example.com/feed", list.single().feedUrl)
        assertEquals("Kabine", list.single().title)

        val batch = http.put("/v1/progress/batch") {
            bearerAuth(TOKEN)
            setBody(
                ProgressBatchRequest(
                    listOf(
                        sample(updatedAt = 1, positionMs = 10, deviceId = "phone").copy(episodeKey = "a"),
                        sample(updatedAt = 1, positionMs = 20, deviceId = "car").copy(episodeKey = "b"),
                    ),
                ),
            )
        }
        val results = NahtlosJson.json.decodeFromString(ProgressBatchResponse.serializer(), batch.bodyAsText())
        assertEquals(2, results.results.size)
        assertTrue(results.results.all { it.accepted })

        val deleted = http.delete("/v1/subscriptions?feedUrl=https://example.com/feed") { bearerAuth(TOKEN) }
        assertEquals(HttpStatusCode.NoContent, deleted.status)
        val after = NahtlosJson.json.decodeFromString<List<Subscription>>(
            http.get("/v1/subscriptions") { bearerAuth(TOKEN) }.bodyAsText(),
        )
        assertTrue(after.isEmpty())
        db.close()
    }

    @Test
    fun gpodderRoutesStayClosed() = testApplication {
        val db = tempDb()
        application { module(db, TOKEN) }
        val response = client.get("/api/2/episodes/demo.json") { header(HttpHeaders.Authorization, "Bearer $TOKEN") }
        assertEquals(HttpStatusCode.NotImplemented, response.status)
        val error = NahtlosJson.json.decodeFromString(ErrorBody.serializer(), response.bodyAsText())
        assertTrue(error.error.contains("gPodder"))
        db.close()
    }

    @Test
    fun openSocketReceivesAProgressPush() = testApplication {
        val db = tempDb()
        application { module(db, TOKEN) }
        val http = jsonClient()
        val sockets = createClient { install(WebSockets) }
        sockets.webSocket("/v1/stream?token=$TOKEN") {
            val ready = incoming.receive() as Frame.Text
            assertTrue(ready.readText().contains("ready"))
            http.put("/v1/progress") {
                bearerAuth(TOKEN)
                setBody(sample(updatedAt = 70, positionMs = 15_000, deviceId = "phone"))
            }
            val event = withTimeout(5_000) { incoming.receive() } as Frame.Text
            assertTrue(event.readText().contains("15000"))
            assertTrue(event.readText().contains("progress"))
        }
        db.close()
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.jsonClient() = createClient {
        install(ContentNegotiation) { json(NahtlosJson.json) }
        defaultRequest { contentType(ContentType.Application.Json) }
    }

    private fun sample(
        updatedAt: Long,
        positionMs: Long,
        deviceId: String = "phone",
        status: ProgressStatus = ProgressStatus.PAUSED,
    ) = EpisodeProgress(
        episodeKey = "pod|ep",
        podcastGuid = "pod",
        episodeGuid = "ep",
        feedUrl = "https://example.com/feed",
        positionMs = positionMs,
        durationMs = 1_800_000,
        updatedAt = updatedAt,
        deviceId = deviceId,
        status = status,
    )

    private fun tempDb(): SyncDatabase {
        val dir = Files.createTempDirectory("nahtlos-sync")
        return SyncDatabase(dir.resolve("sync.db").toString())
    }

    companion object {
        const val TOKEN = "test-token"
    }
}
