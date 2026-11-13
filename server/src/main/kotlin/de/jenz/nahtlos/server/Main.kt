package de.jenz.nahtlos.server

import de.jenz.nahtlos.model.ErrorBody
import de.jenz.nahtlos.model.EpisodeProgress
import de.jenz.nahtlos.model.NahtlosJson
import de.jenz.nahtlos.model.ProgressBatchRequest
import de.jenz.nahtlos.model.ProgressBatchResponse
import de.jenz.nahtlos.model.Subscription
import de.jenz.nahtlos.model.SyncEvent
import de.jenz.nahtlos.opml.Opml
import de.jenz.nahtlos.opml.OpmlOutline
import de.jenz.nahtlos.playlist.ListeningPlaylist
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.http.ContentType
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
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

private val log = LoggerFactory.getLogger("nahtlos")

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
    val guard = PairingGuard(clock)
    val random = java.security.SecureRandom()
    install(XForwardedHeaders)
    install(ContentNegotiation) { json(NahtlosJson.json) }
    install(WebSockets) { pingPeriodMillis = 25_000 }
    install(StatusPages) {
        exception<RateLimitedException> { call, cause ->
            call.respond(HttpStatusCode.TooManyRequests, ErrorBody(cause.message ?: "Zu viele Versuche"))
        }
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
        if (path == "/health" || path == "/v1/pair/start" || path == "/v1/pair/poll") return@intercept
        val presented = bearerToken(call.request.header("Authorization"))
            ?: call.request.queryParameters["token"]
        val auth = if (presented != null && constantTimeEquals(presented, token)) {
            AuthKind.Admin
        } else if (presented != null) {
            database.touchDevice(presented, clock())?.let { AuthKind.Device(it) }
        } else {
            null
        }
        if (auth == null) {
            call.respond(HttpStatusCode.Unauthorized, ErrorBody("Token fehlt oder ist falsch"))
            finish()
            return@intercept
        }
        call.attributes.put(NahtlosAuthKey, auth)
    }

    routing {
        get("/health") { call.respond(mapOf("status" to "ok")) }

        route("/v1") {
            deviceRoutes(database, clock, guard, random)
            walletRelayRoutes(database, clock, guard)
            nostrRoutes(database, clock, guard)
            boostRoutes(database)
            diagnoseRoutes(database)
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

            get("/subscriptions/opml") {
                val outlines = database.listSubscriptions().map { OpmlOutline(it.title, it.feedUrl) }
                call.respondText(Opml.write("Nahtlos", outlines), ContentType.Application.Xml)
            }

            put("/subscriptions/opml") {
                val raw = call.receiveText()
                val xml = if (raw.trimStart().startsWith("{")) {
                    NahtlosJson.json.decodeFromString<OpmlBody>(raw).opml
                } else {
                    raw
                }
                val outlines = Opml.parse(xml)
                require(outlines.isNotEmpty()) { "Keine Feeds in der OPML-Datei" }
                val stored = outlines.map { outline ->
                    val subscription = Subscription(
                        feedUrl = outline.feedUrl.trimEnd('/'),
                        title = outline.title.ifBlank { outline.feedUrl },
                        updatedAt = clock(),
                    )
                    database.upsertSubscription(subscription, subscription.updatedAt)
                    subscription
                }
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

            get("/playlist") {
                call.respond(database.getPlaylist() ?: ListeningPlaylist())
            }

            put("/playlist") {
                val incoming = call.receive<ListeningPlaylist>()
                val error = incoming.validationError()
                if (error != null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorBody(error))
                    return@put
                }
                val result = database.applyPlaylist(incoming, clock())
                if (result.changed) {
                    hub.broadcast(SyncEvent(SyncEvent.TYPE_PLAYLIST, playlist = result.response.stored))
                }
                call.respond(result.response)
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

@kotlinx.serialization.Serializable
private data class OpmlBody(val opml: String)

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
        val text = NahtlosJson.json.encodeToString(SyncEvent.serializer(), event)
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
