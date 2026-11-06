package de.jenz.nahtlos.automotive

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import de.jenz.nahtlos.core.CrashLog
import de.jenz.nahtlos.core.Payments
import de.jenz.nahtlos.core.SettingsViewModel
import de.jenz.nahtlos.core.playback.AutomotiveSetup
import de.jenz.nahtlos.core.wallet.WalletViewModel
import de.jenz.nahtlos.core.wallet.formatSats
import de.jenz.nahtlos.feed.EpisodeHorizon
import de.jenz.nahtlos.playback.PlaybackCachePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@AndroidEntryPoint
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val openPairing = intent.getBooleanExtra(AutomotiveSetup.EXTRA_OPEN_PAIRING, false)
        setContent {
            AutoTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    CarSettings(openPairing = openPairing)
                }
            }
        }
    }
}

@Composable
private fun CarSettings(
    openPairing: Boolean,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val walletModel: WalletViewModel = hiltViewModel()
    val wallet by walletModel.state.collectAsStateWithLifecycle()
    val driving by rememberDriving()
    var url by remember(state.serverUrl) {
        mutableStateOf(state.serverUrl.ifBlank { BuildConfig.NAHTLOS_DEFAULT_SERVER })
    }
    var token by remember(state.token) { mutableStateOf(state.token) }
    var buffer by remember(state.bufferAheadMinutes) { mutableIntStateOf(state.bufferAheadMinutes) }
    var unmetered by remember(state.downloadOnUnmeteredOnly) { mutableStateOf(state.downloadOnUnmeteredOnly) }
    var limit by remember(state.downloadLimitMb) { mutableStateOf(state.downloadLimitMb.toString()) }
    var deleteHours by remember(state.deletePlayedAfterHours) { mutableStateOf(state.deletePlayedAfterHours.toString()) }
    var horizon by remember(state.episodeHorizonId) { mutableStateOf(state.episodeHorizonId) }
    var nwc by remember { mutableStateOf("") }
    var sender by remember(wallet.senderName) { mutableStateOf(wallet.senderName) }
    var defaultSats by remember(wallet.defaultSats) { mutableStateOf(wallet.defaultSats.toString()) }
    var budget by remember(wallet.dailyBudget) { mutableStateOf(wallet.dailyBudget.toString()) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var crash by remember { mutableStateOf<String?>(null) }
    var nav by remember { mutableStateOf(AutoNav.initial(openPairing)) }
    val leave = { MediaCenterExit.open(context) }
    LaunchedEffect(Unit) {
        viewModel.refresh()
        crash = withContext(Dispatchers.IO) { CrashLog.read(context) }
    }
    LaunchedEffect(viewModel) {
        viewModel.notice.collectLatest { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(walletModel) {
        walletModel.notice.collectLatest { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(driving) {
        nav = nav.withDriving(driving)
    }
    BackHandler {
        val next = nav.back()
        if (next == null) leave() else nav = next
    }
    val server = url.trim().trimEnd('/')
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().widthIn(max = 840.dp)) {
            SettingsBar(
                onBack = {
                    val next = nav.back()
                    if (next == null) leave() else nav = next
                },
                onMediaCenter = leave,
                showMediaCenter = nav.visible == AutoScreen.Pairing || nav.visible == AutoScreen.Wallet,
            )
            when (nav.visible) {
                AutoScreen.Locked -> Column(Modifier.padding(horizontal = 48.dp, vertical = 28.dp)) {
                    Text("Bitte im Stand einrichten", style = MaterialTheme.typography.headlineLarge)
                    Text(
                        "Kopplung, Server-Token und Wallet-Link gibt es nur, wenn das Auto steht. Die Wiedergabe läuft über die Medien-App weiter.",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                AutoScreen.Pairing -> Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 48.dp, vertical = 12.dp),
                ) {
                    Text("Handy koppeln", style = MaterialTheme.typography.headlineLarge)
                    Text(
                        "QR-Code und Kurzcode. Das Handy scannt oder tippt den Code und gibt dieses Auto frei.",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                    )
                    if (server.isBlank()) {
                        OutlinedTextField(colors = autoFieldColors(),
                            value = url,
                            onValueChange = { url = it },
                            label = { Text("Server-URL") },
                            placeholder = { Text("http://192.168.1.20:8787") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        )
                    }
                    CarPairing(serverUrl = server, configured = state.configured)
                }
                AutoScreen.Wallet -> Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 48.dp, vertical = 12.dp),
                ) {
                    WalletReceive(driving = false)
                }
                AutoScreen.Settings -> Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 48.dp, vertical = 12.dp),
                ) {
            Text("Nahtlos", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Geparkt einrichten. Danach spielt die Medien-App ab der Position, an der das Telefon pausiert hat. Dieser Bildschirm hat kein distractionOptimized und bleibt während der Fahrt gesperrt.",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
            )
            Button(
                onClick = { nav = nav.openPairing() },
                modifier = Modifier.fillMaxWidth().height(76.dp),
            ) { Text(if (state.configured) "Erneut per QR-Code koppeln" else "Handy per QR-Code koppeln") }
            Text(
                "Zeigt den QR-Code und den Kurzcode. Das Handy muss denselben Server erreichen.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
            )
            Text("Server-Adresse", style = MaterialTheme.typography.titleMedium)
            Text(
                "Nur nötig, wenn sie nicht schon im QR-Code steckt und kein NAHTLOS_DEFAULT_SERVER gebaut wurde.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            OutlinedTextField(colors = autoFieldColors(),
                value = url,
                onValueChange = { url = it },
                label = { Text("Server-URL") },
                placeholder = { Text("http://192.168.1.20:8787") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Text("Token von Hand", style = MaterialTheme.typography.titleMedium)
            Text(
                "Fallback, wenn das Koppeln nicht geht. Beim Speichern mit dem Admin-Token legt der Server ein eigenes Geräte-Token an.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            OutlinedTextField(colors = autoFieldColors(),
                value = token,
                onValueChange = { token = it },
                label = { Text("Token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(20.dp))
            if (Payments.enabled) {
            Text("Wallet & Zahlungen", style = MaterialTheme.typography.titleMedium)
            Button(
                onClick = { nav = nav.openWallet() },
                modifier = Modifier.fillMaxWidth().height(76.dp).padding(top = 8.dp),
            ) { Text("Wallet vom Handy holen") }
            Text(
                "Eigener NWC-Link, verschlüsselt nur auf diesem Auto. „Wallet vom Handy holen“ nimmt eine Verbindung vom Telefon entgegen. Der Server sieht nur das Chiffrat. Abtippen bleibt der Notfall.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            wallet.storageError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            if (wallet.connected) {
                Text(
                    listOf(wallet.alias.ifBlank { "Wallet" }, wallet.balanceLabel).filter { it.isNotBlank() }.joinToString(" · "),
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text("Heute ${wallet.spentToday} von ${wallet.dailyBudget} sats.")
                OutlinedButton(
                    onClick = { walletModel.disconnect() },
                    modifier = Modifier.fillMaxWidth().height(64.dp).padding(top = 8.dp),
                ) { Text("Wallet trennen") }
            } else {
                OutlinedTextField(colors = autoFieldColors(),
                    value = nwc,
                    onValueChange = { nwc = it.trim() },
                    label = { Text("NWC-Link") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Button(
                    onClick = { walletModel.connect(nwc) },
                    enabled = !wallet.busy && nwc.startsWith("nostr+walletconnect://"),
                    modifier = Modifier.fillMaxWidth().height(64.dp).padding(top = 8.dp),
                ) { Text(if (wallet.busy) "Verbinde …" else "Wallet verbinden") }
            }
            OutlinedTextField(colors = autoFieldColors(),
                value = sender,
                onValueChange = { sender = it.take(40) },
                label = { Text("Absendername") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(colors = autoFieldColors(),
                value = defaultSats,
                onValueChange = { defaultSats = it.filter { char -> char.isDigit() }.take(7) },
                label = { Text("Schnell-Boost in Sats") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(colors = autoFieldColors(),
                value = budget,
                onValueChange = { budget = it.filter { char -> char.isDigit() }.take(8) },
                label = { Text("Tagesbudget in Sats") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = { walletModel.saveLimits(sender, defaultSats.toLongOrNull() ?: 210, budget.toLongOrNull() ?: 5000) },
                modifier = Modifier.fillMaxWidth().height(64.dp),
            ) { Text("Beträge speichern") }
            wallet.history.take(5).forEach { payment ->
                Text("${payment.totalSats} sats · ${payment.podcast}", modifier = Modifier.padding(top = 8.dp))
                payment.lines.forEach { line -> Text(line.text, color = if (line.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                if (payment.routingMsat > 0) Text("Routing ${formatSats(payment.routingMsat)}, extra.")
                if (payment.canRetry) {
                    androidx.compose.material3.TextButton(onClick = { walletModel.retry(payment.id) }) { Text("Fehlende erneut") }
                }
            }
            }
            Spacer(Modifier.height(16.dp))
            Text("Puffer für Funklöcher: $buffer Minuten", style = MaterialTheme.typography.titleMedium)
            Text(
                "Beim Start im Auto wird die aktuelle Folge ab der gespeicherten Stelle in den Cache geladen. Voreinstellung 10 Minuten.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = buffer.toFloat(),
                onValueChange = { buffer = it.roundToInt() },
                valueRange = PlaybackCachePolicy.MIN_AHEAD_MINUTES.toFloat()..PlaybackCachePolicy.MAX_AHEAD_MINUTES.toFloat(),
                steps = PlaybackCachePolicy.MAX_AHEAD_MINUTES - PlaybackCachePolicy.MIN_AHEAD_MINUTES - 1,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
            Text("Folgen nachladen für die letzten …", style = MaterialTheme.typography.titleMedium)
            Text(
                "Ältere Folgen im Feed werden übersprungen. Playlist und gespeicherte Positionen bleiben.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Tempo ${"%.1f".format(state.playbackSpeed)}× · zurück ${state.skipBackSec}s · vor ${state.skipForwardSec}s",
                modifier = Modifier.padding(top = 8.dp),
            )
            Row {
                androidx.compose.material3.TextButton(onClick = { viewModel.nudgeSpeed(-1) }, modifier = Modifier.height(56.dp)) { Text("Langsamer") }
                androidx.compose.material3.TextButton(onClick = { viewModel.nudgeSpeed(1) }, modifier = Modifier.height(56.dp)) { Text("Schneller") }
            }
            EpisodeHorizon.entries.forEach { option ->
                androidx.compose.material3.TextButton(
                    onClick = {
                        horizon = option.id
                        viewModel.setEpisodeHorizon(option.id)
                    },
                    modifier = Modifier.height(56.dp),
                ) { Text(if (option.id == horizon) "● ${option.label}" else option.label) }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Downloads nur ohne Mobilfunk", modifier = Modifier.weight(1f))
                Switch(checked = unmetered, onCheckedChange = { unmetered = it })
            }
            OutlinedTextField(colors = autoFieldColors(),
                value = limit,
                onValueChange = { limit = it.filter { char -> char.isDigit() }.take(6) },
                label = { Text("Speicherlimit (MB)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(colors = autoFieldColors(),
                value = deleteHours,
                onValueChange = { deleteHours = it.filter { char -> char.isDigit() }.take(4) },
                label = { Text("Gespieltes löschen nach Stunden") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Geräte-ID", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
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
            Spacer(Modifier.height(16.dp))
            Text("Letzter Absturz", style = MaterialTheme.typography.titleSmall)
            if (crash.isNullOrBlank()) {
                Text(
                    "Keiner protokolliert.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                SelectionContainer {
                    Text(
                        crash.orEmpty(),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 8,
                    )
                }
                Row {
                    androidx.compose.material3.TextButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Nahtlos Absturz")
                            putExtra(Intent.EXTRA_TEXT, crash)
                        }
                        context.startActivity(Intent.createChooser(send, "Absturzbericht teilen"))
                    }) { Text("Teilen") }
                    androidx.compose.material3.TextButton(onClick = {
                        CrashLog.clear(context)
                        crash = null
                    }) { Text("Löschen") }
                }
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    viewModel.syncNow(url, token, buffer, unmetered, limit.toIntOrNull() ?: 2048, deleteHours.toIntOrNull() ?: 24, enrollName = "Auto")
                },
                modifier = Modifier.fillMaxWidth().height(64.dp),
            ) { Text(if (state.syncing) "Synchronisiert …" else "Speichern und synchronisieren") }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    viewModel.test(url, token, buffer, unmetered, limit.toIntOrNull() ?: 2048, deleteHours.toIntOrNull() ?: 24, enrollName = "Auto")
                },
                modifier = Modifier.fillMaxWidth().height(64.dp),
            ) { Text("Verbindung prüfen") }
                }
            }
        }
        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp))
    }
}

@Composable
private fun SettingsBar(
    onBack: () -> Unit,
    onMediaCenter: () -> Unit,
    showMediaCenter: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = onBack, modifier = Modifier.height(64.dp)) { Text("Zurück") }
        if (showMediaCenter) {
            OutlinedButton(
                onClick = onMediaCenter,
                modifier = Modifier.padding(start = 12.dp).height(64.dp),
            ) { Text("Mediencenter") }
        }
    }
}

private fun formatWhen(epochMs: Long?): String {
    if (epochMs == null || epochMs <= 0) return "noch nie"
    val formatter = DateTimeFormatter.ofPattern("d. MMM, HH:mm", Locale.GERMAN)
    return Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(formatter)
}

@Composable
private fun AutoTheme(content: @Composable () -> Unit) {
    val tone = if (isSystemInDarkTheme()) AutoPalette.dark else AutoPalette.light
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = Color(tone.cursor),
            onPrimary = Color(0xFF2A1C00),
            background = Color(tone.page),
            onBackground = Color(tone.onPage),
            surface = Color(tone.page),
            onSurface = Color(tone.onPage),
            surfaceVariant = Color(tone.field),
            onSurfaceVariant = Color(tone.placeholder),
            outline = Color(tone.outline),
            error = Color(0xFFFFB4A8),
        )
    } else {
        lightColorScheme(
            primary = Color(tone.cursor),
            onPrimary = Color(0xFFFFFFFF),
            background = Color(tone.page),
            onBackground = Color(tone.onPage),
            surface = Color(tone.page),
            onSurface = Color(tone.onPage),
            surfaceVariant = Color(tone.field),
            onSurfaceVariant = Color(tone.placeholder),
            outline = Color(tone.outline),
        )
    }
    MaterialTheme(
        colorScheme = scheme,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(12.dp),
            small = RoundedCornerShape(16.dp),
            medium = RoundedCornerShape(20.dp),
            large = RoundedCornerShape(24.dp),
            extraLarge = RoundedCornerShape(28.dp),
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = scheme.background,
            contentColor = scheme.onBackground,
            content = content,
        )
    }
}

@Composable
fun autoFieldColors() = run {
    val tone = if (isSystemInDarkTheme()) AutoPalette.dark else AutoPalette.light
    val text = Color(tone.text)
    val hint = Color(tone.placeholder)
    val label = Color(tone.label)
    val cursor = Color(tone.cursor)
    val field = Color(tone.field)
    val line = Color(tone.outline)
    OutlinedTextFieldDefaults.colors(
        focusedTextColor = text,
        unfocusedTextColor = text,
        disabledTextColor = text.copy(alpha = 0.62f),
        cursorColor = cursor,
        errorCursorColor = cursor,
        focusedContainerColor = field,
        unfocusedContainerColor = field,
        disabledContainerColor = field,
        focusedLabelColor = label,
        unfocusedLabelColor = hint,
        disabledLabelColor = hint,
        focusedPlaceholderColor = hint,
        unfocusedPlaceholderColor = hint,
        disabledPlaceholderColor = hint,
        focusedBorderColor = line,
        unfocusedBorderColor = line,
    )
}
