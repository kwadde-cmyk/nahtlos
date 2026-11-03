package de.jenz.nahtlos.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.jenz.nahtlos.core.db.EpisodeRow
import de.jenz.nahtlos.core.db.PodcastEntity
import de.jenz.nahtlos.core.db.extras
import de.jenz.nahtlos.core.db.persons
import de.jenz.nahtlos.core.feed.FeedRefresh
import de.jenz.nahtlos.core.feed.FeedRepository
import de.jenz.nahtlos.core.library.EpisodeCommands
import de.jenz.nahtlos.core.library.Reversible
import de.jenz.nahtlos.core.playback.PlayerConnection
import de.jenz.nahtlos.core.playlist.PlaylistRepository
import de.jenz.nahtlos.core.sync.ServerSettings
import de.jenz.nahtlos.core.sync.SettingsStore
import de.jenz.nahtlos.core.sync.SyncRepository
import de.jenz.nahtlos.playback.SwipeAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class EpisodesViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val feeds: FeedRepository,
    private val sync: SyncRepository,
    private val player: PlayerConnection,
    private val commands: EpisodeCommands,
    playlists: PlaylistRepository,
    settingsStore: SettingsStore,
) : ViewModel() {
    private val podcastId: String = checkNotNull(savedState["podcastId"])
    val podcast = feeds.observePodcast(podcastId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val episodes = feeds.observeEpisodes(podcastId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    val settings = settingsStore.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), settingsStore.read())
    val playlistKeys = playlists.observe().map { playlist -> playlist.items.map { it.episodeKey }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
    val downloadKeys = playlists.observeDownloads()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
    private val _undo = MutableSharedFlow<Reversible>(extraBufferCapacity = 1)
    val undo = _undo.asSharedFlow()

    init {
        refresh(force = false)
    }

    fun refresh(force: Boolean) {
        viewModelScope.launch {
            _refreshing.value = true
            try {
                feeds.ensureFresh(podcastId, force)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _error.value = error.message
            }
            _refreshing.value = false
        }
    }

    fun play(episodeKey: String) {
        player.play(episodeKey)
    }

    fun act(action: SwipeAction, keys: List<String>) {
        viewModelScope.launch {
            commands.run(action, keys)?.let { _undo.emit(it) }
        }
    }

    fun toggle(action: SwipeAction, keys: List<String>) {
        viewModelScope.launch { commands.runToggled(action, keys)?.let { _undo.emit(it) } }
    }

    fun unsubscribe(onDone: () -> Unit) {
        viewModelScope.launch {
            val feedUrl = feeds.getPodcast(podcastId)?.feedUrl
            feeds.deleteLocal(podcastId)
            if (feedUrl != null) sync.markUnsubscribed(feedUrl)
            onDone()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpisodesScreen(
    onBack: () -> Unit,
    onOpenPlayer: (String) -> Unit,
    viewModel: EpisodesViewModel = hiltViewModel(),
) {
    val podcast by viewModel.podcast.collectAsStateWithLifecycle()
    val episodes by viewModel.episodes.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val playlistKeys by viewModel.playlistKeys.collectAsStateWithLifecycle()
    val downloadKeys by viewModel.downloadKeys.collectAsStateWithLifecycle()
    val selection = rememberSelection()
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    UndoEffect(viewModel.undo, snackbar)
    var confirmUnsubscribe by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(podcast?.title ?: "Folgen", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    LabeledIconButton(label = "Zurück", onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    if (selection.active) {
                        TextButton(onClick = { selection.all(episodes.map { it.episode.episodeKey }) }) { Text("Alle") }
                        TextButton(onClick = { selection.clear() }) { Text("Fertig") }
                    } else {
                        LabeledIconButton(label = "Feed aktualisieren", onClick = { viewModel.refresh(force = true) }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Feed aktualisieren")
                        }
                    }
                },
            )
        },
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) },
        bottomBar = {
            if (selection.active) {
                SelectionBar(
                    count = selection.keys.size,
                    onAll = { selection.all(episodes.map { it.episode.episodeKey }) },
                    onNone = { selection.clear() },
                    actions = run {
                        val played = episodes.filter { it.progressStatus == "COMPLETED" }.map { it.episode.episodeKey }.toSet()
                        listOf(
                            bulkLabel(SwipeAction.ADD_PLAYLIST, selection.keys, played, playlistKeys, downloadKeys) to {
                                viewModel.toggle(SwipeAction.ADD_PLAYLIST, selection.keys.toList()); selection.clear()
                            },
                            bulkLabel(SwipeAction.MARK_PLAYED, selection.keys, played, playlistKeys, downloadKeys) to {
                                viewModel.toggle(SwipeAction.MARK_PLAYED, selection.keys.toList()); selection.clear()
                            },
                            bulkLabel(SwipeAction.DOWNLOAD, selection.keys, played, playlistKeys, downloadKeys) to {
                                viewModel.toggle(SwipeAction.DOWNLOAD, selection.keys.toList()); selection.clear()
                            },
                        )
                    },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            when {
                podcast == null && refreshing -> Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) { CircularProgressIndicator() }
                podcast == null -> Text("Dieser Podcast ist nicht mehr abonniert.", modifier = Modifier.padding(24.dp))
                else -> EpisodeList(
                    podcast = podcast!!,
                    episodes = episodes,
                    refreshing = refreshing,
                    error = error,
                    onPlay = { key ->
                        if (selection.active) selection.toggle(key) else {
                            viewModel.play(key)
                            onOpenPlayer(key)
                        }
                    },
                    onLongPress = { key -> selection.start(key) },
                    selecting = selection.active,
                    selected = selection.keys,
                    swipeStart = settings.swipeStart,
                    swipeEnd = settings.swipeEnd,
                    playlistKeys = playlistKeys,
                    downloadKeys = downloadKeys,
                    onSwipe = { action, key -> viewModel.act(action, listOf(key)) },
                    onUnsubscribe = { confirmUnsubscribe = true },
                )
            }
        }
    }

    if (confirmUnsubscribe) {
        AlertDialog(
            onDismissRequest = { confirmUnsubscribe = false },
            title = { Text("Abo entfernen?") },
            text = { Text("Der Feed verschwindet auf diesem Gerät und, sobald das Netz da ist, auch auf dem Server.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnsubscribe = false
                    viewModel.unsubscribe(onBack)
                }) { Text("Entfernen") }
            },
            dismissButton = { TextButton(onClick = { confirmUnsubscribe = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun EpisodeList(
    podcast: PodcastEntity,
    episodes: List<EpisodeRow>,
    refreshing: Boolean,
    error: String?,
    onPlay: (String) -> Unit,
    onLongPress: (String) -> Unit,
    selecting: Boolean,
    selected: Set<String>,
    swipeStart: SwipeAction,
    swipeEnd: SwipeAction,
    playlistKeys: Set<String>,
    downloadKeys: Set<String>,
    onSwipe: (SwipeAction, String) -> Unit,
    onUnsubscribe: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.widthIn(max = 840.dp),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                GestureHint(Modifier)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Artwork(podcast.imageUrl, podcast.title, Modifier.size(72.dp))
                    Column(Modifier.padding(start = 14.dp)) {
                        Text(podcast.title, style = MaterialTheme.typography.headlineSmall)
                        val people = podcast.persons().joinToString { person ->
                            if (person.role.isNullOrBlank()) person.name else "${person.name} (${person.role})"
                        }
                        val byline = people.ifBlank { podcast.author.orEmpty() }
                        if (byline.isNotBlank()) {
                            Text(byline, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (podcast.description.isNotBlank()) {
                    Text(
                        podcast.description,
                        modifier = Modifier.padding(top = 12.dp),
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (refreshing) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                }
                if (error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        if (episodes.isEmpty() && !refreshing) {
            item {
                val message = when (podcast.refreshStatus) {
                    FeedRefresh.PENDING, FeedRefresh.LOADING -> "Folgen werden geladen."
                    FeedRefresh.FAILED -> podcast.refreshError ?: "Der Feed konnte nicht geladen werden."
                    else -> "Dieser Feed hat noch keine Folgen mit Audio. Sobald welche da sind: Wischen legt sie in die Playlist oder markiert sie, langes Drücken wählt mehrere."
                }
                Text(message, modifier = Modifier.padding(20.dp))
            }
        }
        items(episodes, key = { it.episode.episodeKey }) { row ->
            val key = row.episode.episodeKey
            val played = row.progressStatus == "COMPLETED"
            val resolvedStart = swipeStart.resolved(played, key in playlistKeys, key in downloadKeys)
            val resolvedEnd = swipeEnd.resolved(played, key in playlistKeys, key in downloadKeys)
            SwipeRow(
                start = resolvedStart,
                end = resolvedEnd,
                enabled = !selecting,
                onAction = { onSwipe(it, key) },
            ) {
                SelectableRow(
                    selected = key in selected,
                    selecting = selecting,
                    onClick = { onPlay(key) },
                    onLongPress = { onLongPress(key) },
                ) {
                    EpisodeRowItem(row)
                }
            }
        }
        item {
            TextButton(onClick = onUnsubscribe, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text("Abo entfernen")
            }
        }
    }
}

@Composable
private fun EpisodeRowItem(row: EpisodeRow) {
    val episode = row.episode
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        val extra = episode.extras()
        val badge = buildString {
            if (extra.live) append("Live")
            if (extra.season != null || !extra.episodeNumber.isNullOrBlank()) {
                if (isNotEmpty()) append(" · ")
                extra.season?.let { append("Staffel $it") }
                extra.episodeNumber?.let {
                    if (extra.season != null) append(" ")
                    append("Folge $it")
                }
            }
        }
        if (badge.isNotBlank()) {
            Text(badge, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
        }
        Text(episode.title, style = MaterialTheme.typography.titleMedium)
        val meta = listOfNotNull(
            formatDate(episode.publishedAt).takeIf { it.isNotBlank() },
            episode.durationMs?.takeIf { it > 0 }?.let { formatClock(it) },
        ).joinToString(" · ")
        if (meta.isNotBlank()) {
            Text(meta, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        val position = row.positionMs
        val duration = episode.durationMs ?: 0L
        if (position != null && position > 0 && row.progressStatus != "COMPLETED") {
            val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        }
    }
}
