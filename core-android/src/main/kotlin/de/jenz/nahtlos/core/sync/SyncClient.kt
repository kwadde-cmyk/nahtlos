package de.jenz.nahtlos.core.sync

import de.jenz.nahtlos.core.net.Net
import de.jenz.nahtlos.model.EpisodeProgress
import de.jenz.nahtlos.model.ProgressBatchRequest
import de.jenz.nahtlos.model.ProgressBatchResponse
import de.jenz.nahtlos.model.ProgressWriteResponse
import de.jenz.nahtlos.model.Subscription
import de.jenz.nahtlos.model.NahtlosJson
import kotlinx.serialization.encodeToString
import java.io.IOException
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

class SyncHttpException(val code: Int, message: String) : IOException(message)

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
        if (result.code == 401) throw SyncHttpException(401, "Token abgelehnt")
        if (result.code !in 200..299) {
            throw SyncHttpException(result.code, "Sync-Server antwortet mit ${result.code}")
        }
        return result.body
    }

    private fun url(settings: ServerSettings, path: String): String {
        val base = settings.serverUrl.trimEnd('/')
        return base + if (path.startsWith("/")) path else "/$path"
    }
}
