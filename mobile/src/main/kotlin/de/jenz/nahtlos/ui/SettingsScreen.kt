package de.jenz.nahtlos.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jenz.nahtlos.core.CrashLog
import de.jenz.nahtlos.core.SettingsViewModel
import de.jenz.nahtlos.feed.EpisodeHorizon
import de.jenz.nahtlos.playback.PlaybackCachePolicy
import de.jenz.nahtlos.playback.PlaybackTuning
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SwipeChoices(selected: de.jenz.nahtlos.playback.SwipeAction, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
        de.jenz.nahtlos.playback.SwipeAction.entries.forEach { action ->
            TextButton(onClick = { onSelect(action.id) }) {
                Text(if (action == selected) "● ${action.label}" else action.label)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun HorizonChoices(selected: String, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        EpisodeHorizon.entries.forEach { option ->
            TextButton(onClick = { onSelect(option.id) }) {
                Text(if (option.id == selected) "● ${option.label}" else option.label)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenPairing: () -> Unit = {},
    wide: Boolean = false,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var url by remember(state.serverUrl) { mutableStateOf(state.serverUrl) }
    var token by remember(state.token) { mutableStateOf(state.token) }
    var buffer by remember(state.bufferAheadMinutes) { mutableIntStateOf(state.bufferAheadMinutes) }
    var unmetered by remember(state.downloadOnUnmeteredOnly) { mutableStateOf(state.downloadOnUnmeteredOnly) }
    var limit by remember(state.downloadLimitMb) { mutableStateOf(state.downloadLimitMb.toString()) }
    var deleteHours by remember(state.deletePlayedAfterHours) { mutableStateOf(state.deletePlayedAfterHours.toString()) }
    var horizon by remember(state.episodeHorizonId) { mutableStateOf(state.episodeHorizonId) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var crash by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        crash = withContext(Dispatchers.IO) { CrashLog.read(context) }
    }
    LaunchedEffect(viewModel) {
        viewModel.notice.collectLatest { message -> snackbar.showSnackbar(message) }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (wide) "Nahtlos Auto" else "Einstellungen") },
                navigationIcon = {
                    if (!wide) {
                        LabeledIconButton(label = "Zurück", onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (wide) 48.dp else 20.dp, vertical = 12.dp)
                .widthIn(max = 720.dp),
        ) {
            Text(
                "Phone und Auto teilen sich die Wiedergabeposition. Beim Pausieren wird sie hochgeladen, beim Start im Auto geholt. Der neuere Zeitstempel gewinnt — beide Uhren sollten stimmen.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Server-URL") },
                placeholder = { Text("http://192.168.1.20:8787") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            if (url.isNotBlank() && !url.trim().startsWith("http://") && !url.trim().startsWith("https://")) {
                Text(
                    "Die URL muss mit http:// oder https:// beginnen.",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text("Puffer für Funklöcher: $buffer Minuten", style = MaterialTheme.typography.titleSmall)
            Text(
                "So weit liest die Wiedergabe voraus und legt das Audio in einen Cache. Voreinstellung 10 Minuten. Wirkt sofort auf den Vorspul-Puffer; die Cache-Größe gilt ab dem nächsten Start des Players.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = buffer.toFloat(),
                onValueChange = { buffer = it.roundToInt() },
                valueRange = PlaybackCachePolicy.MIN_AHEAD_MINUTES.toFloat()..PlaybackCachePolicy.MAX_AHEAD_MINUTES.toFloat(),
                steps = PlaybackCachePolicy.MAX_AHEAD_MINUTES - PlaybackCachePolicy.MIN_AHEAD_MINUTES - 1,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text("Folgen nachladen für die letzten …", style = MaterialTheme.typography.titleSmall)
            Text(
                "Beim Aktualisieren werden ältere Folgen im Feed übersprungen. Was schon in der Playlist steht oder eine Position hat, bleibt.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            HorizonChoices(horizon) { id ->
                horizon = id
                viewModel.setEpisodeHorizon(id)
            }
            Text("Wischen nach rechts", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            SwipeChoices(state.swipeStart) { viewModel.setSwipe(start = true, it) }
            Text("Wischen nach links", style = MaterialTheme.typography.titleSmall)
            SwipeChoices(state.swipeEnd) { viewModel.setSwipe(start = false, it) }
            Text("Wiedergabe", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            Text(
                "Tempo ${"%.1f".format(state.playbackSpeed)}×, Sprung zurück ${state.skipBackSec}s, vor ${state.skipForwardSec}s. Pro Podcast lässt sich das im Player überschreiben.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Row {
                TextButton(onClick = { viewModel.nudgeSpeed(-1) }) { Text("Langsamer") }
                TextButton(onClick = { viewModel.nudgeSpeed(1) }) { Text("Schneller") }
                TextButton(onClick = { viewModel.nudgeSkip(back = true, delta = -5) }) { Text("−5s zurück") }
                TextButton(onClick = { viewModel.nudgeSkip(back = false, delta = 5) }) { Text("+5s vor") }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Downloads nur ohne Mobilfunk", modifier = Modifier.weight(1f))
                Switch(checked = unmetered, onCheckedChange = { unmetered = it })
            }
            OutlinedTextField(
                value = limit,
                onValueChange = { limit = it.filter { char -> char.isDigit() }.take(6) },
                label = { Text("Speicherlimit (MB)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = deleteHours,
                onValueChange = { deleteHours = it.filter { char -> char.isDigit() }.take(4) },
                label = { Text("Gespieltes löschen nach Stunden") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text("Geräte-ID", style = MaterialTheme.typography.labelLarge)
            SelectionContainer {
                Text(state.deviceId, fontFamily = FontFamily.Monospace)
            }
            if (!wide) {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Geräte-ID", state.deviceId))
                }) { Text("ID kopieren") }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Zuletzt synchronisiert: ${formatSyncTime(state.lastSyncAt)}" +
                    if (state.pending > 0) " · ${state.pending} in der Warteschlange" else "",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.message?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { viewModel.syncNow(url, token, buffer, unmetered, limit.toIntOrNull() ?: 2048, deleteHours.toIntOrNull() ?: 24) },
                modifier = Modifier.fillMaxWidth().height(if (wide) 64.dp else 52.dp),
            ) { Text(if (state.syncing) "Synchronisiert …" else "Speichern und synchronisieren") }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { viewModel.test(url, token, buffer, unmetered, limit.toIntOrNull() ?: 2048, deleteHours.toIntOrNull() ?: 24) },
                modifier = Modifier.fillMaxWidth().height(if (wide) 64.dp else 52.dp),
            ) { Text("Verbindung prüfen") }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onOpenPairing,
                modifier = Modifier.fillMaxWidth().height(if (wide) 64.dp else 52.dp),
            ) { Text("Auto koppeln") }
            Spacer(Modifier.height(20.dp))
            Text("Letzter Absturz", style = MaterialTheme.typography.titleSmall)
            if (crash.isNullOrBlank()) {
                Text(
                    "Keiner protokolliert. Ein Absturz schreibt den Stacktrace in den App-Speicher, auch ohne Logcat.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                SelectionContainer {
                    Text(crash.orEmpty(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
                Row {
                    TextButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Nahtlos Absturz")
                            putExtra(Intent.EXTRA_TEXT, crash)
                        }
                        context.startActivity(Intent.createChooser(send, "Absturzbericht teilen"))
                    }) { Text("Teilen") }
                    TextButton(onClick = {
                        CrashLog.clear(context)
                        crash = null
                    }) { Text("Löschen") }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "Im Auto reicht dieselbe URL und dasselbe Token. Ohne Eintrag bleibt der Browse-Baum leer und bietet „Server einrichten“ an.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
