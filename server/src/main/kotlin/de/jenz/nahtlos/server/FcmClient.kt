package de.jenz.nahtlos.server

import de.jenz.nahtlos.wallet.NostrEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.util.Base64

fun interface SignNudge {
    fun nudge()

    companion object {
        fun fromEnvironment(database: SyncDatabase): SignNudge {
            val client = FcmClient.fromEnvironment()
            log.info(if (client.enabled) "FCM an" else "FCM aus")
            return SignNudge {
                if (!client.enabled) return@SignNudge
                try {
                    val tokens = database.listPushTokens()
                    val accepted = client.wake(tokens)
                    log.info("FCM Weckruf, {} Ziele, {} angenommen", tokens.size, accepted)
                } catch (error: Exception) {
                    log.warn("FCM Weckruf fehlgeschlagen ({})", error.javaClass.simpleName)
                }
            }
        }
    }
}

/**
 * FCM HTTP v1. Die Nachricht enthält nur das Signal `what=sign`.
 * Dienstkonto-Schlüssel und Geräte-Token stehen nie im Log.
 */
class FcmClient(
    private val projectId: String,
    private val account: ServiceAccount?,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val exchange: (String, String, String, String?) -> Pair<Int, String> = ::http,
) {
    val enabled: Boolean = account != null && projectId.isNotBlank()

    private val tokenLock = Any()
    private var cachedToken: String? = null
    private var cachedUntilMs: Long = 0

    fun wake(tokens: List<String>): Int {
        if (!enabled) return 0
        val distinct = tokens.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (distinct.isEmpty()) return 0
        val bearer = accessToken()
        var accepted = 0
        for (token in distinct) {
            val code = runCatching {
                exchange(
                    "https://fcm.googleapis.com/v1/projects/$projectId/messages:send",
                    FcmMessages.wake(token),
                    "application/json",
                    bearer,
                ).first
            }.getOrDefault(-1)
            if (code in 200..299) accepted++
        }
        return accepted
    }

    private fun accessToken(): String = synchronized(tokenLock) {
        val now = clock()
        val current = cachedToken
        if (current != null && now < cachedUntilMs) return current
        val credentials = account ?: throw IllegalStateException("FCM aus")
        val assertion = FcmMessages.jwt(credentials.email, credentials.key, now / 1000)
        val body = "grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=$assertion"
        val (code, response) = exchange("https://oauth2.googleapis.com/token", body, "application/x-www-form-urlencoded", null)
        if (code !in 200..299) throw IllegalStateException("FCM-Token $code")
        val parsed = FcmMessages.readAccess(response)
        cachedToken = parsed.first
        cachedUntilMs = now + (parsed.second - 60).coerceAtLeast(30) * 1000
        parsed.first
    }

    companion object {
        fun fromEnvironment(env: (String) -> String? = System::getenv): FcmClient {
            val project = env("FCM_PROJECT_ID").orEmpty().trim()
            val path = env("FCM_CREDENTIALS_FILE").orEmpty().trim()
            if (project.isEmpty() || path.isEmpty()) return FcmClient(project, null)
            val file = File(path)
            if (!file.isFile || file.length() < 40) return FcmClient(project, null)
            val account = runCatching { ServiceAccount.parse(file.readText()) }.getOrNull()
            return FcmClient(project, account)
        }

        private fun http(url: String, body: String, contentType: String, bearer: String?): Pair<Int, String> {
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
            val builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body))
            if (bearer != null) builder.header("Authorization", "Bearer $bearer")
            val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            return response.statusCode() to response.body().take(2_000)
        }
    }
}

class ServiceAccount(val email: String, val key: PrivateKey) {
    override fun toString(): String = "fcm:${email.substringBefore('@')}"

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): ServiceAccount? {
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
            val email = obj["client_email"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val pem = obj["private_key"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (!email.contains('@') || !pem.contains("PRIVATE KEY")) return null
            val clean = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\\s".toRegex(), "")
            val spec = PKCS8EncodedKeySpec(Base64.getDecoder().decode(clean))
            val key = KeyFactory.getInstance("RSA").generatePrivate(spec)
            return ServiceAccount(email, key)
        }
    }
}

object FcmMessages {
    fun wake(token: String): String = buildString {
        append("{\"message\":{")
        append("\"token\":${NostrEvent.jsonString(token)},")
        append("\"data\":{\"what\":\"sign\"},")
        append("\"android\":{\"priority\":\"HIGH\"}}}")
    }

    fun jwt(email: String, key: PrivateKey, nowSec: Long): String {
        val header = b64("""{"alg":"RS256","typ":"JWT"}""")
        val claims = b64(
            buildString {
                append("{\"iss\":${NostrEvent.jsonString(email)}")
                append(",\"scope\":\"https://www.googleapis.com/auth/firebase.messaging\"")
                append(",\"aud\":\"https://oauth2.googleapis.com/token\"")
                append(",\"iat\":$nowSec,\"exp\":${nowSec + 3600}}")
            },
        )
        val input = "$header.$claims"
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initSign(key)
        signature.update(input.toByteArray(Charsets.UTF_8))
        return "$input.${b64(signature.sign())}"
    }

    fun readAccess(body: String): Pair<String, Long> {
        val obj = Json { ignoreUnknownKeys = true }.parseToJsonElement(body).jsonObject
        val token = obj["access_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("FCM-Token fehlt")
        val seconds = obj["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3600L
        return token to seconds
    }

    private fun b64(text: String): String = b64(text.toByteArray(Charsets.UTF_8))

    private fun b64(data: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(data)
}

private val log = LoggerFactory.getLogger("nahtlos.fcm")
