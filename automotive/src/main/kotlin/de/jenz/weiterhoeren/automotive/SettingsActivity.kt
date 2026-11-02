package de.jenz.weiterhoeren.automotive

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import de.jenz.weiterhoeren.core.SettingsViewModel
import kotlinx.coroutines.flow.collectLatest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@AndroidEntryPoint
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AutoTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    CarSettings()
                }
            }
        }
    }
}

@Composable
private fun CarSettings(viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var url by remember(state.serverUrl) { mutableStateOf(state.serverUrl) }
    var token by remember(state.token) { mutableStateOf(state.token) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.refresh() }
    LaunchedEffect(viewModel) {
        viewModel.notice.collectLatest { snackbar.showSnackbar(it) }
    }
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .widthIn(max = 840.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 48.dp, vertical = 28.dp),
        ) {
            Text("Weiterhören", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Geparkt einrichten. Danach spielt die Medien-App ab der Position, an der das Telefon pausiert hat.",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Server-URL") },
                placeholder = { Text("http://192.168.1.20:8787") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Text("Geräte-ID", style = MaterialTheme.typography.labelLarge)
            SelectionContainer {
                Text(state.deviceId.ifBlank { "…" }, fontFamily = FontFamily.Monospace)
            }
            Text(
                "Zuletzt synchronisiert: ${formatWhen(state.lastSyncAt)}" +
                    if (state.pending > 0) " · ${state.pending} wartet" else "",
                modifier = Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.message?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { viewModel.syncNow(url, token) },
                modifier = Modifier.fillMaxWidth().height(64.dp),
            ) { Text(if (state.syncing) "Synchronisiert …" else "Speichern und synchronisieren") }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { viewModel.test(url, token) },
                modifier = Modifier.fillMaxWidth().height(64.dp),
            ) { Text("Verbindung prüfen") }
        }
        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp))
    }
}

private fun formatWhen(epochMs: Long?): String {
    if (epochMs == null || epochMs <= 0) return "noch nie"
    val formatter = DateTimeFormatter.ofPattern("d. MMM, HH:mm", Locale.GERMAN)
    return Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(formatter)
}

@Composable
private fun AutoTheme(content: @Composable () -> Unit) {
    val dark = darkColorScheme(
        primary = Color(0xFFF0A202),
        onPrimary = Color(0xFF2A1C00),
        background = Color(0xFF10120F),
        onBackground = Color(0xFFF3F1E7),
        surface = Color(0xFF10120F),
        onSurface = Color(0xFFF3F1E7),
        surfaceVariant = Color(0xFF242922),
        onSurfaceVariant = Color(0xFFC9C6B8),
        error = Color(0xFFFFB4A8),
    )
    val light = lightColorScheme(
        primary = Color(0xFF8A5A00),
        background = Color(0xFFF6F3EA),
        surface = Color(0xFFF6F3EA),
    )
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dark else light, content = content)
}
