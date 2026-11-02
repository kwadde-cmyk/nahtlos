package de.jenz.weiterhoeren.server

import de.jenz.weiterhoeren.model.ErrorBody
import de.jenz.weiterhoeren.model.EpisodeProgress
import de.jenz.weiterhoeren.model.ProgressBatchRequest
import de.jenz.weiterhoeren.model.ProgressBatchResponse
import de.jenz.weiterhoeren.model.Subscription
import de.jenz.weiterhoeren.model.SyncEvent
import de.jenz.weiterhoeren.model.WeiterJson
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.channels.ClosedSendChannelException
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

private val log = LoggerFactory.getLogger("weiterhoeren")

fun main() {
    val dbPath = System.getenv("SYNC_DB") ?: "data/sync.db"
    File(dbPath).parentFile?.mkdirs()
    val token = System.getenv("SYNC_TOKEN") ?: "dev-token-change-me"
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8787
    if (token == "dev-token-change-me") {
        log.warn("SYNC_TOKEN ist der Entwicklungsstandard. Vor dem Einsatz im Netz setzen.")
    }
    log.info("Sync-Dienst auf Port {} , Datenbank {}", port, dbPath)
    val database = SyncDatabase(dbPath)
    Runtime.getRuntime().addShutdownHook(Thread { database.close() })
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        module(database, token)
    }.start(wait = true)
}

fun Application.module(
    database: SyncDatabase,
    token: String,
    clock: () -> Long = { System.currentTimeMillis() },
) {
    val hub = ProgressHub()
    install(ContentNegotiation) { json(WeiterJson.json) }
    install(WebSockets)
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorBody(cause.message ?: "Ungültige Anfrage"))
        }
        exception<Throwable> { call, cause ->
            log.error("Anfrage fehlgeschlagen", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorBody("Interner Fehler"))
        }
    }
    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.local.uri.substringBefore('?')
        if (path == "/health") return@intercept
        val presented = bearerToken(call.request.header("Authorization"))
            ?: call.request.queryParameters["token"]
        if (presented != token) {
            call.respond(HttpStatusCode.Unauthorized, ErrorBody("Token fehlt oder ist falsch"))
            finish()
        }
    }

    routing {
        get("/health") { call.respond(mapOf("status" to "ok")) }

        route("/v1") {
            get("/subscriptions") { call.respond(database.listSubscriptions()) }

            put("/subscriptions") {
                val body = call.receive<Subscription>()
                val feedUrl = body.feedUrl.trim()
                require(feedUrl.startsWith("http://") || feedUrl.startsWith("https://")) { "feedUrl muss http(s) sein" }
                require(body.title.isNotBlank()) { "title fehlt" }
                val stored = body.copy(feedUrl = feedUrl.trimEnd('/'), title = body.title.trim(), updatedAt = clock())
                database.upsertSubscription(stored, stored.updatedAt)
                call.respond(stored)
            }

            delete("/subscriptions") {
                val feedUrl = call.request.queryParameters["feedUrl"]?.trim()?.trimEnd('/')
                require(!feedUrl.isNullOrBlank()) { "feedUrl fehlt" }
                database.deleteSubscription(feedUrl)
                call.respond(HttpStatusCode.NoContent)
            }

            get("/progress") {
                val episodeKey = call.request.queryParameters["episodeKey"]
                if (!episodeKey.isNullOrBlank()) {
                    val found = database.findProgress(episodeKey)
                    if (found == null) call.respond(HttpStatusCode.NotFound, ErrorBody("Keine Position für diese Episode"))
                    else call.respond(found)
                } else {
                    val since = call.request.queryParameters["since"]?.toLongOrNull()
                    call.respond(database.listProgress(since))
                }
            }

            put("/progress") {
                val incoming = call.receive<EpisodeProgress>().normalized()
                val error = incoming.validationError()
                if (error != null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorBody(error))
                    return@put
                }
                val result = database.applyProgress(incoming, clock())
                if (result.changed) hub.broadcast(SyncEvent(SyncEvent.TYPE_PROGRESS, result.response.stored))
                call.respond(result.response)
            }

            put("/progress/batch") {
                val request = call.receive<ProgressBatchRequest>()
                require(request.items.size <= 200) { "Höchstens 200 Positionen pro Aufruf" }
                val results = request.items.map { item ->
                    val incoming = item.normalized()
                    val error = incoming.validationError()
                    if (error != null) throw IllegalArgumentException("$error (${incoming.episodeKey})")
                    val result = database.applyProgress(incoming, clock())
                    if (result.changed) hub.broadcast(SyncEvent(SyncEvent.TYPE_PROGRESS, result.response.stored))
                    result.response
                }
                call.respond(ProgressBatchResponse(results))
            }

            webSocket("/stream") {
                hub.register(this)
                try {
                    send(Frame.Text("""{"type":"ready"}"""))
                    for (frame in incoming) {
                        if (frame is Frame.Close) break
                    }
                } finally {
                    hub.unregister(this)
                }
            }
        }

        // Door for a later gPodder / oPodSync adapter. Intentionally not implemented.
        route("/api/2") {
            get("{path...}") { gpodderNotImplemented() }
            post("{path...}") { gpodderNotImplemented() }
        }
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.gpodderNotImplemented() {
    call.respond(
        HttpStatusCode.NotImplemented,
        ErrorBody(
            "gPodder-API ist noch nicht umgesetzt. Bitte /v1 verwenden. " +
                "Geplante Abbildung: play.position (Sekunden) = positionMs/1000, " +
                "Identität über podcast:guid + Episoden-GUID statt Enclosure-URL.",
        ),
    )
}

private fun bearerToken(header: String?): String? {
    if (header.isNullOrBlank()) return null
    val prefix = "Bearer "
    return if (header.startsWith(prefix, ignoreCase = true)) header.substring(prefix.length).trim() else header.trim()
}

class ProgressHub {
    private val sessions = CopyOnWriteArraySet<WebSocketSession>()

    fun register(session: WebSocketSession) {
        sessions += session
    }

    fun unregister(session: WebSocketSession) {
        sessions -= session
    }

    suspend fun broadcast(event: SyncEvent) {
        val text = WeiterJson.json.encodeToString(SyncEvent.serializer(), event)
        for (session in sessions) {
            try {
                session.send(Frame.Text(text))
            } catch (_: ClosedSendChannelException) {
                sessions -= session
            } catch (_: Throwable) {
                sessions -= session
            }
        }
    }
}
