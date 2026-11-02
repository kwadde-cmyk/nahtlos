package de.jenz.weiterhoeren.core.sync

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import de.jenz.weiterhoeren.feed.EpisodeKeys
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class ServerSettings(
    val serverUrl: String,
    val token: String,
    val deviceId: String,
) {
    val configured: Boolean
        get() = (serverUrl.startsWith("http://") || serverUrl.startsWith("https://")) && token.isNotBlank()
}

@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("weiterhoeren", Context.MODE_PRIVATE)

    fun read(): ServerSettings {
        val deviceId = prefs.getString(KEY_DEVICE, null) ?: UUID.randomUUID().toString().also { id ->
            prefs.edit().putString(KEY_DEVICE, id).apply()
        }
        return ServerSettings(
            serverUrl = prefs.getString(KEY_URL, "").orEmpty(),
            token = prefs.getString(KEY_TOKEN, "").orEmpty(),
            deviceId = deviceId,
        )
    }

    fun save(serverUrl: String, token: String) {
        val normalized = serverUrl.trim().trimEnd('/').let { url ->
            if (url.isEmpty()) "" else EpisodeKeys.normalizeFeedUrl(url)
        }
        prefs.edit()
            .putString(KEY_URL, normalized)
            .putString(KEY_TOKEN, token.trim())
            .apply()
    }

    fun observe(): Flow<ServerSettings> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(read()) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(read())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    companion object {
        private const val KEY_URL = "serverUrl"
        private const val KEY_TOKEN = "token"
        private const val KEY_DEVICE = "deviceId"
    }
}
