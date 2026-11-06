package de.jenz.nahtlos.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import de.jenz.nahtlos.sync.BoostSync
import de.jenz.nahtlos.wallet.BoostSplits

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
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
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(busy, result, error) {
        if (armed && !busy && (result != null || error != null)) armed = false
    }
    val shares = if (amount != null && amount > 0) {
        BoostSplits.plan(amount, episodeRecipients, timeSplits, channelRecipients, positionMs)
    } else {
        emptyList()
    }
    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(
                if (episodeTitle.isNullOrBlank()) "Boost an $podcastTitle" else "Boost an $podcastTitle",
                style = MaterialTheme.typography.headlineSmall,
            )
            if (!episodeTitle.isNullOrBlank()) {
                Text(episodeTitle, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
                if (!wallet.connected) {
                    Text("Unter Einstellungen → Wallet & Zahlungen den NWC-Link von Alby Hub einfügen. Ohne Verbindung wird nichts gesendet.")
                    return@Column
                }
                if (result != null) {
                    val failed = result.lines.any { it.error }
                    Text(BoostSync.outcome(failed), style = MaterialTheme.typography.headlineMedium)
                    Text("${result.totalSats} sats · ${result.podcast}", modifier = Modifier.padding(top = 8.dp))
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
                Text(
                    "Heute ${wallet.spentToday} von ${wallet.dailyBudget} sats übrig.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
                FlowRow(modifier = Modifier.padding(top = 12.dp)) {
                    listOf(21L, 210L, 1000L, 2100L).forEach { suggestion ->
                        val chosen = amount == suggestion
                        Surface(
                            modifier = Modifier.padding(end = 8.dp, bottom = 8.dp).clickable { amountText = suggestion.toString() },
                            shape = RoundedCornerShape(24.dp),
                            color = if (chosen) HoppOrange else MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                "$suggestion",
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                color = if (chosen) Color(0xFF1A0E00) else MaterialTheme.colorScheme.onSurface,
                            )
                        }
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
                    label = { Text("Nachricht (optional)") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = sender,
                    onValueChange = { sender = it.take(40) },
                    label = { Text("Dein Name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                if (shares.isEmpty()) {
                    Text("Keine zahlbaren Empfänger für diesen Zeitpunkt.", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                } else {
                    Text(
                        "Aufteilung laut Feed · ${shares.size} Empfänger",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    shares.forEach { share ->
                        val fee = if (share.fee) " · Gebühr" else ""
                        Text("${share.name.ifBlank { share.address }} · ${share.sats} sats$fee")
                    }
                    Text(
                        "Routing-Gebühren kommen danach extra dazu und stecken nicht in diesen Beträgen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                error?.let { raw ->
                    val line = if (raw.startsWith("Boost") || raw.startsWith("Eine Zahlung")) raw else "Boost nicht gesendet. $raw"
                    Text(line, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
                }
            }
            if (result != null || !wallet.connected) {
                TextButton(onClick = onDismiss, modifier = Modifier.padding(top = 12.dp)) { Text("Schließen") }
            } else {
                Button(
                    enabled = !busy && !armed && amount != null && shares.isNotEmpty() && shares.sumOf { it.sats } == amount,
                    onClick = {
                        if (!BoostSync.acceptTap(armed || busy)) return@Button
                        armed = true
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
                                amountSats = amount ?: return@Button,
                                message = message,
                                senderName = sender,
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(56.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = HoppOrange, contentColor = Color(0xFF1A0E00)),
                ) { Text("${amount ?: 0} sats boosten") }
                TextButton(enabled = !busy, onClick = onDismiss) { Text("Abbrechen") }
            }
        }
    }
