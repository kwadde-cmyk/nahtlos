package de.jenz.nahtlos.automotive

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.hilt.navigation.compose.hiltViewModel
import de.jenz.nahtlos.core.Payments
import de.jenz.nahtlos.core.db.extras
import de.jenz.nahtlos.core.db.valueRecipients
import de.jenz.nahtlos.core.feed.FeedRepository
import de.jenz.nahtlos.core.nostr.NostrViewModel
import de.jenz.nahtlos.core.playback.AutomotiveSetup
import de.jenz.nahtlos.core.playback.CarControls
import de.jenz.nahtlos.core.playback.PlayerConnection
import de.jenz.nahtlos.core.playback.PlayerSnapshot
import de.jenz.nahtlos.core.wallet.BoostService
import de.jenz.nahtlos.model.Chapter
import de.jenz.nahtlos.model.ValueRecipient
import de.jenz.nahtlos.nostr.PlainNotes
import de.jenz.nahtlos.sync.BoostSync
import de.jenz.nahtlos.wallet.BoostText
import de.jenz.nahtlos.wallet.SecretScrub
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val HoppOrange = Color(0xFFFF7A1A)
private val Ink = Color(0xFF1A0E00)
private val Track = Color(0xFF2A241C)
private val ButtonShape = RoundedCornerShape(22.dp)

private enum class CarMenu { Shownotes, Chapters, People, Support, Boost }

@AndroidEntryPoint
class NowPlayingActivity : ComponentActivity() {
    @Inject lateinit var player: PlayerConnection
    @Inject lateinit var boosts: BoostService
    @Inject lateinit var feeds: FeedRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AutoTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                        NowPlaying(player, boosts, feeds)
                    }
                }
            }
        }
    }
}

