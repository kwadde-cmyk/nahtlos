package de.jenz.nahtlos.core.sync

import de.jenz.nahtlos.core.net.Net
import de.jenz.nahtlos.model.BootstrapRequest
import de.jenz.nahtlos.model.BootstrapResponse
import de.jenz.nahtlos.model.DeviceSummary
import de.jenz.nahtlos.model.EpisodeProgress
import de.jenz.nahtlos.model.ErrorBody
import de.jenz.nahtlos.model.PairApproveRequest
import de.jenz.nahtlos.model.PairApproveResponse
import de.jenz.nahtlos.model.PairPollResponse
import de.jenz.nahtlos.model.PairStartResponse
import de.jenz.nahtlos.model.ProgressBatchRequest
import de.jenz.nahtlos.model.ProgressBatchResponse
import de.jenz.nahtlos.model.ProgressWriteResponse
import de.jenz.nahtlos.model.Subscription
import de.jenz.nahtlos.model.WalletCipherRequest
import de.jenz.nahtlos.model.WalletOpenRequest
import de.jenz.nahtlos.model.WalletOpenResponse
import de.jenz.nahtlos.model.WalletPollResponse
import de.jenz.nahtlos.diagnose.DiagnoseNote
import de.jenz.nahtlos.diagnose.DiagnoseUpload
import de.jenz.nahtlos.sync.BoostBatchRequest
import de.jenz.nahtlos.sync.BoostSyncItem
import de.jenz.nahtlos.model.NahtlosJson
import de.jenz.nahtlos.playlist.ListeningPlaylist
import de.jenz.nahtlos.playlist.PlaylistWriteResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

class SyncHttpException(val code: Int, message: String) : IOException(message)

@kotlinx.serialization.Serializable
private data class OpmlUpload(val opml: String)

