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
import de.jenz.nahtlos.core.wallet.WalletViewModel
import de.jenz.nahtlos.core.wallet.formatSats
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.collectLatest

@Composable
fun WalletSection(viewModel: WalletViewModel = hiltViewModel(), onNotice: (String) -> Unit = {}) {
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
        if (state.history.isNotEmpty()) {
            Text("Zahlungen", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            state.history.take(12).forEach { payment ->
                val whenLabel = DateTimeFormatter.ofPattern("d. MMM HH:mm", Locale.GERMAN)
                    .format(Instant.ofEpochMilli(payment.atMs).atZone(ZoneId.systemDefault()))
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
