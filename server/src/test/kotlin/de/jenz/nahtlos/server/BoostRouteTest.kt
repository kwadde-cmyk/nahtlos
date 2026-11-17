package de.jenz.nahtlos.server

import de.jenz.nahtlos.model.BootstrapRequest
import de.jenz.nahtlos.model.BootstrapResponse
import de.jenz.nahtlos.model.NahtlosJson
import de.jenz.nahtlos.sync.BoostBatchRequest
import de.jenz.nahtlos.sync.BoostPrefs
import de.jenz.nahtlos.sync.BoostRecipientSync
import de.jenz.nahtlos.sync.BoostSyncItem
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
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoostRouteTest {
    @Test
    fun sameIdDoesNotDuplicateAndSecretsAreRejected() = testApplication {
        val dir = Files.createTempDirectory("nahtlos-boost")
        val db = SyncDatabase(dir.resolve("sync.db").toString())
        application { module(db, ADMIN) }
        val http = createClient {
            install(ContentNegotiation) { json(NahtlosJson.json) }
            defaultRequest { contentType(ContentType.Application.Json) }
        }
        val phone = device(http, "Handy")
        val car = device(http, "Auto")
        val first = item(updated = 100, status = "gesendet")
        val put = http.put("/v1/boosts") {
            bearerAuth(phone.token)
            setBody(BoostBatchRequest(listOf(first)))
        }
        assertEquals(HttpStatusCode.OK, put.status)
        val older = http.put("/v1/boosts") {
            bearerAuth(car.token)
            setBody(BoostBatchRequest(listOf(first.copy(updatedAt = 50, totalSats = 1))))
        }
        assertEquals(HttpStatusCode.OK, older.status)
        val listed = NahtlosJson.json.decodeFromString<List<BoostSyncItem>>(
            http.get("/v1/boosts") { bearerAuth(car.token) }.bodyAsText(),
        )
        assertEquals(1, listed.size)
        assertEquals(210, listed[0].totalSats)
        val newer = http.put("/v1/boosts") {
            bearerAuth(car.token)
            setBody(BoostBatchRequest(listOf(first.copy(updatedAt = 200, recipients = first.recipients.map { it.copy(status = "fehlgeschlagen") }))))
        }
        assertEquals(HttpStatusCode.OK, newer.status)
        val after = NahtlosJson.json.decodeFromString<List<BoostSyncItem>>(
            http.get("/v1/boosts") { bearerAuth(phone.token) }.bodyAsText(),
        )
        assertEquals(1, after.size)
        assertEquals("fehlgeschlagen", after[0].recipients[0].status)
        val secret = http.put("/v1/boosts") {
            bearerAuth(phone.token)
            setBody(BoostBatchRequest(listOf(first.copy(id = "other", episode = "nostr+walletconnect://aa?secret=bb"))))
        }
        assertEquals(HttpStatusCode.BadRequest, secret.status)
        val invoice = http.put("/v1/boosts") {
            bearerAuth(phone.token)
            setBody(BoostBatchRequest(listOf(first.copy(id = "inv", recipients = listOf(first.recipients[0].copy(address = "lnbc1invoice"))))))
        }
        assertEquals(HttpStatusCode.BadRequest, invoice.status)
        val body = http.get("/v1/boosts") { bearerAuth(phone.token) }.bodyAsText()
        assertFalse(body.contains("nostr+walletconnect"))
        assertFalse(body.contains("lnbc1"))
        assertFalse(body.contains("preimage"))
        val noted = http.put("/v1/boosts") {
            bearerAuth(phone.token)
            setBody(BoostBatchRequest(listOf(first.copy(id = "noted", updatedAt = 10, message = "Danke fürs Zuhören", senderName = "Ada"))))
        }
        assertEquals(HttpStatusCode.OK, noted.status)
        val wiped = http.put("/v1/boosts") {
            bearerAuth(car.token)
            setBody(BoostBatchRequest(listOf(first.copy(id = "noted", updatedAt = 30, message = "", totalSats = 21))))
        }
        assertEquals(HttpStatusCode.OK, wiped.status)
        val notes = NahtlosJson.json.decodeFromString<List<BoostSyncItem>>(
            http.get("/v1/boosts") { bearerAuth(phone.token) }.bodyAsText(),
        )
        val kept = notes.first { it.id == "noted" }
        assertEquals("Danke fürs Zuhören", kept.message)
        assertEquals("Ada", kept.senderName)
        assertEquals(21, kept.totalSats)
        val leaked = http.put("/v1/boosts") {
            bearerAuth(phone.token)
            setBody(BoostBatchRequest(listOf(first.copy(id = "leak", message = "nostr+walletconnect://aa"))))
        }
        assertEquals(HttpStatusCode.BadRequest, leaked.status)
        val emptyPrefs = NahtlosJson.json.decodeFromString<BoostPrefs>(
            http.get("/v1/boost-prefs") { bearerAuth(phone.token) }.bodyAsText(),
        )
        assertTrue(emptyPrefs.phrases.isEmpty())
        val saved = http.put("/v1/boost-prefs") {
            bearerAuth(phone.token)
            setBody(BoostPrefs(listOf("Danke", "Klasse Folge"), nostrComment = true, updatedAt = 50))
        }
        assertEquals(HttpStatusCode.OK, saved.status)
        val olderPrefs = http.put("/v1/boost-prefs") {
            bearerAuth(car.token)
            setBody(BoostPrefs(listOf("Alt"), nostrComment = false, updatedAt = 10))
        }
        assertEquals(HttpStatusCode.OK, olderPrefs.status)
        val prefs = NahtlosJson.json.decodeFromString<BoostPrefs>(
            http.get("/v1/boost-prefs") { bearerAuth(car.token) }.bodyAsText(),
        )
        assertEquals(listOf("Danke", "Klasse Folge"), prefs.phrases)
        assertTrue(prefs.nostrComment)
        val poisoned = http.put("/v1/boost-prefs") {
            bearerAuth(phone.token)
            setBody(BoostPrefs(listOf("nsec1shouldnot"), nostrComment = true, updatedAt = 80))
        }
        assertEquals(HttpStatusCode.BadRequest, poisoned.status)
        db.close()
    }

    @Test
    fun oldDatabaseKeepsProgressWhenBoostsAppear() {
        val path = Files.createTempDirectory("nahtlos-old").resolve("sync.db").toString()
        DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.createStatement().execute(
                """
                CREATE TABLE progress (
                  episode_key TEXT PRIMARY KEY,
                  podcast_guid TEXT,
                  episode_guid TEXT,
                  feed_url TEXT,
                  position_ms INTEGER NOT NULL,
                  duration_ms INTEGER NOT NULL,
                  updated_at INTEGER NOT NULL,
                  device_id TEXT NOT NULL,
                  status TEXT NOT NULL,
                  server_received_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            connection.prepareStatement(
                "INSERT INTO progress(episode_key, podcast_guid, episode_guid, feed_url, position_ms, duration_ms, updated_at, device_id, status, server_received_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, "ep")
                statement.setString(2, null)
                statement.setString(3, null)
                statement.setString(4, null)
                statement.setLong(5, 12_000)
                statement.setLong(6, 60_000)
                statement.setLong(7, 10)
                statement.setString(8, "handy")
                statement.setString(9, "PAUSED")
                statement.setLong(10, 10)
                statement.executeUpdate()
            }
        }
        val db = SyncDatabase(path)
        assertEquals(12_000, db.findProgress("ep")?.positionMs)
        assertTrue(db.listBoosts(null).isEmpty())
        db.upsertBoost(item(updated = 10, status = "gesendet"), "handy")
        db.close()
        val again = SyncDatabase(path)
        assertEquals(12_000, again.findProgress("ep")?.positionMs)
        assertEquals(1, again.listBoosts(null).size)
        assertEquals("", again.listBoosts(null).single().message)
        again.close()
    }

    @Test
    fun oldBoostTableGainsMessageWithoutLosingRows() {
        val path = Files.createTempDirectory("nahtlos-boost-old").resolve("sync.db").toString()
        DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.createStatement().execute(
                """
                CREATE TABLE boosts (
                  id TEXT PRIMARY KEY,
                  at_ms INTEGER NOT NULL,
                  updated_at INTEGER NOT NULL,
                  total_sats INTEGER NOT NULL,
                  podcast TEXT NOT NULL,
                  episode TEXT NOT NULL,
                  recipients_json TEXT NOT NULL,
                  device_id TEXT NOT NULL
                )
                """.trimIndent(),
            )
            connection.prepareStatement(
                "INSERT INTO boosts(id, at_ms, updated_at, total_sats, podcast, episode, recipients_json, device_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, "old")
                statement.setLong(2, 5)
                statement.setLong(3, 5)
                statement.setLong(4, 21)
                statement.setString(5, "Show")
                statement.setString(6, "Folge")
                statement.setString(7, """[{"name":"Studio","address":"03aa","sats":21,"status":"gesendet"}]""")
                statement.setString(8, "handy")
                statement.executeUpdate()
            }
        }
        val db = SyncDatabase(path)
        val listed = db.listBoosts(null)
        assertEquals(1, listed.size)
        assertEquals("", listed[0].message)
        db.upsertBoost(listed[0].copy(updatedAt = 9, message = "Nachgezogen"), "handy")
        assertEquals("Nachgezogen", db.listBoosts(null).single().message)
        assertEquals(21, db.listBoosts(null).single().totalSats)
        db.close()
    }

    private fun item(updated: Long, status: String) = BoostSyncItem(
        id = "pay-1",
        atMs = 1_700_000_000_000,
        updatedAt = updated,
        totalSats = 210,
        podcast = "Nodesignal",
        episode = "Recap",
        recipients = listOf(BoostRecipientSync("Studio", "03abcdef", 210, status)),
    )

    private suspend fun device(http: io.ktor.client.HttpClient, name: String): BootstrapResponse =
        NahtlosJson.json.decodeFromString(
            http.post("/v1/devices/bootstrap") {
                bearerAuth(ADMIN)
                setBody(BootstrapRequest(name))
            }.bodyAsText(),
        )
}

private const val ADMIN = "test-admin-token"
