package de.jenz.nahtlos.automotive

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
            "Das Auto legt keinen Schlüssel an. Amber auf dem Handy signiert. Der Code hier enthält nur die Sitzung, keinen privaten Schlüssel, und der Server sieht ihn nicht.",
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
        if (ui.carUri.isNotBlank()) {
            val image = remember(ui.carUri) { pairingQr(ui.carUri) }
            Image(
                bitmap = image,
                contentDescription = "Code für Amber",
                modifier = Modifier
                    .padding(top = 12.dp)
                    .size(280.dp)
                    .align(Alignment.CenterHorizontally)
                    .background(Color.White)
                    .padding(12.dp),
            )
            Text(ui.notice, modifier = Modifier.padding(top = 12.dp))
            OutlinedButton(
                onClick = nostr::cancelCarOffer,
                modifier = Modifier.fillMaxWidth().height(76.dp).padding(top = 8.dp),
            ) { Text("Abbrechen") }
        } else if (!ui.loggedIn) {
            Button(
                onClick = nostr::startCarOffer,
                enabled = !ui.busy,
                modifier = Modifier.fillMaxWidth().height(76.dp),
            ) { Text(if (ui.busy) "Verbinde …" else "Mit dem Handy anmelden") }
            if (ui.notice.isNotBlank()) Text(ui.notice, modifier = Modifier.padding(top = 8.dp))
        }
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
