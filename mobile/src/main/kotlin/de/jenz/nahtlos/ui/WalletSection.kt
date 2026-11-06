package de.jenz.nahtlos.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jenz.nahtlos.core.wallet.WalletHandoffViewModel
import de.jenz.nahtlos.core.wallet.WalletViewModel
import de.jenz.nahtlos.wallet.WalletRelay
import de.jenz.nahtlos.core.wallet.formatSats
import de.jenz.nahtlos.sync.BoostSync
import java.time.ZoneId
import kotlinx.coroutines.flow.collectLatest

@Composable
fun WalletSection(
    viewModel: WalletViewModel = hiltViewModel(),
    onNotice: (String) -> Unit = {},
    onOpenHistory: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var link by remember { mutableStateOf("") }
    var sender by remember(state.senderName) { mutableStateOf(state.senderName) }
    var defaultSats by remember(state.defaultSats) { mutableStateOf(state.defaultSats.toString()) }
    var budget by remember(state.dailyBudget) { mutableStateOf(state.dailyBudget.toString()) }
    var scan by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        camera = granted
        if (granted) scan = true
    }
    LaunchedEffect(viewModel) {
        viewModel.notice.collectLatest { onNotice(it) }
    }
    Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Text("Wallet & Zahlungen", style = MaterialTheme.typography.titleMedium)
        Text(
            "Alby Hub verbindest du mit einem NWC-Link (nostr+walletconnect://…). Den findest du im Hub unter Apps oder Verbindungen. Er bleibt verschlüsselt auf diesem Gerät und geht nicht an den Sync-Server.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        state.storageError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        }
        if (state.connected) {
            Text(
                listOfNotNull(
                    state.alias.ifBlank { "Wallet" },
                    state.balanceLabel.ifBlank { null },
                    state.methods.takeIf { it.isNotEmpty() }?.joinToString(),
                ).joinToString(" · "),
                modifier = Modifier.padding(top = 8.dp),
            )
            Text("Heute ${state.spentToday} von ${state.dailyBudget} sats.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!state.connected) {
            OutlinedTextField(
                value = link,
                onValueChange = { link = it.trim() },
                label = { Text("NWC-Link") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            if (scan && camera) {
                QrScanner(onResult = { value ->
                    if (value.startsWith("nostr+walletconnect://")) {
                        link = value.trim()
                        scan = false
                    }
                })
            }
            TextButton(onClick = {
                if (camera) scan = !scan else permission.launch(Manifest.permission.CAMERA)
            }) { Text(if (scan) "Scanner schließen" else "QR scannen") }
            Button(
                onClick = { viewModel.connect(link) },
                enabled = !state.busy && link.startsWith("nostr+walletconnect://"),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Verbinden") }
        } else {
            OutlinedButton(
                onClick = { viewModel.disconnect() },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { Text("Trennen") }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        WalletHandoff(stored = state.connected)
        OutlinedTextField(
            value = sender,
            onValueChange = { sender = it.take(40) },
            label = { Text("Absendername") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        OutlinedTextField(
            value = defaultSats,
            onValueChange = { defaultSats = it.filter { char -> char.isDigit() }.take(7) },
            label = { Text("Standard-Boost in Sats") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = budget,
            onValueChange = { budget = it.filter { char -> char.isDigit() }.take(8) },
            label = { Text("Tagesbudget in Sats") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = {
            viewModel.saveLimits(sender, defaultSats.toLongOrNull() ?: 210, budget.toLongOrNull() ?: 5000)
        }) { Text("Beträge speichern") }
        TextButton(onClick = onOpenHistory, modifier = Modifier.padding(top = 8.dp)) { Text("Boost-Verlauf") }
        if (state.history.isNotEmpty()) {
            Text("Letzte Zahlungen", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            state.history.take(12).forEach { payment ->
                val whenLabel = BoostSync.formatWhen(payment.atMs, ZoneId.systemDefault())
                Text(
                    "$whenLabel · ${payment.totalSats} sats · ${payment.podcast}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                payment.lines.forEach { line ->
                    Text(
                        line.text,
                        color = if (line.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (payment.routingMsat > 0) {
                    Text(
                        "Routing ${formatSats(payment.routingMsat)}, extra.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (payment.canRetry) {
                    TextButton(onClick = { viewModel.retry(payment.id) }, enabled = !state.busy) { Text("Fehlende erneut") }
                }
            }
        }
    }
}

@Composable
private fun WalletHandoff(stored: Boolean, viewModel: WalletHandoffViewModel = hiltViewModel()) {
    val message by viewModel.message.collectAsStateWithLifecycle()
    var other by remember { mutableStateOf("") }
    var qr by remember { mutableStateOf("") }
    var scan by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf(false) }
    val offer = remember(qr) { WalletRelay.parseOffer(qr) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        camera = granted
        if (granted) scan = true
    }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text("Ans Auto senden", style = MaterialTheme.typography.titleSmall)
        Text(
            "Im Auto, im Stand, „Wallet vom Handy holen“ öffnen. Den QR dort scannen und den Code vergleichen. Dieselbe Verbindung teilt Guthaben und Tagesbudget. Für eine eigene Auto-Wallet hier eine zweite Alby-Verbindung einfügen oder ihren QR scannen, nicht den vom Auto.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        OutlinedTextField(
            value = other,
            onValueChange = { other = it.trim() },
            label = { Text(if (stored) "Andere Verbindung, sonst die hier gespeicherte" else "NWC-Link fürs Auto") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        if (scan && camera) {
            QrScanner(onResult = { value ->
                val text = value.trim()
                if (text.startsWith("nahtlos://wallet")) {
                    qr = text
                    scan = false
                } else if (text.startsWith("nostr+walletconnect://")) {
                    other = text
                    scan = false
                }
            })
        }
        TextButton(onClick = {
            if (camera) scan = !scan else permission.launch(Manifest.permission.CAMERA)
        }) { Text(if (scan) "Scanner schließen" else "QR scannen") }
        if (offer != null) {
            Text(
                "Code ${offer.fingerprint}. Nur senden, wenn das Auto denselben zeigt.",
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Button(
            onClick = { viewModel.send(qr, other, confirmed = offer != null) },
            enabled = offer != null && (stored || other.startsWith("nostr+walletconnect://")),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Codes stimmen überein, senden") }
        if (message.isNotBlank()) {
            Text(message, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
