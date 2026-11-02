package de.jenz.nahtlos.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import de.jenz.nahtlos.core.db.ContinueRow
import de.jenz.nahtlos.core.db.PodcastEntity
import de.jenz.nahtlos.core.feed.FeedRepository
import de.jenz.nahtlos.core.playback.PlayerConnection
import de.jenz.nahtlos.core.playback.PlayerSnapshot
import de.jenz.nahtlos.core.sync.SyncRepository
import de.jenz.nahtlos.core.sync.SyncStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val feeds: FeedRepository,
    private val sync: SyncRepository,
    private val player: PlayerConnection,
) : ViewModel() {
    val podcasts = feeds.observePodcasts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val continueListening = feeds.observeContinue().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val syncStatus = sync.status.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncStatus())
    val playerState = player.state
    private val _subscribe = MutableStateFlow<SubscribeState>(SubscribeState.Idle)
    val subscribe: StateFlow<SubscribeState> = _subscribe.asStateFlow()

    init {
        player.connect()
        viewModelScope.launch { sync.pullAndMerge() }
    }

    fun onForeground() {
        viewModelScope.launch { sync.pullAndMerge() }
    }

    fun subscribe(rawUrl: String) {
        val url = rawUrl.trim()
        if (url.isEmpty()) return
        viewModelScope.launch {
            _subscribe.value = SubscribeState.Loading
            runCatching { feeds.subscribe(url, markNeedsSync = true) }
                .onSuccess { podcast ->
                    sync.pushSubscription(podcast.feedUrl)
                    _subscribe.value = SubscribeState.Idle
                }
                .onFailure { error ->
                    _subscribe.value = SubscribeState.Error(error.message ?: "Feed konnte nicht gelesen werden")
                }
        }
    }

    fun dismissSubscribeError() {
        if (_subscribe.value is SubscribeState.Error) _subscribe.value = SubscribeState.Idle
    }

    fun play(episodeKey: String) {
        player.play(episodeKey)
    }

    fun togglePlayback() {
        player.toggle()
    }
}

sealed interface SubscribeState {
    data object Idle : SubscribeState
    data object Loading : SubscribeState
    data class Error(val message: String) : SubscribeState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenPodcast: (String) -> Unit,
    onOpenPlayer: (String?) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val podcasts by viewModel.podcasts.collectAsStateWithLifecycle()
    val continueRows by viewModel.continueListening.collectAsStateWithLifecycle()
    val status by viewModel.syncStatus.collectAsStateWithLifecycle()
    val subscribe by viewModel.subscribe.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nahtlos") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Einstellungen")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Filled.Add, contentDescription = "RSS-Feed hinzufügen")
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 840.dp),
        ) {
            StatusBanner(status) { viewModel.onForeground() }
            when {
                status.syncing && podcasts.isEmpty() && continueRows.isEmpty() -> LoadingState("Abos werden geholt …")
                podcasts.isEmpty() && continueRows.isEmpty() -> EmptyLibrary { showAdd = true }
                else -> LibraryList(
                    continueRows = continueRows,
                    podcasts = podcasts,
                    onContinue = { key ->
                        viewModel.play(key)
                        onOpenPlayer(key)
                    },
                    onOpenPodcast = onOpenPodcast,
                )
            }
        }
        }
    }

    if (showAdd) {
        AddFeedDialog(
            state = subscribe,
            onDismiss = {
                showAdd = false
                viewModel.dismissSubscribeError()
            },
            onSubmit = viewModel::subscribe,
        )
    }
}

@Composable
private fun StatusBanner(status: SyncStatus, onRetry: () -> Unit) {
    val text = when {
        !status.configured -> "Sync aus. Server-URL und Token stehen in den Einstellungen. Die Position bleibt lokal erhalten."
        status.lastError != null -> status.lastError
        status.pending > 0 -> "Offline: ${status.pending} Position${if (status.pending == 1) "" else "en"} wartet auf Netz."
        else -> null
    } ?: return
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (status.configured && status.lastError != null) {
            TextButton(onClick = onRetry) { Text("Erneut versuchen") }
        }
    }
}

@Composable
private fun LoadingState(label: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(label)
    }
}

@Composable
private fun EmptyLibrary(onAdd: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Noch kein Podcast", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Füge eine RSS-Adresse hinzu. Kapitel, Transkript-Link und GUID werden mitgelesen, die Wiedergabeposition geht mit aufs Auto.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onAdd) { Text("RSS-Feed eintragen") }
    }
}

@Composable
private fun LibraryList(
    continueRows: List<ContinueRow>,
    podcasts: List<PodcastEntity>,
    onContinue: (String) -> Unit,
    onOpenPodcast: (String) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
        if (continueRows.isNotEmpty()) {
            item {
                Text(
                    "Weitermachen",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(continueRows, key = { it.episodeKey }) { row ->
                        ContinueCard(row, onClick = { onContinue(row.episodeKey) })
                    }
                }
            }
        }
        item {
            Text(
                "Abos",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
        items(podcasts, key = { it.id }) { podcast ->
            PodcastRow(podcast, onClick = { onOpenPodcast(podcast.id) })
        }
    }
}

@Composable
private fun ContinueCard(row: ContinueRow, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(240.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Artwork(row.imageUrl, row.podcastTitle, Modifier.size(56.dp))
        Spacer(Modifier.height(10.dp))
        Text(row.episodeTitle, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
        Text(row.podcastTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        val fraction = if (row.durationMs > 0) (row.positionMs.toFloat() / row.durationMs).coerceIn(0f, 1f) else 0f
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        Text(
            "${formatClock(row.positionMs)} von ${if (row.durationMs > 0) formatClock(row.durationMs) else "?"}",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
fun PodcastRow(podcast: PodcastEntity, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(podcast.imageUrl, podcast.title, Modifier.size(56.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(podcast.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val subtitle = podcast.author ?: podcast.description
            if (subtitle.isNotBlank()) {
                Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun Artwork(url: String?, title: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    if (url.isNullOrBlank()) {
        Box(
            modifier = modifier
                .clip(shape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(title.take(1).uppercase(), color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    } else {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(shape),
        )
    }
}

@Composable
private fun AddFeedDialog(
    state: SubscribeState,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var sawLoading by remember { mutableStateOf(false) }
    val loading = state is SubscribeState.Loading
    LaunchedEffect(state) {
        if (state is SubscribeState.Loading) sawLoading = true
        if (sawLoading && state is SubscribeState.Idle) onDismiss()
    }
    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        title = { Text("RSS-Feed") },
        text = {
            Column {
                Text("Adresse des Podcast-Feeds, zum Beispiel https://example.com/feed.xml")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    label = { Text("Feed-URL") },
                    enabled = !loading,
                )
                if (state is SubscribeState.Error) {
                    Spacer(Modifier.height(8.dp))
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                }
                if (loading) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(url) },
                enabled = !loading && url.isNotBlank(),
            ) { Text("Abonnieren") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !loading) { Text("Schließen") }
        },
    )
}

@Composable
fun MiniPlayer(snapshot: PlayerSnapshot, onClick: () -> Unit, onToggle: () -> Unit) {
    if (snapshot.episodeKey.isNullOrBlank()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(snapshot.artworkUri, snapshot.title, Modifier.size(44.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(snapshot.title.ifBlank { "Wiedergabe" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                snapshot.artist,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        IconButton(onClick = onToggle) {
            Icon(
                if (snapshot.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (snapshot.isPlaying) "Pause" else "Wiedergabe",
            )
        }
    }
}
