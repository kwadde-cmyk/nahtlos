package de.jenz.nahtlos.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.jenz.nahtlos.core.sync.SyncClient
import de.jenz.nahtlos.core.sync.SyncRepository
import de.jenz.nahtlos.model.DeviceSummary
import de.jenz.nahtlos.sync.PairingCodes
import de.jenz.nahtlos.sync.PairingLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PairUi(
    val configured: Boolean = false,
    val serverUrl: String = "",
    val selfId: String = "",
    val devices: List<DeviceSummary> = emptyList(),
    val message: String? = null,
    val busy: Boolean = false,
)

@HiltViewModel
class PairViewModel @Inject constructor(
    private val sync: SyncRepository,
    private val api: SyncClient,
) : ViewModel() {
    private val _ui = MutableStateFlow(PairUi())
    val ui: StateFlow<PairUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch { loadDevices(keepMessage = null) }
    }

    fun refresh() {
        viewModelScope.launch { loadDevices(keepMessage = _ui.value.message) }
    }

    private suspend fun loadDevices(keepMessage: String?) {
        val current = sync.currentSettings()
        if (!current.configured) {
            _ui.value = PairUi(configured = false, serverUrl = current.serverUrl, message = keepMessage)
            return
        }
        val devices = try {
            api.listDevices(current)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _ui.value = _ui.value.copy(
                configured = true,
                serverUrl = current.serverUrl,
                message = keepMessage ?: error.message ?: "Geräteliste nicht erreichbar",
            )
            return
        }
        _ui.value = PairUi(
            configured = true,
            serverUrl = current.serverUrl,
            selfId = sync.serverDeviceId(),
            devices = devices,
            message = keepMessage,
        )
    }

    fun approve(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true, message = null)
            try {
                sync.enrollOwnDevice("Handy")
                val current = sync.currentSettings()
                val linked = PairingLink.decode(text)
                val code = linked?.code ?: raw
                val server = linked?.server ?: current.serverUrl
                if (!PairingCodes.isWellFormed(code)) {
                    _ui.value = _ui.value.copy(busy = false, message = "Der Code hat nicht das Format ABC-DEF")
                    return@launch
                }
                if (!current.configured) {
                    _ui.value = _ui.value.copy(busy = false, message = "Zuerst Server-Adresse und Token speichern")
                    return@launch
                }
                val approved = api.pairApprove(current, PairingCodes.format(PairingCodes.normalize(code)), "Auto", server)
                loadDevices(keepMessage = "„${approved.name}“ darf synchronisieren")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _ui.value = _ui.value.copy(busy = false, message = error.message ?: "Freigabe fehlgeschlagen")
            }
        }
    }

    fun revoke(id: String) {
        viewModelScope.launch {
            try {
                val current = sync.currentSettings()
                api.revokeDevice(current, id)
                loadDevices(keepMessage = "Gerät gesperrt")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _ui.value = _ui.value.copy(message = error.message ?: "Sperren fehlgeschlagen")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairScreen(onBack: () -> Unit, viewModel: PairViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var code by remember { mutableStateOf("") }
    var showCamera by remember { mutableStateOf(false) }
    var cameraGranted by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraGranted = granted
        showCamera = granted
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Auto koppeln") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            if (!ui.configured) {
                Text("Zuerst in den Einstellungen die Server-Adresse und das Token speichern. Danach kann das Handy das Auto freigeben.")
                return@Column
            }
            Text(
                "Im Auto „Mit Handy verbinden“ öffnen. Den QR-Code scannen oder den Code abtippen. Das Auto bekommt einen eigenen Token, das Admin-Token bleibt am Server.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(16.dp))
            if (showCamera && cameraGranted) {
                QrScanner(onResult = { value ->
                    val linked = PairingLink.decode(value)
                    code = linked?.code ?: value
                    showCamera = false
                    viewModel.approve(value)
                })
                Spacer(Modifier.height(8.dp))
            }
            OutlinedButton(
                onClick = {
                    if (cameraGranted) showCamera = !showCamera else permission.launch(Manifest.permission.CAMERA)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (showCamera && cameraGranted) "Kamera aus" else "QR-Code scannen") }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.uppercase() },
                label = { Text("Code") },
                placeholder = { Text("K7P-4QX") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { viewModel.approve(code) },
                enabled = !ui.busy && code.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(if (ui.busy) "Freigabe läuft …" else "Auto freigeben") }
            ui.message?.let { message ->
                Text(message, modifier = Modifier.padding(top = 12.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text("Geräte", style = MaterialTheme.typography.titleMedium)
            if (ui.devices.isEmpty()) {
                Text(
                    "Noch kein Gerät angemeldet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            ui.devices.forEach { device ->
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (device.id == ui.selfId) "${device.name} (dieses Handy)" else device.name,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            if (device.revoked) "gesperrt" else "aktiv",
                            color = if (device.revoked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!device.revoked && device.id != ui.selfId) {
                        TextButton(onClick = { viewModel.revoke(device.id) }) { Text("Sperren") }
                    }
                }
            }
        }
    }
}
