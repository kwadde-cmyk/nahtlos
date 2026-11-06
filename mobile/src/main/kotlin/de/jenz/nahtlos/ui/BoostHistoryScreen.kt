package de.jenz.nahtlos.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jenz.nahtlos.core.wallet.WalletViewModel
import de.jenz.nahtlos.sync.BoostSync
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoostHistoryScreen(
    onBack: () -> Unit,
    viewModel: WalletViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Boosts") },
                navigationIcon = {
                    LabeledIconButton(label = "Zurück", onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
            )
        },
    ) { padding ->
        if (state.historyEntries.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(20.dp)) {
                Text("Noch keine Boosts.")
                Text(
                    "Gesendete und fehlgeschlagene Boosts stehen hier mit Datum und Uhrzeit. Der Verlauf gleicht sich mit dem anderen Gerät ab. Der Wallet-Link bleibt auf dem Gerät.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = 32.dp),
            ) {
                item {
                    Text(
                        "Gleiche Liste auf Handy und Auto. Datum und Uhrzeit sind die lokale Gerätezeit. Der Wallet-Link wird nicht synchronisiert.",
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(state.historyEntries, key = { it.id }) { entry ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                        Text(BoostSync.formatWhen(entry.atMs, ZoneId.systemDefault()), style = MaterialTheme.typography.titleMedium)
                        Text("Datum und Uhrzeit", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${entry.totalSats} sats · ${entry.podcast}", style = MaterialTheme.typography.titleMedium)
                        if (entry.episode.isNotBlank()) {
                            Text(entry.episode, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        entry.recipients.forEach { line ->
                            Text(
                                "${line.name} · ${line.sats} sats · ${line.status}",
                                color = if (line.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            if (line.address.isNotBlank()) {
                                Text(
                                    line.address,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (entry.canRetry) {
                            TextButton(onClick = { viewModel.retry(entry.id) }, enabled = !state.busy) {
                                Text("Fehlende erneut")
                            }
                        }
                    }
                }
            }
        }
    }
}

