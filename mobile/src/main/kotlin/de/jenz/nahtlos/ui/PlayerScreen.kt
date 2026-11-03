package de.jenz.nahtlos.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.jenz.nahtlos.core.db.chapters
import de.jenz.nahtlos.core.db.persons
import de.jenz.nahtlos.core.db.transcripts
import de.jenz.nahtlos.core.feed.FeedRepository
import de.jenz.nahtlos.core.playback.PlayerConnection
import de.jenz.nahtlos.core.db.extras
import de.jenz.nahtlos.core.db.funding
import de.jenz.nahtlos.core.db.persons
import de.jenz.nahtlos.core.db.valueRecipients
import de.jenz.nahtlos.model.AlternateSource
import de.jenz.nahtlos.model.PodLicense
import de.jenz.nahtlos.model.PodLocation
import de.jenz.nahtlos.model.PodTxt
import de.jenz.nahtlos.model.RemoteItem
import de.jenz.nahtlos.model.Trailer
import de.jenz.nahtlos.model.Chapter
import de.jenz.nahtlos.model.FundingLink
import de.jenz.nahtlos.model.Person
import de.jenz.nahtlos.model.Soundbite
import de.jenz.nahtlos.model.TranscriptCue
import de.jenz.nahtlos.model.TranscriptLink
import de.jenz.nahtlos.model.ValueRecipient
import de.jenz.nahtlos.model.ValueTimeSplit
import de.jenz.nahtlos.core.wallet.WalletViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EpisodeDetails(
    val description: String = "",
    val chapters: List<Chapter> = emptyList(),
    val transcripts: List<TranscriptLink> = emptyList(),
    val persons: List<Person> = emptyList(),
    val html: String = "",
    val cues: List<TranscriptCue> = emptyList(),
    val soundbites: List<Soundbite> = emptyList(),
    val value: List<ValueRecipient> = emptyList(),
    val valueSuggested: String? = null,
    val episodeValue: List<ValueRecipient> = emptyList(),
    val timeSplits: List<ValueTimeSplit> = emptyList(),
    val channelValue: List<ValueRecipient> = emptyList(),
    val feedUrl: String? = null,
    val episodeGuid: String? = null,
    val episodeTitle: String = "",
    val podcastTitle: String = "",
    val funding: List<FundingLink> = emptyList(),
    val alternates: List<AlternateSource> = emptyList(),
    val location: PodLocation? = null,
    val license: PodLicense? = null,
    val txt: List<PodTxt> = emptyList(),
    val trailers: List<Trailer> = emptyList(),
    val podroll: List<RemoteItem> = emptyList(),
    val locked: Boolean = false,
    val podcastGuid: String? = null,
    val live: Boolean = false,
    val season: Int? = null,
    val episodeNumber: String? = null,
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val player: PlayerConnection,
    private val feeds: FeedRepository,
) : ViewModel() {
    val playback = player.state
    private val _details = MutableStateFlow(EpisodeDetails())
    val details = _details.asStateFlow()
    init {
        player.connect()
        val requested = savedState.get<String>("episode")
        if (!requested.isNullOrBlank()) player.play(requested)
        viewModelScope.launch {
            player.state.map { it.episodeKey }.distinctUntilChanged().collect { key ->
                if (!key.isNullOrBlank()) load(key)
            }
        }
    }

    fun toggle() = player.toggle()
    fun seekTo(positionMs: Long) = player.seekTo(positionMs)
    fun seekBack() = player.seekBack()
    fun seekForward() = player.seekForward()
    fun nudgeSpeed(direction: Int) = player.nudgeSpeed(direction)
    fun toggleSilence() = player.toggleSilence()
    fun toggleBoost() = player.toggleBoost()
    fun sleepForMinutes(minutes: Int) = player.sleepForMinutes(minutes)
    fun sleepAtEnd() = player.sleepAtEnd()
    fun moreIntro() = player.moreIntro()
    fun moreOutro() = player.moreOutro()
    fun subscribe(url: String) {
        viewModelScope.launch { runCatching { feeds.subscribe(url, markNeedsSync = true) } }
    }
    fun useEnclosure(url: String) {
        val key = playback.value.episodeKey ?: return
        viewModelScope.launch {
            feeds.useEnclosure(key, url)
            player.play(key)
        }
    }

    private fun load(episodeKey: String) {
        viewModelScope.launch {
            val episode = feeds.getEpisode(episodeKey) ?: return@launch
            val extra = episode.extras()
            val podcast = feeds.getPodcast(episode.podcastId)
            val show = podcast?.extras()
            _details.value = EpisodeDetails(
                description = episode.description,
                chapters = episode.chapters(),
                transcripts = episode.transcripts(),
                persons = episode.persons().ifEmpty { podcast?.persons().orEmpty() },
                html = extra.descriptionHtml,
                soundbites = extra.soundbites,
                value = extra.value.ifEmpty { podcast?.valueRecipients().orEmpty() },
                valueSuggested = extra.valueSuggested ?: show?.valueSuggested,
                episodeValue = extra.value,
                timeSplits = extra.valueTimeSplits,
                channelValue = podcast?.valueRecipients().orEmpty(),
                feedUrl = podcast?.feedUrl,
                episodeGuid = episode.guid,
                episodeTitle = episode.title,
                podcastTitle = podcast?.title.orEmpty(),
                funding = podcast?.funding() ?: emptyList(),
                alternates = extra.alternates,
                location = extra.location ?: show?.location,
                license = extra.license ?: show?.license,
                txt = extra.txt.ifEmpty { show?.txt.orEmpty() },
                trailers = show?.trailers.orEmpty(),
                podroll = show?.podroll.orEmpty(),
                locked = podcast?.locked == true,
                podcastGuid = podcast?.podcastGuid,
                live = extra.live,
                season = extra.season,
                episodeNumber = extra.episodeNumber,
            )
            val remote = feeds.ensureChapters(episodeKey)
            if (remote.isNotEmpty()) _details.value = _details.value.copy(chapters = remote)
            val cues = feeds.ensureTranscript(episodeKey)
            if (cues.isNotEmpty()) _details.value = _details.value.copy(cues = cues)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(onBack: () -> Unit, viewModel: PlayerViewModel = hiltViewModel()) {
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val details by viewModel.details.collectAsStateWithLifecycle()
    val walletModel: WalletViewModel = hiltViewModel()
    val wallet by walletModel.state.collectAsStateWithLifecycle()
    val boostResult by walletModel.result.collectAsStateWithLifecycle()
    val boostError by walletModel.error.collectAsStateWithLifecycle()
    var boostOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Player") },
                navigationIcon = {
                    LabeledIconButton(label = "Zurück", onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            if (playback.episodeKey == null && playback.title.isBlank()) {
                Column(Modifier.padding(24.dp)) {
                    Text("Nichts in der Wiedergabe.", style = MaterialTheme.typography.titleLarge)
                    Text(
                        playback.error
                            ?: "Wähle eine Folge. Die Wiedergabe startet lokal, eine neuere Position vom anderen Gerät wird danach übernommen.",
                        color = if (playback.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .widthIn(max = 640.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                ) {
                    Artwork(
                        playback.artworkUri,
                        playback.title,
                        Modifier
                            .size(240.dp)
                            .clip(RoundedCornerShape(28.dp))
                            .align(Alignment.CenterHorizontally),
                    )
                    Spacer(Modifier.height(20.dp))
                    Text(playback.title.ifBlank { "Folge" }, style = MaterialTheme.typography.headlineSmall)
                    if (playback.artist.isNotBlank()) {
                        Text(playback.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val currentChapter = details.chapters.lastOrNull { playback.positionMs >= it.startMs }
                    if (currentChapter != null) {
                        Text(currentChapter.title, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                    }
                    FeatureRow(details)
                    if (details.persons.isNotEmpty()) {
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            details.persons.take(6).forEach { person ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Artwork(person.img, person.name, Modifier.size(48.dp))
                                    Text(person.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    SeekRow(playback.positionMs, playback.durationMs, viewModel::seekTo)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { viewModel.nudgeSpeed(-1) }) { Text("−0,1×") }
                        Text("${"%.1f".format(playback.speed)}×", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { viewModel.nudgeSpeed(1) }) { Text("+0,1×") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        TextButton(onClick = viewModel::toggleSilence) { Text("Stille") }
                        TextButton(onClick = viewModel::toggleBoost) { Text("Lauter") }
                        TextButton(onClick = { viewModel.sleepForMinutes(15) }) { Text("Schlaf 15") }
                        TextButton(onClick = viewModel::sleepAtEnd) { Text("Bis Ende") }
                        TextButton(onClick = viewModel::moreIntro) { Text("Intro +15s") }
                        TextButton(onClick = viewModel::moreOutro) { Text("Outro +15s") }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconTextButton(label = "Zurück", icon = Icons.Filled.Replay, onClick = viewModel::seekBack)
                        LabeledFilledIconButton(
                            label = if (playback.isPlaying) "Pause" else "Wiedergabe",
                            onClick = viewModel::toggle,
                            modifier = Modifier.size(72.dp).padding(horizontal = 12.dp),
                        ) {
                            if (playback.buffering) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(28.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 3.dp,
                                )
                            } else {
                                Icon(
                                    if (playback.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = if (playback.isPlaying) "Pause" else "Wiedergabe",
                                )
                            }
                        }
                        IconTextButton(label = "Vor", icon = Icons.AutoMirrored.Filled.Forward, onClick = viewModel::seekForward)
                    }
                    playback.error?.let { message ->
                        Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                    }
                    EpisodeDossier(
                        details = details,
                        positionMs = playback.positionMs,
                        onSeek = viewModel::seekTo,
                        onSubscribe = viewModel::subscribe,
                        onUseEnclosure = viewModel::useEnclosure,
                        onBoost = {
                            walletModel.clearResult()
                            boostOpen = true
                        },
                    )
                    if (boostOpen) {
                        BoostDialog(
                            podcastTitle = details.podcastTitle,
                            episodeTitle = details.episodeTitle,
                            feedUrl = details.feedUrl,
                            feedGuid = details.podcastGuid,
                            episodeGuid = details.episodeGuid,
                            positionMs = playback.positionMs,
                            episodeRecipients = details.episodeValue,
                            timeSplits = details.timeSplits,
                            channelRecipients = details.channelValue,
                            wallet = wallet,
                            busy = wallet.busy,
                            result = boostResult,
                            error = boostError,
                            onDismiss = { boostOpen = false },
                            onSend = walletModel::send,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HtmlNotes(html: String) {
    val color = MaterialTheme.colorScheme.onSurface
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = Modifier.padding(top = 12.dp, bottom = 32.dp),
        factory = { context ->
            android.widget.TextView(context).apply {
                movementMethod = android.text.method.LinkMovementMethod.getInstance()
            }
        },
        update = { view ->
            view.setTextColor(color.toArgb())
            view.text = androidx.core.text.HtmlCompat.fromHtml(html, androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT)
        },
    )
}

@Composable
private fun SeekRow(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var drag by remember { mutableFloatStateOf(0f) }
    val duration = durationMs.coerceAtLeast(1).toFloat()
    val shown = if (dragging) drag else positionMs.toFloat().coerceIn(0f, duration)
    LaunchedEffect(positionMs) {
        if (!dragging) drag = positionMs.toFloat()
    }
    Slider(
        value = shown,
        onValueChange = {
            dragging = true
            drag = it
        },
        onValueChangeFinished = {
            dragging = false
            onSeek(drag.toLong())
        },
        valueRange = 0f..duration,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatClock(shown.toLong()), style = MaterialTheme.typography.labelMedium)
        Text(if (durationMs > 0) formatClock(durationMs) else "–", style = MaterialTheme.typography.labelMedium)
    }
}
