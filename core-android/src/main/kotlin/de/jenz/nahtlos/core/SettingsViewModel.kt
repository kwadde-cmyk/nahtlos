package de.jenz.nahtlos.core

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.jenz.nahtlos.core.sync.SettingsStore
import de.jenz.nahtlos.core.sync.SyncRepository
import de.jenz.nahtlos.playback.PlaybackCachePolicy
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
        )
    }

    fun test(
        serverUrl: String,
        token: String,
        bufferAheadMinutes: Int,
        downloadOnUnmeteredOnly: Boolean,
        downloadLimitMb: Int,
        deletePlayedAfterHours: Int,
    ) {
        save(serverUrl, token, bufferAheadMinutes, downloadOnUnmeteredOnly, downloadLimitMb, deletePlayedAfterHours)
        viewModelScope.launch {
            val message = runCatching { sync.testConnection() }.getOrElse { it.message ?: "Nicht erreichbar" }
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
    ) {
        save(serverUrl, token, bufferAheadMinutes, downloadOnUnmeteredOnly, downloadLimitMb, deletePlayedAfterHours)
        viewModelScope.launch {
            sync.pullAndMerge(force = true)
        }
    }

    fun refresh() {
        viewModelScope.launch { sync.pullAndMerge(force = true) }
    }
}
