package de.jenz.nahtlos.core.sync

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import de.jenz.nahtlos.feed.EpisodeHorizon
import de.jenz.nahtlos.feed.EpisodeKeys
import de.jenz.nahtlos.playback.PlaybackCachePolicy
import de.jenz.nahtlos.playback.PlaybackTuning
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
    val episodeHorizonId: String = EpisodeHorizon.DEFAULT.id,
    val playbackSpeed: Float = PlaybackTuning.DEFAULT_SPEED,
    val skipBackSec: Int = PlaybackTuning.DEFAULT_SKIP_BACK_SEC,
    val skipForwardSec: Int = PlaybackTuning.DEFAULT_SKIP_FORWARD_SEC,
    val skipSilence: Boolean = false,
    val volumeBoost: Boolean = false,
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
            episodeHorizonId = EpisodeHorizon.fromId(prefs.getString(KEY_HORIZON, null)).id,
            playbackSpeed = PlaybackTuning.clampSpeed(prefs.getFloat(KEY_SPEED, PlaybackTuning.DEFAULT_SPEED)),
            skipBackSec = PlaybackTuning.clampSkipSeconds(prefs.getInt(KEY_SKIP_BACK, PlaybackTuning.DEFAULT_SKIP_BACK_SEC)),
            skipForwardSec = PlaybackTuning.clampSkipSeconds(prefs.getInt(KEY_SKIP_FORWARD, PlaybackTuning.DEFAULT_SKIP_FORWARD_SEC)),
            skipSilence = prefs.getBoolean(KEY_SILENCE, false),
            volumeBoost = prefs.getBoolean(KEY_BOOST, false),
        )
    }

    fun save(
        serverUrl: String,
        token: String,
        bufferAheadMinutes: Int,
        downloadOnUnmeteredOnly: Boolean = read().downloadOnUnmeteredOnly,
        downloadLimitMb: Int = read().downloadLimitMb,
        deletePlayedAfterHours: Int = read().deletePlayedAfterHours,
        episodeHorizonId: String = read().episodeHorizonId,
        playbackSpeed: Float = read().playbackSpeed,
        skipBackSec: Int = read().skipBackSec,
        skipForwardSec: Int = read().skipForwardSec,
        skipSilence: Boolean = read().skipSilence,
        volumeBoost: Boolean = read().volumeBoost,
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
            .putString(KEY_HORIZON, EpisodeHorizon.fromId(episodeHorizonId).id)
            .putFloat(KEY_SPEED, PlaybackTuning.clampSpeed(playbackSpeed))
            .putInt(KEY_SKIP_BACK, PlaybackTuning.clampSkipSeconds(skipBackSec))
            .putInt(KEY_SKIP_FORWARD, PlaybackTuning.clampSkipSeconds(skipForwardSec))
            .putBoolean(KEY_SILENCE, skipSilence)
            .putBoolean(KEY_BOOST, volumeBoost)
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
        private const val KEY_HORIZON = "episodeHorizonId"
        private const val KEY_SPEED = "playbackSpeed"
        private const val KEY_SKIP_BACK = "skipBackSec"
        private const val KEY_SKIP_FORWARD = "skipForwardSec"
        private const val KEY_SILENCE = "skipSilence"
        private const val KEY_BOOST = "volumeBoost"
    }
}
