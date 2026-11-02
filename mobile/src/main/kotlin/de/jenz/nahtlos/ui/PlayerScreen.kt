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
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import de.jenz.nahtlos.model.Chapter
import de.jenz.nahtlos.model.Person
import de.jenz.nahtlos.model.TranscriptLink
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

    private fun load(episodeKey: String) {
        viewModelScope.launch {
            val episode = feeds.getEpisode(episodeKey) ?: return@launch
            _details.value = EpisodeDetails(
                description = episode.description,
                chapters = episode.chapters(),
                transcripts = episode.transcripts(),
                persons = episode.persons(),
            )
            val remote = feeds.ensureChapters(episodeKey)
            if (remote.isNotEmpty()) _details.value = _details.value.copy(chapters = remote)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(onBack: () -> Unit, viewModel: PlayerViewModel = hiltViewModel()) {
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val details by viewModel.details.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Player") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
                        "Wähle eine Folge. Die Position kommt vom letzten Gerät, sobald der Sync-Server antwortet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    if (details.persons.isNotEmpty()) {
                        Text(
                            details.persons.joinToString { person ->
                                if (person.role.isNullOrBlank()) person.name else "${person.name} · ${person.role}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    SeekRow(playback.positionMs, playback.durationMs, viewModel::seekTo)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = viewModel::seekBack) {
                            Text("−15", style = MaterialTheme.typography.titleMedium)
                        }
                        FilledIconButton(onClick = viewModel::toggle, modifier = Modifier.size(72.dp).padding(horizontal = 12.dp)) {
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
                        IconButton(onClick = viewModel::seekForward) {
                            Icon(Icons.Filled.Forward30, contentDescription = "30 Sekunden vor")
                        }
                    }
                    playback.error?.let { message ->
                        Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                    }
                    if (details.chapters.isNotEmpty()) {
                        Text("Kapitel", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
                        details.chapters.forEach { chapter ->
                            val current = playback.positionMs >= chapter.startMs
                            Text(
                                "${formatClock(chapter.startMs)}  ${chapter.title}",
                                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.seekTo(chapter.startMs) }
                                    .padding(vertical = 8.dp),
                            )
                        }
                    }
                    details.transcripts.forEach { transcript ->
                        TextButton(onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(transcript.url))
                            runCatching { context.startActivity(intent) }
                        }) {
                            Text("Transkript öffnen" + (transcript.language?.let { " ($it)" } ?: ""))
                        }
                    }
                    if (details.description.isNotBlank()) {
                        Text(details.description, modifier = Modifier.padding(top = 12.dp, bottom = 32.dp))
                    }
                }
            }
        }
    }
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