@Composable
private fun NowPlaying(player: PlayerConnection, boosts: BoostService, feeds: FeedRepository) {
    val playback by player.state.collectAsStateWithLifecycle()
    val wallet by boosts.state.collectAsStateWithLifecycle()
    val driving by rememberDriving()
    val scope = rememberCoroutineScope()
    var comment by remember(playback.episodeKey) { mutableStateOf("") }
    val nostr: NostrViewModel = hiltViewModel()
    val nostrUi by nostr.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var notes by remember { mutableStateOf("") }
    var episodeGuid by remember { mutableStateOf<String?>(null) }
    var podcastGuid by remember { mutableStateOf<String?>(null) }
    var showTitle by remember { mutableStateOf("") }
    var page by remember { mutableStateOf<String?>(null) }
    var zapAddress by remember { mutableStateOf<String?>(null) }
    var openMenu by remember(playback.episodeKey) { mutableStateOf<CarMenu?>(null) }
    var chapters by remember(playback.episodeKey) { mutableStateOf<List<Chapter>>(emptyList()) }
    LaunchedEffect(playback.episodeKey) {
        val key = playback.episodeKey
        if (key.isNullOrBlank()) {
            notes = ""
            episodeGuid = null
            return@LaunchedEffect
        }
        val episode = feeds.getEpisode(key)
        val podcast = episode?.let { feeds.getPodcast(it.podcastId) }
        val extra = episode?.extras()
        notes = PlainNotes.from(extra?.descriptionHtml, episode?.description)
        episodeGuid = episode?.guid?.trim()?.takeIf { it.isNotEmpty() }
        podcastGuid = podcast?.podcastGuid
        showTitle = podcast?.title.orEmpty()
        page = podcast?.link?.takeIf { it.startsWith("http") }
        zapAddress = lightningAddress(extra?.value.orEmpty(), podcast?.valueRecipients().orEmpty())
        episodeGuid?.let { nostr.load(it) }
        chapters = runCatching { feeds.ensureChapters(key) }.getOrDefault(emptyList())
    }
    var sending by remember { mutableStateOf(false) }
    var banner by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(player) { player.connect() }
    LaunchedEffect(Unit) {
        nostr.refresh()
        boosts.refresh()
    }
    LaunchedEffect(boosts) {
        boosts.outcome.collect { text ->
            banner = text
            sending = false
        }
    }
    val something = playback.title.isNotBlank() || !playback.episodeKey.isNullOrBlank()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val coverEdge = AutoDrive.coverEdgeDp(maxHeight.value.toInt(), driving).dp
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton("Bibliothek") { MediaCenterExit.open(context) }
                IconButton("Einstellungen") {
                    context.startActivity(AutomotiveSetup.preferencesIntent(context.packageName))
                }
            }
            val headline = nostrUi.notice.ifBlank { banner }
            headline?.let { text ->
                Text(
                    text,
                    fontSize = AutoDrive.BODY_SP.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (
                        text.startsWith(BoostSync.OUTCOME_FAIL) ||
                        text.startsWith("Eine Zahlung") ||
                        text.startsWith("Zap nicht") ||
                        text.startsWith("Zap unklar") ||
                        text.contains("Tagesbudget")
                    ) {
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
            } else {
                Cover(playback.artworkUri, coverEdge)
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
                TransportRow(Modifier.padding(top = 18.dp)) {
                    DriveButton("−15s", filled = false, onClick = player::seekBack)
                    DriveButton(
                        if (playback.isPlaying) "Pause" else "Play",
                        filled = true,
                        onClick = player::toggle,
                    )
                    DriveButton("+15s", filled = false, onClick = player::seekForward)
                }
                val showLike = nostrUi.loggedIn && !episodeGuid.isNullOrBlank()
                val showZap = showLike && Payments.enabled && !zapAddress.isNullOrBlank()
                Row(
                    Modifier.fillMaxWidth().padding(top = 16.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showLike) {
                        DriveButton("Like", filled = false, onClick = { nostr.likeEpisode(episodeGuid!!, podcastGuid) })
                    }
                    if (showZap) {
                        DriveButton(
                            nostrUi.zap.label(episodeGuid!!, "Zap"),
                            filled = true,
                            enabled = !nostrUi.busy,
                            onClick = {
                                nostr.zap(
                                    target = episodeGuid!!,
                                    address = zapAddress!!,
                                    sats = nostr.defaultSats(),
                                    podcast = showTitle,
                                    episode = playback.title,
                                    episodeGuid = episodeGuid,
                                    podcastGuid = podcastGuid,
                                )
                            },
                        )
                    }
                    MenuChip("Shownotes", openMenu == CarMenu.Shownotes) { openMenu = if (openMenu == CarMenu.Shownotes) null else CarMenu.Shownotes }
                    MenuChip("Kapitel", openMenu == CarMenu.Chapters) { openMenu = if (openMenu == CarMenu.Chapters) null else CarMenu.Chapters }
                    MenuChip("Personen", openMenu == CarMenu.People) { openMenu = if (openMenu == CarMenu.People) null else CarMenu.People }
                    MenuChip("Unterstützen", openMenu == CarMenu.Support) { openMenu = if (openMenu == CarMenu.Support) null else CarMenu.Support }
                    if (Payments.enabled) {
                        MenuChip("Boost", openMenu == CarMenu.Boost) { openMenu = if (openMenu == CarMenu.Boost) null else CarMenu.Boost }
                    }
                }
                when (openMenu) {
                    CarMenu.Shownotes -> {
                        if (notes.isNotBlank()) {
                            Text(notes, fontSize = AutoDrive.BODY_SP.sp, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
                        } else {
                            Text("Keine Shownotes.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp))
                        }
                    }
                    CarMenu.Chapters -> {
                        if (chapters.isEmpty()) {
                            Text("Keine Kapitel.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp))
                        } else {
                            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                chapters.sortedBy { it.startMs }.forEach { chapter ->
                                    val later = chapters.any { it.startMs > chapter.startMs && playback.positionMs >= it.startMs }
                                    val current = playback.positionMs >= chapter.startMs && !later
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable { player.seekTo(chapter.startMs) }
                                            .padding(vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            clock(chapter.startMs),
                                            fontSize = AutoDrive.BODY_SP.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (current) HoppOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.width(90.dp),
                                        )
                                        Text(
                                            chapter.title.ifBlank { "Kapitel" },
                                            fontSize = AutoDrive.BODY_SP.sp,
                                            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                                            color = if (current) HoppOrange else MaterialTheme.colorScheme.onSurface,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    CarMenu.People -> {
                        Text("Personen aus dem Feed erscheinen hier.", modifier = Modifier.padding(top = 16.dp))
                    }
                    CarMenu.Support -> {
                        Text("Unterstützen: Funding-Links und Value4Value.", modifier = Modifier.padding(top = 16.dp))
                    }
                    CarMenu.Boost -> {
                        if (Payments.enabled) {
                            BoostCommentField(comment, wallet.phrases) { comment = it }
                            DriveButton(
                                if (sending) "…" else "Senden",
                                filled = true,
                                enabled = !sending,
                                modifier = Modifier.padding(top = 12.dp),
                                onClick = {
                                    if (!BoostSync.acceptTap(sending)) return@DriveButton
                                    val key = playback.episodeKey
                                    if (key.isNullOrBlank()) {
                                        banner = "Boost nicht gesendet. Keine Folge in der Wiedergabe."
                                        return@DriveButton
                                    }
                                    sending = true
                                    banner = null
                                    val note = comment
                                    val position = playback.positionMs
                                    scope.launch {
                                        val text = try {
                                            boosts.outcomeText(boosts.quickBoost(key, position, note))
                                        } catch (error: CancellationException) {
                                            throw error
                                        } catch (error: Exception) {
                                            CarControls.boostNotice(SecretScrub.message(error.message))
                                        }
                                        banner = text
                                        sending = false
                                    }
                                },
                            )
                        }
                    }
                    null -> {}
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
                CarComments(
                    parked = !driving,
                    episodeGuid = episodeGuid,
                    podcastGuid = podcastGuid,
                    showTitle = showTitle,
                    episodeTitle = playback.title,
                    page = page,
                    nostr = nostr,
                )
            }
        }
    }
}

@Composable
private fun IconButton(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(56.dp).padding(4.dp),
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        contentColor = HoppOrange,
        border = BorderStroke(2.dp, HoppOrange),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label.take(1), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun MenuChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.height(AutoDrive.BUTTON_HEIGHT_DP.dp).width(140.dp),
        shape = ButtonShape,
        color = if (selected) HoppOrange else Color.Transparent,
        contentColor = if (selected) Ink else HoppOrange,
        border = if (selected) null else BorderStroke(3.dp, HoppOrange),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, fontSize = AutoDrive.LABEL_SP.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun TransportRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
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

@Composable
private fun DriveButton(
    label: String,
    filled: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    buttonWidth: Dp = AutoDrive.BUTTON_WIDTH_DP.dp,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .width(buttonWidth)
            .height(AutoDrive.BUTTON_HEIGHT_DP.dp),
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
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Cover(uri: String?, edge: Dp) {
    if (uri.isNullOrBlank()) {
        Surface(
            modifier = Modifier.size(edge),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
        ) {}
    } else {
        AsyncImage(
            model = uri,
            contentDescription = "Cover",
            modifier = Modifier.size(edge).clip(RoundedCornerShape(28.dp)),
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
private fun BoostCommentField(
    text: String,
    phrases: List<String>,
    onText: (String) -> Unit,
) {
    OutlinedTextField(
        value = text,
        onValueChange = { onText(it.take(BoostText.MESSAGE_MAX)) },
        label = { Text("Kommentar zum Boost") },
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = AutoDrive.BODY_SP.sp),
    )
    Text(
        "Optional. Geht mit dem Boost an die Empfänger.",
        fontSize = AutoDrive.BODY_SP.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
    if (phrases.isNotEmpty() || text.isNotBlank()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (text.isNotBlank()) {
                PhraseChip("Ohne") { onText("") }
            }
            phrases.forEach { phrase ->
                PhraseChip(phrase) { onText(phrase) }
            }
        }
    }
}

@Composable
private fun PhraseChip(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.height(AutoDrive.MIN_TOUCH_DP.dp),
        shape = RoundedCornerShape(20.dp),
        color = Color.Transparent,
        contentColor = HoppOrange,
        border = BorderStroke(3.dp, HoppOrange),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun lightningAddress(episode: List<ValueRecipient>, channel: List<ValueRecipient>): String? =
    (episode + channel).firstOrNull { recipient ->
        val address = recipient.address?.trim().orEmpty()
        !recipient.fee && '@' in address
    }?.address?.trim()

private fun clock(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
