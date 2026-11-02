package de.jenz.nahtlos.server

import de.jenz.nahtlos.model.BootstrapRequest
import de.jenz.nahtlos.model.BootstrapResponse
import de.jenz.nahtlos.model.DeviceSummary
import de.jenz.nahtlos.model.ErrorBody
import de.jenz.nahtlos.model.PairApproveRequest
import de.jenz.nahtlos.model.PairApproveResponse
import de.jenz.nahtlos.model.PairPollResponse
import de.jenz.nahtlos.model.PairStartResponse
import de.jenz.nahtlos.sync.PairingCodes
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.util.AttributeKey
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.UUID

sealed interface AuthKind {
    data object Admin : AuthKind
    data class Device(val id: String) : AuthKind
}

val NahtlosAuthKey = AttributeKey<AuthKind>("nahtlos.auth")

class RateLimitedException(message: String) : RuntimeException(message)

/**
 * In-memory windows. Codes are short, so failed approvals and anonymous starts
 * have to stop long before a search of the alphabet becomes realistic.
 */
class PairingGuard(private val clock: () -> Long) {
    private val starts = ArrayDeque<Hit>()
    private val failures = ArrayDeque<Hit>()
    private val polls = ArrayDeque<Hit>()

    fun allowStart(ip: String) {
        prune(starts)
        if (starts.count { it.ip == ip } >= MAX_STARTS) {
            throw RateLimitedException("Zu viele Kopplungsversuche. Ein paar Minuten warten.")
        }
        starts.addLast(Hit(ip, clock()))
    }

    fun allowApprove(ip: String) {
        prune(failures)
        if (failures.count { it.ip == ip } >= MAX_FAILURES) {
            throw RateLimitedException("Zu viele falsche Codes. Ein paar Minuten warten.")
        }
    }

    fun recordFailure(ip: String) {
        failures.addLast(Hit(ip, clock()))
    }

    fun allowPoll(ip: String) {
        prune(polls)
        if (polls.count { it.ip == ip } >= MAX_POLLS) {
            throw RateLimitedException("Zu viele Statusabfragen.")
        }
        polls.addLast(Hit(ip, clock()))
    }

    private fun prune(hits: ArrayDeque<Hit>) {
        val cutoff = clock() - WINDOW_MS
        while (hits.isNotEmpty() && hits.first().at < cutoff) hits.removeFirst()
    }

    private data class Hit(val ip: String, val at: Long)

    companion object {
        const val WINDOW_MS = 10 * 60_000L
        const val MAX_STARTS = 8
        const val MAX_FAILURES = 5
        const val MAX_POLLS = 240
    }
}

