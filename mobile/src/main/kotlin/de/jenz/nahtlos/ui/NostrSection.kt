package de.jenz.nahtlos.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import de.jenz.nahtlos.core.nostr.NostrViewModel

@Composable
fun NostrSection(viewModel: NostrViewModel = hiltViewModel()) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    var relays by remember(ui.relays) { mutableStateOf(ui.relays) }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text("Nostr", style = MaterialTheme.typography.titleMedium)
        Text(
            "Anmeldung nur über Amber. Nahtlos legt keinen Schlüssel an und importiert keinen nsec.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        if (ui.loggedIn) {
            if (!ui.picture.isNullOrBlank()) {
                AsyncImage(
                    model = ui.picture,
                    contentDescription = ui.name,
                    modifier = Modifier.size(56.dp).clip(MaterialTheme.shapes.medium),
                    contentScale = ContentScale.Crop,
                )
            }
            Text(ui.name.ifBlank { "Angemeldet" }, style = MaterialTheme.typography.titleLarge)
            Text(ui.pubkey.take(16) + "…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = viewModel::logout, modifier = Modifier.padding(top = 8.dp)) { Text("Abmelden") }
        } else if (ui.amberInstalled) {
            Button(onClick = viewModel::login, enabled = !ui.busy) { Text(if (ui.busy) "Amber …" else "Mit Amber anmelden") }
        } else {
            Text("Amber ist nicht installiert. Ohne Amber gibt es hier kein Konto.")
        }
        Spacer(Modifier.height(12.dp))
        Text("Auto", style = MaterialTheme.typography.titleSmall)
        Text(
            "Das Konto geht über den gekoppelten Server ans Auto. Das Handy signiert danach die Kommentare, Likes und Zaps mit Amber. Ein privater Schlüssel geht nicht an den Server.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (ui.loggedIn) {
            Button(onClick = viewModel::shareWithCar, enabled = !ui.busy, modifier = Modifier.padding(top = 8.dp)) {
                Text("Konto ans Auto geben")
            }
        }
        OutlinedTextField(
            value = relays,
            onValueChange = { relays = it },
            label = { Text("Relays, eine Adresse pro Zeile") },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        TextButton(onClick = { viewModel.saveRelays(relays) }) { Text("Relays speichern") }
        if (ui.notice.isNotBlank()) {
            Text(ui.notice, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
fun ZapSentDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        title = { Text(if (message.startsWith("Zap gesendet")) "Zap gesendet" else "Zap") },
        text = { Text(message) },
    )
}
