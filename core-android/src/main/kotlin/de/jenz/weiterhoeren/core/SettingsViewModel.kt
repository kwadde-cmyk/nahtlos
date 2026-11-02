package de.jenz.weiterhoeren.core

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.jenz.weiterhoeren.core.sync.SettingsStore
import de.jenz.weiterhoeren.core.sync.SyncRepository
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
            message = status.lastError,
            messageIsError = status.lastError != null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUi(deviceId = settings.read().deviceId))

    private val _notice = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val notice: SharedFlow<String> = _notice.asSharedFlow()

    fun save(serverUrl: String, token: String) {
        val trimmed = serverUrl.trim()
        if (trimmed.isNotEmpty() && !trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return
        }
        settings.save(trimmed, token)
    }

    fun test(serverUrl: String, token: String) {
        save(serverUrl, token)
        viewModelScope.launch {
            val message = runCatching { sync.testConnection() }.getOrElse { it.message ?: "Nicht erreichbar" }
            _notice.emit(message)
        }
    }

    fun syncNow(serverUrl: String, token: String) {
        save(serverUrl, token)
        viewModelScope.launch {
            sync.pullAndMerge(force = true)
        }
    }

    fun refresh() {
        viewModelScope.launch { sync.pullAndMerge(force = true) }
    }
}