@Singleton
class SyncClient @Inject constructor(
    private val net: Net,
) {
    private val json = NahtlosJson.json

    suspend fun health(settings: ServerSettings): Boolean {
        val result = net.exchange(url(settings, "/health"), "GET", null, emptyMap())
        if (result.code !in 200..299) throw SyncHttpException(result.code, "Server antwortet mit ${result.code}")
        return true
    }

    suspend fun getSubscriptions(settings: ServerSettings): List<Subscription> {
        val result = authed(settings, "/v1/subscriptions", "GET", null)
        return json.decodeFromString(result)
    }

    suspend fun putSubscription(settings: ServerSettings, subscription: Subscription): Subscription {
        val result = authed(settings, "/v1/subscriptions", "PUT", json.encodeToString(subscription))
        return json.decodeFromString(result)
    }

    suspend fun deleteSubscription(settings: ServerSettings, feedUrl: String) {
        val encoded = URLEncoder.encode(feedUrl, Charsets.UTF_8.name())
        authed(settings, "/v1/subscriptions?feedUrl=$encoded", "DELETE", null)
    }

    suspend fun getProgress(settings: ServerSettings, episodeKey: String): EpisodeProgress? {
        val encoded = URLEncoder.encode(episodeKey, Charsets.UTF_8.name())
        return try {
            val result = authed(settings, "/v1/progress?episodeKey=$encoded", "GET", null)
            json.decodeFromString(result)
        } catch (e: SyncHttpException) {
            if (e.code == 404) null else throw e
        }
    }

    suspend fun getAllProgress(settings: ServerSettings): List<EpisodeProgress> {
        val result = authed(settings, "/v1/progress", "GET", null)
        return json.decodeFromString(result)
    }

    suspend fun putProgressBatch(settings: ServerSettings, items: List<EpisodeProgress>): List<ProgressWriteResponse> {
        if (items.isEmpty()) return emptyList()
        val payload = json.encodeToString(ProgressBatchRequest(items))
        val result = authed(settings, "/v1/progress/batch", "PUT", payload)
        return json.decodeFromString<ProgressBatchResponse>(result).results
    }

    /**
     * Null when this server has no playlist route or the body is not a playlist.
     * Callers keep the local document in that case.
     */
    suspend fun getPlaylist(settings: ServerSettings): ListeningPlaylist? {
        return try {
            json.decodeFromString(authed(settings, "/v1/playlist", "GET", null))
        } catch (error: SyncHttpException) {
            if (error.code == 404 || error.code == 405 || error.code == 501) null else throw error
        } catch (error: SerializationException) {
            null
        }
    }

    suspend fun putPlaylist(settings: ServerSettings, playlist: ListeningPlaylist): PlaylistWriteResponse {
        val result = authed(settings, "/v1/playlist", "PUT", json.encodeToString(playlist))
        return try {
            json.decodeFromString(result)
        } catch (error: SerializationException) {
            throw SyncHttpException(200, "Playlist-Antwort ist kein bekanntes Dokument")
        }
    }

    suspend fun pairStart(serverUrl: String): PairStartResponse {
        val result = net.exchange(endpoint(serverUrl, "/v1/pair/start"), "POST", "{}", jsonHeaders(auth = null))
        if (result.code !in 200..299) throw httpFailure(result.code, result.body)
        return json.decodeFromString(result.body)
    }

    suspend fun pairPoll(serverUrl: String, pairingId: String): PairPollResponse {
        val id = URLEncoder.encode(pairingId, Charsets.UTF_8.name())
        val result = net.exchange(endpoint(serverUrl, "/v1/pair/poll?pairingId=$id"), "GET", null, jsonHeaders(auth = null))
        if (result.code !in 200..299) throw httpFailure(result.code, result.body)
        return json.decodeFromString(result.body)
    }

    suspend fun pairApprove(
        settings: ServerSettings,
        code: String,
        name: String,
        serverUrl: String = settings.serverUrl,
    ): PairApproveResponse {
        val body = json.encodeToString(PairApproveRequest(code = code, name = name))
        return json.decodeFromString(authed(settings.copy(serverUrl = serverUrl), "/v1/pair/approve", "POST", body))
    }

    suspend fun listDevices(settings: ServerSettings): List<DeviceSummary> =
        json.decodeFromString(authed(settings, "/v1/devices", "GET", null))

    suspend fun revokeDevice(settings: ServerSettings, id: String) {
        authed(settings, "/v1/devices/${URLEncoder.encode(id, Charsets.UTF_8.name())}", "DELETE", null)
    }

    suspend fun bootstrap(settings: ServerSettings, name: String): BootstrapResponse {
        val body = json.encodeToString(BootstrapRequest(name))
        return json.decodeFromString(authed(settings, "/v1/devices/bootstrap", "POST", body))
    }

    suspend fun putOpml(settings: ServerSettings, opml: String): List<Subscription> {
        val result = authed(settings, "/v1/subscriptions/opml", "PUT", json.encodeToString(OpmlUpload(opml)))
        return json.decodeFromString(result)
    }

    suspend fun openWalletRelay(settings: ServerSettings, sessionId: String): WalletOpenResponse =
        json.decodeFromString(authed(settings, "/v1/wallet-relay/open", "POST", json.encodeToString(WalletOpenRequest(sessionId))))

    suspend fun putWalletCipher(settings: ServerSettings, sessionId: String, ciphertext: String) {
        val id = URLEncoder.encode(sessionId, Charsets.UTF_8.name())
        authed(settings, "/v1/wallet-relay/$id/cipher", "POST", json.encodeToString(WalletCipherRequest(ciphertext)))
    }

    suspend fun putDiagnose(settings: ServerSettings, upload: DiagnoseUpload) {
        authed(settings, "/v1/diagnose", "PUT", json.encodeToString(upload))
    }

    suspend fun getDiagnose(settings: ServerSettings): List<DiagnoseNote> =
        json.decodeFromString(authed(settings, "/v1/diagnose", "GET", null))

    suspend fun putBoosts(settings: ServerSettings, items: List<BoostSyncItem>): List<BoostSyncItem> {
        val result = authed(settings, "/v1/boosts", "PUT", json.encodeToString(BoostBatchRequest(items.take(80))))
        return json.decodeFromString(result)
    }

    suspend fun getBoosts(settings: ServerSettings, since: Long?): List<BoostSyncItem> {
        val path = if (since == null) "/v1/boosts" else "/v1/boosts?since=$since"
        return json.decodeFromString(authed(settings, path, "GET", null))
    }

    suspend fun pollWalletRelay(settings: ServerSettings, sessionId: String): WalletPollResponse {
        val id = URLEncoder.encode(sessionId, Charsets.UTF_8.name())
        return json.decodeFromString(authed(settings, "/v1/wallet-relay/$id", "GET", null))
    }

    private suspend fun authed(settings: ServerSettings, path: String, method: String, body: String?): String {
        val result = net.exchange(
            url = url(settings, path),
            method = method,
            body = body,
            headers = mapOf(
                "Authorization" to "Bearer ${settings.token}",
                "Accept" to "application/json",
                "Content-Type" to "application/json",
            ),
        )
        if (result.code !in 200..299) throw httpFailure(result.code, result.body)
        return result.body
    }

    private fun jsonHeaders(auth: String?): Map<String, String> = buildMap {
        if (auth != null) put("Authorization", "Bearer $auth")
        put("Accept", "application/json")
        put("Content-Type", "application/json")
    }

    private fun httpFailure(code: Int, body: String): SyncHttpException {
        val message = runCatching { json.decodeFromString(ErrorBody.serializer(), body).error }.getOrNull()
        val fallback = if (code == 401) "Token abgelehnt" else "Sync-Server antwortet mit $code"
        return SyncHttpException(code, message ?: fallback)
    }

    private fun endpoint(serverUrl: String, path: String): String {
        val base = serverUrl.trim().trimEnd('/')
        return base + if (path.startsWith("/")) path else "/$path"
    }

    private fun url(settings: ServerSettings, path: String): String = endpoint(settings.serverUrl, path)
}
