package de.jenz.nahtlos.automotive

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dagger.hilt.android.AndroidEntryPoint
import de.jenz.nahtlos.core.Payments
import de.jenz.nahtlos.core.playback.AutomotiveSetup
import de.jenz.nahtlos.core.playback.PlayerConnection
import de.jenz.nahtlos.core.playback.PlayerSnapshot
import de.jenz.nahtlos.core.wallet.BoostService
import de.jenz.nahtlos.sync.BoostSync
import javax.inject.Inject

@AndroidEntryPoint
class NowPlayingActivity : ComponentActivity() {
    @Inject lateinit var player: PlayerConnection
    @Inject lateinit var boosts: BoostService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AutoTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    NowPlaying(player, boosts)
                }
            }
        }
    }
}

@Composable
private fun NowPlaying(player: PlayerConnection, boosts: BoostService) {
    val playback by player.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var sending by remember { mutableStateOf(false) }
    var banner by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(player) { player.connect() }
    LaunchedEffect(boosts) {
        boosts.outcome.collect { text ->
            banner = text
            sending = false
        }
    }
    val something = playback.title.isNotBlank() || !playback.episodeKey.isNullOrBlank()
    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        banner?.let { text ->
            Text(
                text,
                style = MaterialTheme.typography.headlineSmall,
                color = if (text.startsWith(BoostSync.OUTCOME_FAIL) || text.startsWith("Eine Zahlung")) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            )
        }
        if (!something) {
            Spacer(Modifier.height(48.dp))
            Text("Nichts läuft", style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
            Text(
                "Starte eine Folge aus der Bibliothek oder der Playlist.",
                modifier = Modifier.padding(top = 12.dp),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(28.dp))
            Button(onClick = { MediaCenterExit.open(context) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("Bibliothek und Playlist")
            }
        } else {
            Cover(playback.artworkUri)
            Text(
                playback.title.ifBlank { "Folge" },
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
            if (playback.artist.isNotBlank()) {
                Text(playback.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            val fraction = if (playback.durationMs > 0) {
                (playback.positionMs.toFloat() / playback.durationMs.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(6.dp),
                color = Color(0xFFFF7A1A),
            )
            Text(
                "${clock(playback.positionMs)} / ${clock(playback.durationMs)}",
                modifier = Modifier.padding(top = 6.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 18.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = player::seekBack) { Text("−15s") }
                Button(onClick = player::toggle, modifier = Modifier.height(64.dp)) {
                    Text(if (playback.isPlaying) "Pause" else "Play")
                }
                OutlinedButton(onClick = player::seekForward) { Text("+15s") }
            }
            if (Payments.enabled) {
                Button(
                    enabled = !sending,
                    onClick = {
                        if (!BoostSync.acceptTap(sending)) return@Button
                        sending = true
                        banner = null
                        if (!player.quickBoost()) {
                            sending = false
                            banner = "Boost nicht gesendet. Player nicht bereit."
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF7A1A), contentColor = Color(0xFF1A0E00)),
                ) { Text(if (sending) "Boost läuft…" else "Boost") }
            }
            playback.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            }
            OutlinedButton(
                onClick = { MediaCenterExit.open(context) },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) { Text("Bibliothek") }
        }
        Spacer(Modifier.weight(1f))
        OutlinedButton(
            onClick = { context.startActivity(AutomotiveSetup.preferencesIntent(context.packageName)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Einstellungen") }
    }
}

@Composable
private fun Cover(uri: String?) {
    if (uri.isNullOrBlank()) {
        Surface(
            modifier = Modifier.size(280.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {}
    } else {
        AsyncImage(
            model = uri,
            contentDescription = "Cover",
            modifier = Modifier.size(280.dp).clip(RoundedCornerShape(28.dp)),
            contentScale = ContentScale.Crop,
        )
    }
}

private fun clock(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
