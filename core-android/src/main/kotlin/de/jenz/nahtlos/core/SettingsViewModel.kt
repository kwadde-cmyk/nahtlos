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
            message = status.lastError,
            messageIsError = status.lastError != null,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        settings.read().let { SettingsUi(deviceId = it.deviceId, bufferAheadMinutes = it.bufferAheadMinutes) },
    )

    private val _notice = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val notice: SharedFlow<String> = _notice.asSharedFlow()

    fun save(serverUrl: String, token: String, bufferAheadMinutes: Int) {
        val trimmed = serverUrl.trim()
        val urlOk = trimmed.isEmpty() || trimmed.startsWith("http://") || trimmed.startsWith("https://")
        val current = settings.read()
        settings.save(
            serverUrl = if (urlOk) trimmed else current.serverUrl,
            token = if (urlOk) token else current.token,
            bufferAheadMinutes = bufferAheadMinutes,
        )
    }

    fun test(serverUrl: String, token: String, bufferAheadMinutes: Int) {
        save(serverUrl, token, bufferAheadMinutes)
        viewModelScope.launch {
            val message = runCatching { sync.testConnection() }.getOrElse { it.message ?: "Nicht erreichbar" }
            _notice.emit(message)
        }
    }

    fun syncNow(serverUrl: String, token: String, bufferAheadMinutes: Int) {
        save(serverUrl, token, bufferAheadMinutes)
        viewModelScope.launch {
            sync.pullAndMerge(force = true)
        }
    }

    fun refresh() {
        viewModelScope.launch { sync.pullAndMerge(force = true) }
    }
}