fun Route.deviceRoutes(database: SyncDatabase, clock: () -> Long, guard: PairingGuard, random: SecureRandom) {
    route("/pair") {
        post("/start") {
            guard.allowStart(clientIp())
            val now = clock()
            database.purgeExpiredPairings(now)
            val code = newCode(database, random, now)
            val id = UUID.randomUUID().toString()
            val expiresAt = now + PairingCodes.TTL_SECONDS * 1000L
            database.insertPairing(id, code, now, expiresAt)
            call.respond(
                PairStartResponse(
                    pairingId = id,
                    code = PairingCodes.format(code),
                    expiresIn = PairingCodes.TTL_SECONDS,
                ),
            )
        }

        post("/approve") {
            val ip = clientIp()
            guard.allowApprove(ip)
            val body = call.receive<PairApproveRequest>()
            val name = body.name.trim()
            if (name.isEmpty() || name.length > 40) throw IllegalArgumentException("Gerätename fehlt oder ist zu lang")
            if (!PairingCodes.isWellFormed(body.code)) {
                guard.recordFailure(ip)
                throw IllegalArgumentException("Code ist ungültig oder abgelaufen")
            }
            val code = PairingCodes.normalize(body.code)
            val now = clock()
            val pairing = database.findPairingByCode(code)
            if (pairing == null || pairing.expiresAt < now || pairing.deviceId != null) {
                guard.recordFailure(ip)
                throw IllegalArgumentException("Code ist ungültig oder abgelaufen")
            }
            val deviceId = UUID.randomUUID().toString()
            val token = newDeviceToken(random)
            database.insertDevice(deviceId, name, token, now)
            val kept = database.approvePairing(
                pairingId = pairing.pairingId,
                deviceId = deviceId,
                name = name,
                token = token,
                expiresAt = maxOf(pairing.expiresAt, now + 60_000L),
            )
            if (!kept) {
                database.revokeDevice(deviceId)
                guard.recordFailure(ip)
                throw IllegalArgumentException("Code ist ungültig oder abgelaufen")
            }
            call.respond(PairApproveResponse(deviceId = deviceId, name = name))
        }

        get("/poll") {
            guard.allowPoll(clientIp())
            val id = call.request.queryParameters["pairingId"]?.trim().orEmpty()
            if (id.isEmpty()) throw IllegalArgumentException("pairingId fehlt")
            val now = clock()
            val row = database.consumePairingToken(id)
            if (row == null) {
                call.respond(HttpStatusCode.NotFound, ErrorBody("Kopplung unbekannt"))
                return@get
            }
            val response = when {
                row.deviceToken != null && row.expiresAt >= now -> PairPollResponse(
                    status = PairPollResponse.APPROVED,
                    token = row.deviceToken,
                    deviceId = row.deviceId,
                    name = row.deviceName,
                )
                row.consumed != 0 -> PairPollResponse(status = PairPollResponse.CONSUMED)
                row.expiresAt < now -> PairPollResponse(status = PairPollResponse.EXPIRED)
                else -> PairPollResponse(
                    status = PairPollResponse.PENDING,
                    expiresIn = ((row.expiresAt - now) / 1000L).toInt().coerceAtLeast(0),
                )
            }
            call.respond(response)
        }
    }

    get("/devices") {
        call.respond(database.listDevices().map { it.toSummary() })
    }

    post("/devices/bootstrap") {
        val auth = call.attributes.getOrNull(NahtlosAuthKey)
        if (auth != AuthKind.Admin) {
            call.respond(HttpStatusCode.Forbidden, ErrorBody("Nur das Admin-Token legt das erste Gerät an"))
            return@post
        }
        val body = call.receive<BootstrapRequest>()
        val name = body.name.trim()
        if (name.isEmpty() || name.length > 40) throw IllegalArgumentException("Gerätename fehlt oder ist zu lang")
        val id = UUID.randomUUID().toString()
        val token = newDeviceToken(random)
        database.insertDevice(id, name, token, clock())
        call.respond(BootstrapResponse(id = id, name = name, token = token))
    }

    delete("/devices/{id}") {
        val id = call.parameters["id"]?.trim().orEmpty()
        if (id.isEmpty()) throw IllegalArgumentException("Geräte-Id fehlt")
        if (!database.revokeDevice(id)) {
            call.respond(HttpStatusCode.NotFound, ErrorBody("Gerät unbekannt"))
            return@delete
        }
        call.respond(HttpStatusCode.NoContent)
    }
}

private fun DeviceRow.toSummary() = DeviceSummary(
    id = id,
    name = name,
    createdAt = createdAt,
    lastSeenAt = lastSeenAt,
    revoked = revoked,
)

private fun io.ktor.server.routing.RoutingContext.clientIp(): String =
    call.request.local.remoteHost.ifBlank { "unknown" }

private fun newCode(database: SyncDatabase, random: SecureRandom, now: Long): String {
    repeat(8) {
        val raw = CharArray(6) { PairingCodes.ALPHABET[random.nextInt(PairingCodes.ALPHABET.length)] }.concatToString()
        if (!database.pairingCodeInUse(raw, now)) return raw
    }
    throw IllegalStateException("Kein freier Kopplungscode")
}

private fun newDeviceToken(random: SecureRandom): String {
    val bytes = ByteArray(32)
    random.nextBytes(bytes)
    return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
