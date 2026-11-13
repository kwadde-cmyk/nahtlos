package de.jenz.nahtlos.server

import de.jenz.nahtlos.model.BootstrapRequest
import de.jenz.nahtlos.model.BootstrapResponse
import de.jenz.nahtlos.model.DeviceSummary
import de.jenz.nahtlos.model.ErrorBody
import de.jenz.nahtlos.model.PairApproveRequest
import de.jenz.nahtlos.model.PairApproveResponse
import de.jenz.nahtlos.model.PairPollResponse
import de.jenz.nahtlos.model.PairStartResponse
import de.jenz.nahtlos.model.WalletCipherRequest
import de.jenz.nahtlos.model.WalletOpenRequest
import de.jenz.nahtlos.model.WalletOpenResponse
import de.jenz.nahtlos.model.WalletPollResponse
import de.jenz.nahtlos.sync.PairingCodes
import de.jenz.nahtlos.wallet.WalletRelay
import java.util.Base64
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
    private val wallet = ArrayDeque<Hit>()
    private val nostr = ArrayDeque<Hit>()

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

    fun allowNostr(ip: String) {
        prune(nostr)
        if (nostr.count { it.ip == ip } >= MAX_NOSTR) {
            throw RateLimitedException("Zu viele Nostr-Anfragen. Ein paar Minuten warten.")
        }
        nostr.addLast(Hit(ip, clock()))
    }

    fun allowWallet(ip: String) {
        prune(wallet)
        if (wallet.count { it.ip == ip } >= MAX_WALLET) {
            throw RateLimitedException("Zu viele Wallet-Sendungen. Ein paar Minuten warten.")
        }
        wallet.addLast(Hit(ip, clock()))
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
        const val MAX_WALLET = 400
        const val MAX_NOSTR = 600
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

fun Route.walletRelayRoutes(database: SyncDatabase, clock: () -> Long, guard: PairingGuard) {
    route("/wallet-relay") {
        post("/open") {
            guard.allowWallet(clientIp())
            val auth = call.attributes.getOrNull(NahtlosAuthKey)
            val device = auth as? AuthKind.Device
                ?: throw IllegalArgumentException("Nur ein gekoppeltes Gerät kann eine Sendung öffnen")
            val body = call.receive<WalletOpenRequest>()
            val sessionId = body.sessionId.trim()
            if (!SESSION_ID.matches(sessionId)) throw IllegalArgumentException("Sitzung ungültig")
            val now = clock()
            database.purgeWalletRelay(now)
            try {
                database.openWalletRelay(sessionId, device.id, now, now + WalletRelay.TTL_SECONDS * 1000L)
            } catch (_: Exception) {
                throw IllegalArgumentException("Diese Sitzung gibt es schon")
            }
            call.respond(WalletOpenResponse(sessionId = sessionId, expiresIn = WalletRelay.TTL_SECONDS))
        }

        post("/{id}/cipher") {
            guard.allowWallet(clientIp())
            val auth = call.attributes.getOrNull(NahtlosAuthKey)
            val sender = when (auth) {
                is AuthKind.Device -> auth.id
                AuthKind.Admin -> null
                else -> throw IllegalArgumentException("Token fehlt oder ist falsch")
            }
            val sessionId = call.parameters["id"]?.trim().orEmpty()
            if (!SESSION_ID.matches(sessionId)) throw IllegalArgumentException("Sitzung ungültig")
            val body = call.receive<WalletCipherRequest>()
            val cipher = body.ciphertext.trim()
            if (!acceptableCipher(cipher)) throw IllegalArgumentException("Sendung abgelehnt")
            val stored = database.storeWalletCipher(sessionId, sender, cipher, clock())
            if (!stored) throw IllegalArgumentException("Sendung abgelehnt oder abgelaufen")
            call.respond(mapOf("status" to "stored"))
        }

        get("/{id}") {
            guard.allowWallet(clientIp())
            val auth = call.attributes.getOrNull(NahtlosAuthKey)
            val device = auth as? AuthKind.Device
            if (device == null) {
                call.respond(HttpStatusCode.Forbidden, ErrorBody("Nur das Auto kann die Sendung abholen"))
                return@get
            }
            val sessionId = call.parameters["id"]?.trim().orEmpty()
            val now = clock()
            val taken = database.takeWalletCipher(sessionId, device.id, now)
            if (taken.status == WalletTake.FORBIDDEN) {
                call.respond(HttpStatusCode.Forbidden, ErrorBody("Nur das Auto kann die Sendung abholen"))
                return@get
            }
            val expiresIn = if (taken.expiresAt > now) ((taken.expiresAt - now) / 1000L).toInt() else 0
            call.respond(
                WalletPollResponse(
                    status = when (taken.status) {
                        WalletTake.READY -> WalletPollResponse.READY
                        WalletTake.PENDING -> WalletPollResponse.PENDING
                        WalletTake.EXPIRED -> WalletPollResponse.EXPIRED
                        else -> WalletPollResponse.GONE
                    },
                    expiresIn = expiresIn,
                    ciphertext = taken.ciphertext,
                    senderName = taken.senderDeviceId?.let { database.deviceName(it) },
                ),
            )
        }
    }
}

private val SESSION_ID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

private fun acceptableCipher(cipher: String): Boolean {
    if (cipher.length !in 40..4000) return false
    if (cipher.contains("nostr+walletconnect") || cipher.contains("secret=")) return false
    if (!cipher.matches(BASE64)) return false
    val decoded = try {
        Base64.getDecoder().decode(cipher)
    } catch (_: IllegalArgumentException) {
        return false
    }
    if (decoded.size !in 48..3000) return false
    return !String(decoded, Charsets.ISO_8859_1).contains("nostr+walletconnect")
}

private val BASE64 = Regex("^[A-Za-z0-9+/]+=*$")

private fun DeviceRow.toSummary() = DeviceSummary(
    id = id,
    name = name,
    createdAt = createdAt,
    lastSeenAt = lastSeenAt,
    revoked = revoked,
)

internal fun io.ktor.server.routing.RoutingContext.clientIp(): String =
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
