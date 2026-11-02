package de.jenz.nahtlos.server

import de.jenz.nahtlos.model.NahtlosJson
import de.jenz.nahtlos.playlist.ListeningPlaylist
import de.jenz.nahtlos.playlist.PlaylistEntry
import de.jenz.nahtlos.playlist.PlaylistSort
import de.jenz.nahtlos.playlist.PlaylistWriteResponse
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaylistAndOpmlTest {
    @Test
    fun newerPlaylistReplacesTheOlderOne() = testApplication {
        val db = tempDb()
        application { module(db, TOKEN) }
        val http = jsonClient()
        val first = playlist(updatedAt = 10, keys = listOf("a"))
        val created = http.put("/v1/playlist") {
            bearerAuth(TOKEN)
            setBody(first)
        }
        assertEquals(HttpStatusCode.OK, created.status)
        val accepted = NahtlosJson.json.decodeFromString(PlaylistWriteResponse.serializer(), created.bodyAsText())
        assertTrue(accepted.accepted)
        assertEquals(listOf("a"), accepted.stored.items.map { it.episodeKey })

        val older = http.put("/v1/playlist") {
            bearerAuth(TOKEN)
            setBody(playlist(updatedAt = 5, keys = listOf("b"), deviceId = "car"))
        }
        val rejected = NahtlosJson.json.decodeFromString(PlaylistWriteResponse.serializer(), older.bodyAsText())
        assertFalse(rejected.accepted)
        assertEquals(listOf("a"), rejected.stored.items.map { it.episodeKey })

        val newer = http.put("/v1/playlist") {
            bearerAuth(TOKEN)
            setBody(playlist(updatedAt = 20, keys = listOf("b", "c"), deviceId = "car"))
        }
        val replaced = NahtlosJson.json.decodeFromString(PlaylistWriteResponse.serializer(), newer.bodyAsText())
        assertTrue(replaced.accepted)
        assertEquals(listOf("b", "c"), replaced.stored.items.map { it.episodeKey })

        val fetched = NahtlosJson.json.decodeFromString(
            ListeningPlaylist.serializer(),
            http.get("/v1/playlist") { bearerAuth(TOKEN) }.bodyAsText(),
        )
        assertEquals(listOf("b", "c"), fetched.items.map { it.episodeKey })
        db.close()
    }

    @Test
    fun opmlImportBecomesSubscriptionsTheCarCanRead() = testApplication {
        val db = tempDb()
        application { module(db, TOKEN) }
        val http = jsonClient()
        val opml = """
            <opml version="2.0"><body>
              <outline text="Kabine" title="Kabine" type="rss" xmlUrl="https://example.com/kabine.xml"/>
            </body></opml>
        """.trimIndent()
        val imported = http.put("/v1/subscriptions/opml") {
            bearerAuth(TOKEN)
            contentType(ContentType.Application.Xml)
            setBody(opml)
        }
        assertEquals(HttpStatusCode.OK, imported.status)
        val exported = http.get("/v1/subscriptions/opml") { bearerAuth(TOKEN) }.bodyAsText()
        assertTrue(exported.contains("https://example.com/kabine"))
        assertTrue(exported.contains("Kabine"))
        db.close()
    }

    private fun playlist(updatedAt: Long, keys: List<String>, deviceId: String = "phone") = ListeningPlaylist(
        sinceEpochMs = 1_000,
        sort = PlaylistSort.OLDEST_FIRST,
        removeWhenFinished = true,
        items = keys.map { PlaylistEntry(episodeKey = it, title = it, enclosureUrl = "https://cdn.example/$it.mp3") },
        updatedAt = updatedAt,
        deviceId = deviceId,
    )

    private fun ApplicationTestBuilder.jsonClient() = createClient {
        install(ContentNegotiation) { json(NahtlosJson.json) }
        defaultRequest { contentType(ContentType.Application.Json) }
    }

    private fun tempDb(): SyncDatabase {
        val dir = Files.createTempDirectory("nahtlos-playlist")
        return SyncDatabase(dir.resolve("sync.db").toString())
    }

    companion object {
        const val TOKEN = "test-token"
    }
}
