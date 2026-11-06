package de.jenz.nahtlos.server

import de.jenz.nahtlos.sync.BoostBatchRequest
import de.jenz.nahtlos.sync.BoostSync
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put

fun Route.boostRoutes(database: SyncDatabase) {
    get("/boosts") {
        val since = call.request.queryParameters["since"]?.toLongOrNull()
        call.respond(database.listBoosts(since))
    }
    put("/boosts") {
        val body = call.receive<BoostBatchRequest>()
        require(body.items.size <= 80) { "Höchstens 80 Boosts pro Aufruf" }
        val deviceId = when (val auth = call.attributes.getOrNull(NahtlosAuthKey)) {
            is AuthKind.Device -> auth.id
            AuthKind.Admin -> "admin"
            else -> "unknown"
        }
        val stored = body.items.map { item ->
            val reason = BoostSync.rejectReason(item)
            if (reason != null) throw IllegalArgumentException(reason)
            database.upsertBoost(item, deviceId)
        }
        call.respond(stored)
    }
}
