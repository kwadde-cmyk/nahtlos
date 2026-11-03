package de.jenz.nahtlos.core

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.jenz.nahtlos.core.sync.SettingsStore
import de.jenz.nahtlos.core.sync.SyncRepository
import de.jenz.nahtlos.feed.EpisodeHorizon
import de.jenz.nahtlos.playback.PlaybackCachePolicy
import de.jenz.nahtlos.playback.PlaybackTuning
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUi(
    val serverUrl: String = "",
    val token: String = "",
    val deviceId: String = "",
    val configured: Boolean = false,
    val pending: Int = 0,
    val syncing: Boolean = false,
    val lastSyncAt: Long? = null,
    val bufferAheadMinutes: Int = PlaybackCachePolicy.DEFAULT_AHEAD_MINUTES,
    val downloadOnUnmeteredOnly: Boolean = true,
    val downloadLimitMb: Int = 2048,
    val deletePlayedAfterHours: Int = 24,
    val episodeHorizonId: String = EpisodeHorizon.DEFAULT.id,
    val playbackSpeed: Float = PlaybackTuning.DEFAULT_SPEED,
    val skipBackSec: Int = PlaybackTuning.DEFAULT_SKIP_BACK_SEC,
    val skipForwardSec: Int = PlaybackTuning.DEFAULT_SKIP_FORWARD_SEC,
    val message: String? = null,
    val messageIsError: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsStore,
    private val sync: SyncRepository,
) : ViewModel() {
    val state: StateFlow<SettingsUi> = combine(settings.observe(), sync.status) { stored, status ->
        SettingsUi(
            serverUrl = stored.serverUrl,
            token = stored.token,
            deviceId = stored.deviceId,
            configured = stored.configured,
            pending = status.pending,
            syncing = status.syncing,
            lastSyncAt = status.lastSyncAt,
            bufferAheadMinutes = stored.bufferAheadMinutes,
            downloadOnUnmeteredOnly = stored.downloadOnUnmeteredOnly,
            downloadLimitMb = stored.downloadLimitMb,
            deletePlayedAfterHours = stored.deletePlayedAfterHours,
            episodeHorizonId = stored.episodeHorizonId,
            playbackSpeed = stored.playbackSpeed,
            skipBackSec = stored.skipBackSec,
            skipForwardSec = stored.skipForwardSec,
            message = status.lastError,
            messageIsError = status.lastError != null,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        settings.read().let {
            SettingsUi(
                deviceId = it.deviceId,
                bufferAheadMinutes = it.bufferAheadMinutes,
                downloadOnUnmeteredOnly = it.downloadOnUnmeteredOnly,
                downloadLimitMb = it.downloadLimitMb,
                deletePlayedAfterHours = it.deletePlayedAfterHours,
                episodeHorizonId = it.episodeHorizonId,
                playbackSpeed = it.playbackSpeed,
                skipBackSec = it.skipBackSec,
                skipForwardSec = it.skipForwardSec,
            )
        },
    )

    private val _notice = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val notice: SharedFlow<String> = _notice.asSharedFlow()

    fun save(
        serverUrl: String,
        token: String,
        bufferAheadMinutes: Int,
        downloadOnUnmeteredOnly: Boolean,
        downloadLimitMb: Int,
        deletePlayedAfterHours: Int,
        episodeHorizonId: String = settings.read().episodeHorizonId,
    ) {
        val trimmed = serverUrl.trim()
        val urlOk = trimmed.isEmpty() || trimmed.startsWith("http://") || trimmed.startsWith("https://")
        val current = settings.read()
        settings.save(
            serverUrl = if (urlOk) trimmed else current.serverUrl,
            token = if (urlOk) token else current.token,
            bufferAheadMinutes = bufferAheadMinutes,
            downloadOnUnmeteredOnly = downloadOnUnmeteredOnly,
            downloadLimitMb = downloadLimitMb,
            deletePlayedAfterHours = deletePlayedAfterHours,
            episodeHorizonId = episodeHorizonId,
        )
    }

    fun nudgeSpeed(direction: Int) {
        val current = settings.read()
        savePlayback(
            current.copy(playbackSpeed = PlaybackTuning.nudgeSpeed(current.playbackSpeed, direction)),
        )
    }

    fun nudgeSkip(back: Boolean, delta: Int) {
        val current = settings.read()
        savePlayback(
            if (back) current.copy(skipBackSec = PlaybackTuning.clampSkipSeconds(current.skipBackSec + delta))
            else current.copy(skipForwardSec = PlaybackTuning.clampSkipSeconds(current.skipForwardSec + delta)),
        )
    }

    private fun savePlayback(current: de.jenz.nahtlos.core.sync.ServerSettings) {
        settings.save(
            serverUrl = current.serverUrl,
            token = current.token,
            bufferAheadMinutes = current.bufferAheadMinutes,
            downloadOnUnmeteredOnly = current.downloadOnUnmeteredOnly,
            downloadLimitMb = current.downloadLimitMb,
            deletePlayedAfterHours = current.deletePlayedAfterHours,
            episodeHorizonId = current.episodeHorizonId,
            playbackSpeed = current.playbackSpeed,
            skipBackSec = current.skipBackSec,
            skipForwardSec = current.skipForwardSec,
            skipSilence = current.skipSilence,
            volumeBoost = current.volumeBoost,
        )
    }

    fun setEpisodeHorizon(id: String) {
        val current = settings.read()
        settings.save(
            serverUrl = current.serverUrl,
            token = current.token,
            bufferAheadMinutes = current.bufferAheadMinutes,
            downloadOnUnmeteredOnly = current.downloadOnUnmeteredOnly,
            downloadLimitMb = current.downloadLimitMb,
            deletePlayedAfterHours = current.deletePlayedAfterHours,
            episodeHorizonId = EpisodeHorizon.fromId(id).id,
        )
    }

    fun test(
        serverUrl: String,
        token: String,
        bufferAheadMinutes: Int,
        downloadOnUnmeteredOnly: Boolean,
        downloadLimitMb: Int,
        deletePlayedAfterHours: Int,
        enrollName: String = "Handy",
    ) {
        save(serverUrl, token, bufferAheadMinutes, downloadOnUnmeteredOnly, downloadLimitMb, deletePlayedAfterHours)
        viewModelScope.launch {
            val message = try {
                val enrolled = sync.enrollOwnDevice(enrollName)
                val health = sync.testConnection()
                enrolled?.let { "$it $health" } ?: health
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                error.message ?: "Nicht erreichbar"
            }
            _notice.emit(message)
        }
    }

    fun syncNow(
        serverUrl: String,
        token: String,
        bufferAheadMinutes: Int,
        downloadOnUnmeteredOnly: Boolean,
        downloadLimitMb: Int,
        deletePlayedAfterHours: Int,
        enrollName: String = "Handy",
    ) {
        save(serverUrl, token, bufferAheadMinutes, downloadOnUnmeteredOnly, downloadLimitMb, deletePlayedAfterHours)
        viewModelScope.launch {
            try {
                val enrolled = sync.enrollOwnDevice(enrollName)
                if (enrolled != null) _notice.emit(enrolled)
                sync.pullAndMerge(force = true)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _notice.emit(error.message ?: "Sync fehlgeschlagen")
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { sync.pullAndMerge(force = true) }
    }
}
