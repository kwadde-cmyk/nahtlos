package de.jenz.nahtlos.automotive

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jenz.nahtlos.core.nostr.NostrViewModel

@Composable
fun CarNostr(nostr: NostrViewModel) {
    val ui by nostr.state.collectAsStateWithLifecycle()
    var relays by remember(ui.relays) { mutableStateOf(ui.relays) }
    Column(Modifier.fillMaxWidth()) {
        Text("Nostr", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Das Auto legt keinen Schlüssel an. Das Handy legt den öffentlichen Schlüssel auf den Server und signiert mit Amber. Kommentare, Likes und Zaps warten höchstens 90 Sekunden aufs Handy.",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )
        if (ui.loggedIn) {
            Text(ui.name.ifBlank { "Angemeldet" }, style = MaterialTheme.typography.titleLarge)
            Text(ui.pubkey.take(16) + "…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(
                onClick = nostr::logout,
                modifier = Modifier.fillMaxWidth().height(76.dp).padding(top = 8.dp),
            ) { Text("Abmelden") }
        }
        Button(
            onClick = nostr::pullFromPhone,
            enabled = !ui.busy,
            modifier = Modifier.fillMaxWidth().height(76.dp),
        ) { Text(if (ui.busy) "Hole …" else "Konto vom Handy holen") }
        if (ui.notice.isNotBlank()) Text(ui.notice, modifier = Modifier.padding(top = 8.dp))
        Text("Relays", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp))
        OutlinedTextField(
            value = relays,
            onValueChange = { relays = it },
            label = { Text("Eine Adresse pro Zeile") },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        OutlinedButton(
            onClick = { nostr.saveRelays(relays) },
            modifier = Modifier.fillMaxWidth().height(64.dp).padding(top = 8.dp),
        ) { Text("Relays speichern") }
    }
}
