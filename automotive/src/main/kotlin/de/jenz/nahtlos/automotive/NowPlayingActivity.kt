package de.jenz.nahtlos.automotive

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

private val HoppOrange = Color(0xFFFF7A1A)
private val Ink = Color(0xFF1A0E00)
private val Track = Color(0xFF2A241C)
private val ButtonShape = RoundedCornerShape(22.dp)

@AndroidEntryPoint
class NowPlayingActivity : ComponentActivity() {
    @Inject lateinit var player: PlayerConnection
    @Inject lateinit var boosts: BoostService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AutoTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                        NowPlaying(player, boosts)
                    }
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
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        banner?.let { text ->
            Text(
                text,
                fontSize = AutoDrive.BODY_SP.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (text.startsWith(BoostSync.OUTCOME_FAIL) || text.startsWith("Eine Zahlung")) {
                    MaterialTheme.colorScheme.error
                } else {
                    HoppOrange
                },
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            )
        }
        if (!something) {
            Text(
                "Nichts läuft",
                fontSize = AutoDrive.TITLE_SP.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 48.dp),
            )
            Text(
                "Starte eine Folge aus der Bibliothek oder der Playlist.",
                fontSize = AutoDrive.BODY_SP.sp,
                modifier = Modifier.padding(top = 12.dp, bottom = 28.dp),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DriveButton(
                "Bibliothek und Playlist",
                filled = true,
                modifier = Modifier.fillMaxWidth(),
                onClick = { MediaCenterExit.open(context) },
            )
        } else {
            Cover(playback.artworkUri)
            Text(
                playback.title.ifBlank { "Folge" },
                fontSize = AutoDrive.TITLE_SP.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 38.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 16.dp),
            )
            if (playback.artist.isNotBlank()) {
                Text(
                    playback.artist,
                    fontSize = AutoDrive.BODY_SP.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Progress(playback)
            Text(
                "${clock(playback.positionMs)} / ${clock(playback.durationMs)}",
                fontSize = AutoDrive.BODY_SP.sp,
                modifier = Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DriveButton("−15s", filled = false, modifier = Modifier.weight(1f), onClick = player::seekBack)
                DriveButton(
                    if (playback.isPlaying) "Pause" else "Play",
                    filled = true,
                    modifier = Modifier.weight(1f),
                    onClick = player::toggle,
                )
                DriveButton("+15s", filled = false, modifier = Modifier.weight(1f), onClick = player::seekForward)
            }
            if (Payments.enabled) {
                DriveButton(
                    if (sending) "Boost läuft…" else "Boost",
                    filled = true,
                    enabled = !sending,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    onClick = {
                        if (!BoostSync.acceptTap(sending)) return@DriveButton
                        sending = true
                        banner = null
                        if (!player.quickBoost()) {
                            sending = false
                            banner = "Boost nicht gesendet. Player nicht bereit."
                        }
                    },
                )
            }
            playback.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = AutoDrive.BODY_SP.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            DriveButton(
                "Bibliothek",
                filled = false,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                onClick = { MediaCenterExit.open(context) },
            )
        }
        DriveButton(
            "Einstellungen",
            filled = false,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
            onClick = { context.startActivity(AutomotiveSetup.preferencesIntent(context.packageName)) },
        )
    }
}

@Composable
private fun Progress(playback: PlayerSnapshot) {
    val fraction = if (playback.durationMs > 0) {
        (playback.positionMs.toFloat() / playback.durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
            .height(12.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Track),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .background(HoppOrange),
        )
    }
}

/**
 * Eine Fläche, eine Kontur. Keine Schattenhöhe: die Headunit zeichnet Elevation sonst
 * als versetzte zweite Kante neben der gefüllten Fläche.
 */
@Composable
private fun DriveButton(
    label: String,
    filled: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(AutoDrive.MIN_TOUCH_DP.dp),
        shape = ButtonShape,
        color = if (filled) HoppOrange else Color.Transparent,
        contentColor = if (filled) Ink else HoppOrange,
        border = if (filled) null else BorderStroke(3.dp, HoppOrange),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(
                label,
                fontSize = AutoDrive.LABEL_SP.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Cover(uri: String?) {
    if (uri.isNullOrBlank()) {
        Surface(
            modifier = Modifier.size(240.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
        ) {}
    } else {
        AsyncImage(
            model = uri,
            contentDescription = "Cover",
            modifier = Modifier.size(240.dp).clip(RoundedCornerShape(28.dp)),
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
