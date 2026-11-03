package de.jenz.nahtlos.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.jenz.nahtlos.core.wallet.BoostInput
import de.jenz.nahtlos.core.wallet.PaymentView
import de.jenz.nahtlos.core.wallet.WalletUi
import de.jenz.nahtlos.core.wallet.formatSats
import de.jenz.nahtlos.model.ValueRecipient
import de.jenz.nahtlos.model.ValueTimeSplit
import de.jenz.nahtlos.wallet.BoostSplits

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BoostDialog(
    podcastTitle: String,
    episodeTitle: String?,
    feedUrl: String?,
    feedGuid: String?,
    episodeGuid: String?,
    positionMs: Long?,
    episodeRecipients: List<ValueRecipient>,
    timeSplits: List<ValueTimeSplit>,
    channelRecipients: List<ValueRecipient>,
    wallet: WalletUi,
    busy: Boolean,
    result: PaymentView?,
    error: String?,
    onDismiss: () -> Unit,
    onSend: (BoostInput) -> Unit,
) {
    var amountText by remember(wallet.defaultSats) { mutableStateOf(wallet.defaultSats.toString()) }
    var message by remember { mutableStateOf("") }
    var sender by remember(wallet.senderName) { mutableStateOf(wallet.senderName) }
    val amount = amountText.toLongOrNull()
    val shares = if (amount != null && amount > 0) {
        BoostSplits.plan(amount, episodeRecipients, timeSplits, channelRecipients, positionMs)
    } else {
        emptyList()
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (episodeTitle.isNullOrBlank()) "Boost für $podcastTitle" else "Boost") },
        text = {
            Column {
                if (!wallet.connected) {
                    Text("Unter Einstellungen → Wallet & Zahlungen den NWC-Link von Alby Hub einfügen. Ohne Verbindung wird nichts gesendet.")
                    return@Column
                }
                if (result != null) {
                    Text("${result.totalSats} sats · ${result.podcast}")
                    result.lines.forEach { line ->
                        Text(
                            line.text,
                            color = if (line.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    if (result.routingMsat > 0) {
                        Text(
                            "Routing ${formatSats(result.routingMsat)}, extra und nicht in den Empfänger-Beträgen.",
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    return@Column
                }
                Text("Vorschläge, oder ein eigener Betrag. Heute ${wallet.spentToday} von ${wallet.dailyBudget} sats.")
                FlowRow(modifier = Modifier.padding(top = 8.dp)) {
                    listOf(21L, 210L, 2100L).forEach { suggestion ->
                        FilterChip(
                            selected = amount == suggestion,
                            onClick = { amountText = suggestion.toString() },
                            label = { Text("$suggestion") },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it.filter { char -> char.isDigit() }.take(7) },
                    label = { Text("Sats") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it.take(200) },
                    label = { Text("Boostagram, optional") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = sender,
                    onValueChange = { sender = it.take(40) },
                    label = { Text("Absendername") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                if (shares.isEmpty()) {
                    Text("Keine zahlbaren Empfänger für diesen Zeitpunkt.", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                } else {
                    Text("Aufteilung, ganze Sats", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    shares.forEach { share ->
                        val fee = if (share.fee) " · Gebühr" else ""
                        Text("${share.name} · ${share.sats} sats · ${share.type}$fee")
                    }
                    Text(
                        "Routing-Gebühren kommen danach extra dazu und stecken nicht in diesen Beträgen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            if (result != null || !wallet.connected) {
                TextButton(onClick = onDismiss) { Text("Schließen") }
            } else {
                TextButton(
                    enabled = !busy && amount != null && shares.isNotEmpty() && shares.sumOf { it.sats } == amount,
                    onClick = {
                        onSend(
                            BoostInput(
                                podcastTitle = podcastTitle,
                                episodeTitle = episodeTitle,
                                feedUrl = feedUrl,
                                feedGuid = feedGuid,
                                episodeGuid = episodeGuid,
                                positionMs = positionMs,
                                episodeRecipients = episodeRecipients,
                                timeSplits = timeSplits,
                                channelRecipients = channelRecipients,
                                amountSats = amount ?: return@TextButton,
                                message = message,
                                senderName = sender,
                            ),
                        )
                    },
                ) { Text("Senden") }
            }
        },
        dismissButton = {
            if (result == null && wallet.connected) {
                TextButton(enabled = !busy, onClick = onDismiss) { Text("Abbrechen") }
            }
        },
    )
}
