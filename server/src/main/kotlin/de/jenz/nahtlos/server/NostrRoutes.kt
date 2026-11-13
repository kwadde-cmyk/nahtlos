package de.jenz.nahtlos.server

import de.jenz.nahtlos.model.ErrorBody
import de.jenz.nahtlos.model.NahtlosJson
import de.jenz.nahtlos.model.NostrIdentityBody
import de.jenz.nahtlos.model.NostrSignRequest
import de.jenz.nahtlos.model.NostrSignStatus
import de.jenz.nahtlos.model.NostrSignedBody
import de.jenz.nahtlos.nostr.NostrKeys
import de.jenz.nahtlos.nostr.NostrQueue
import de.jenz.nahtlos.nostr.PodcastNostr
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject

fun Route.nostrRoutes(database: SyncDatabase, clock: () -> Long, guard: PairingGuard) {
    route("/nostr") {
        put("/identity") {
            guard.allowNostr(clientIp())
            val device = call.deviceId()
            val body = call.receive<NostrIdentityBody>()
            val pubkey = body.pubkey.trim().lowercase()
            if (!pubkey.matches(HEX_64)) throw IllegalArgumentException("Öffentlicher Schlüssel ungültig")
            val name = body.name.trim().take(80)
            val picture = body.picture?.trim()?.takeIf { it.startsWith("https://") && it.length <= 300 }
            val reason = NostrQueue.reject(listOf(pubkey, name, picture.orEmpty()).joinToString(" "))
            if (reason != null) throw IllegalArgumentException(reason)
            database.saveNostrIdentity(pubkey, name, picture, device, clock())
            call.respond(mapOf("status" to "stored"))
        }

        get("/identity") {
            guard.allowNostr(clientIp())
            call.deviceId()
            val stored = database.readNostrIdentity()
            if (stored == null) {
                call.respond(HttpStatusCode.NotFound, ErrorBody("Noch kein Nostr-Konto"))
                return@get
            }
            call.respond(stored)
        }

        post("/sign") {
            guard.allowNostr(clientIp())
            val device = call.deviceId()
            val body = call.receive<NostrSignRequest>()
            val reason = NostrQueue.checkDraft(body.id.trim(), body.kind, body.content, body.createdAt)
                ?: NostrQueue.reject(NahtlosJson.json.encodeToString(tagSerializer, body.tags))
            if (reason != null) throw IllegalArgumentException(reason)
            val now = clock()
            val existing = database.readNostrSign(body.id.trim(), now)
            if (existing != null) {
                call.respond(existing.copy(event = null))
                return@post
            }
            database.insertNostrDraft(
                id = body.id.trim(),
                kind = body.kind,
                content = body.content,
                tagsJson = NahtlosJson.json.encodeToString(tagSerializer, body.tags),
                createdAt = body.createdAt,
                deviceId = device,
                now = now,
                expiresAt = now + NostrQueue.TTL_MS,
            )
            call.respond(
                NostrSignStatus(
                    id = body.id.trim(),
                    status = NostrSignStatus.PENDING,
                    kind = body.kind,
                    expiresIn = (NostrQueue.TTL_MS / 1000L).toInt(),
                ),
            )
        }

        get("/sign") {
            guard.allowNostr(clientIp())
            call.deviceId()
            call.respond(database.listPendingNostr(clock()))
        }

        get("/sign/{id}") {
            guard.allowNostr(clientIp())
            call.deviceId()
            val id = call.parameters["id"]?.trim().orEmpty()
            val row = database.readNostrSign(id, clock())
            if (row == null) {
                call.respond(HttpStatusCode.NotFound, ErrorBody("Auftrag unbekannt"))
                return@get
            }
            call.respond(row)
        }

        post("/sign/{id}") {
            guard.allowNostr(clientIp())
            call.deviceId()
            val id = call.parameters["id"]?.trim().orEmpty()
            val body = call.receive<NostrSignedBody>()
            val reason = NostrQueue.reject(body.event)
            if (reason != null) throw IllegalArgumentException(reason)
            val now = clock()
            val draft = database.readNostrSign(id, now) ?: throw IllegalArgumentException("Auftrag unbekannt")
            if (draft.status == NostrSignStatus.EXPIRED || draft.status == NostrSignStatus.FAILED) {
                throw IllegalArgumentException("Auftrag ist abgelaufen")
            }
            if (draft.status == NostrSignStatus.SIGNED) {
                call.respond(mapOf("status" to "signed"))
                return@post
            }
            val identity = database.readNostrIdentity() ?: throw IllegalArgumentException("Noch kein Nostr-Konto")
            val event = parseSigned(body.event) ?: throw IllegalArgumentException("Event ungültig")
            if (!NostrKeys.valid(event) || event.pubkey != identity.pubkey) {
                throw IllegalArgumentException("Signatur passt nicht zum Konto")
            }
            if (!NostrQueue.sameRequest(draft.kind, draft.content, draft.createdAt, draft.tags, event)) {
                throw IllegalArgumentException("Event passt nicht zum Entwurf")
            }
            if (!database.storeNostrEvent(id, body.event, now)) {
                throw IllegalArgumentException("Auftrag ist abgelaufen")
            }
            call.respond(mapOf("status" to "signed"))
        }

        post("/sign/{id}/fail") {
            guard.allowNostr(clientIp())
            call.deviceId()
            val id = call.parameters["id"]?.trim().orEmpty()
            val body = call.receive<ErrorBody>()
            val text = NostrQueue.reject(body.error)?.let { "abgelehnt" } ?: body.error.take(200)
            if (!database.failNostrSign(id, text, clock())) throw IllegalArgumentException("Auftrag ist abgelaufen")
            call.respond(mapOf("status" to "failed"))
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.deviceId(): String {
    val auth = attributes.getOrNull(NahtlosAuthKey)
    return (auth as? AuthKind.Device)?.id ?: throw IllegalArgumentException("Nur ein gekoppeltes Gerät")
}

private fun parseSigned(raw: String): de.jenz.nahtlos.wallet.SignedEvent? {
    val element = runCatching { NahtlosJson.json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return null
    return PodcastNostr.parseEvent(element)
}

private val HEX_64 = Regex("[0-9a-f]{64}")
private val tagSerializer = ListSerializer(ListSerializer(String.serializer()))
