package de.jenz.nahtlos.core.sync

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import de.jenz.nahtlos.feed.EpisodeKeys
import de.jenz.nahtlos.playback.PlaybackCachePolicy
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
    val bufferAheadMinutes: Int = PlaybackCachePolicy.DEFAULT_AHEAD_MINUTES,
    val downloadOnUnmeteredOnly: Boolean = true,
    val downloadLimitMb: Int = 2048,
    val deletePlayedAfterHours: Int = 24,
) {
    val configured: Boolean
        get() = (serverUrl.startsWith("http://") || serverUrl.startsWith("https://")) && token.isNotBlank()
}

@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("nahtlos", Context.MODE_PRIVATE)

    fun read(): ServerSettings {
        val deviceId = prefs.getString(KEY_DEVICE, null) ?: UUID.randomUUID().toString().also { id ->
            prefs.edit().putString(KEY_DEVICE, id).apply()
        }
        return ServerSettings(
            serverUrl = prefs.getString(KEY_URL, "").orEmpty(),
            token = prefs.getString(KEY_TOKEN, "").orEmpty(),
            deviceId = deviceId,
            bufferAheadMinutes = PlaybackCachePolicy.clampMinutes(
                prefs.getInt(KEY_BUFFER, PlaybackCachePolicy.DEFAULT_AHEAD_MINUTES),
            ),
            downloadOnUnmeteredOnly = prefs.getBoolean(KEY_UNMETERED, true),
            downloadLimitMb = prefs.getInt(KEY_LIMIT, 2048).coerceIn(128, 100_000),
            deletePlayedAfterHours = prefs.getInt(KEY_DELETE_HOURS, 24).coerceIn(0, 24 * 30),
        )
    }

    fun save(
        serverUrl: String,
        token: String,
        bufferAheadMinutes: Int,
        downloadOnUnmeteredOnly: Boolean = read().downloadOnUnmeteredOnly,
        downloadLimitMb: Int = read().downloadLimitMb,
        deletePlayedAfterHours: Int = read().deletePlayedAfterHours,
    ) {
        val normalized = serverUrl.trim().trimEnd('/').let { url ->
            if (url.isEmpty()) "" else EpisodeKeys.normalizeFeedUrl(url)
        }
        prefs.edit()
            .putString(KEY_URL, normalized)
            .putString(KEY_TOKEN, token.trim())
            .putInt(KEY_BUFFER, PlaybackCachePolicy.clampMinutes(bufferAheadMinutes))
            .putBoolean(KEY_UNMETERED, downloadOnUnmeteredOnly)
            .putInt(KEY_LIMIT, downloadLimitMb.coerceIn(128, 100_000))
            .putInt(KEY_DELETE_HOURS, deletePlayedAfterHours.coerceIn(0, 24 * 30))
            .apply()
    }

    fun serverDeviceId(): String = prefs.getString(KEY_SERVER_DEVICE, "").orEmpty()

    fun saveServerDeviceId(id: String) {
        prefs.edit().putString(KEY_SERVER_DEVICE, id).apply()
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
        private const val KEY_SERVER_DEVICE = "serverDeviceId"
        private const val KEY_BUFFER = "bufferAheadMinutes"
        private const val KEY_UNMETERED = "downloadOnUnmeteredOnly"
        private const val KEY_LIMIT = "downloadLimitMb"
        private const val KEY_DELETE_HOURS = "deletePlayedAfterHours"
    }
}
