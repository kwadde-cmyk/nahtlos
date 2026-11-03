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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Upload
import androidx.compose.runtime.rememberCoroutineScope
import android.net.Uri
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
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import dagger.hilt.android.lifecycle.HiltViewModel
import de.jenz.nahtlos.core.db.ContinueRow
import de.jenz.nahtlos.core.db.PodcastEntity
import de.jenz.nahtlos.core.feed.FeedRefresh
import de.jenz.nahtlos.core.feed.FeedRefreshWork
import de.jenz.nahtlos.core.feed.FeedRepository
import de.jenz.nahtlos.core.playback.PlayerConnection
import de.jenz.nahtlos.core.playback.PlayerSnapshot
import de.jenz.nahtlos.core.sync.SyncRepository
import de.jenz.nahtlos.core.sync.SyncStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val feeds: FeedRepository,
    private val sync: SyncRepository,
    private val player: PlayerConnection,
    private val feedRefresh: FeedRefreshWork,
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
            try {
                val podcast = feeds.subscribe(url, markNeedsSync = true)
                sync.pushSubscription(podcast.feedUrl)
                _subscribe.value = SubscribeState.Idle
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
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

    fun importOpml(xml: String, onDone: (String) -> Unit) {
        viewModelScope.launch {
            if (xml.isBlank()) {
                onDone("Die Datei ist leer")
                return@launch
            }
            try {
                val count = sync.importOpml(xml)
                val waiting = feeds.pendingRefresh().size
                onDone(
                    when {
                        count == 0 -> "Keine Feeds in der Datei"
                        waiting == 0 -> "$count Feeds übernommen"
                        else -> "$count Feeds übernommen, lade Folgen…"
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                onDone(error.message ?: "OPML konnte nicht gelesen werden")
            }
        }
    }

    fun retryFailed() {
        viewModelScope.launch {
            feeds.retryFailed()
            feedRefresh.schedule()
        }
    }

    fun retryFeed(id: String) {
        viewModelScope.launch {
            feeds.retryOne(id)
            feedRefresh.schedule()
        }
    }

    suspend fun exportOpml(): String = feeds.exportOpml()
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
    onOpenPlaylist: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val podcasts by viewModel.podcasts.collectAsStateWithLifecycle()
    val continueRows by viewModel.continueListening.collectAsStateWithLifecycle()
    val status by viewModel.syncStatus.collectAsStateWithLifecycle()
    val subscribe by viewModel.subscribe.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var banner by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val importOpml = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                banner = error.message ?: "Die Datei konnte nicht gelesen werden"
                return@launch
            }
            viewModel.importOpml(text) { banner = it }
        }
    }
    val exportOpml = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/xml")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val xml = viewModel.exportOpml()
            context.contentResolver.openOutputStream(uri)?.use { it.write(xml.toByteArray()) }
            banner = "OPML exportiert"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nahtlos") },
                actions = {
                    IconButton(onClick = onOpenPlaylist) {
                        Icon(Icons.Filled.QueueMusic, contentDescription = "Playlist")
                    }
                    IconButton(onClick = { importOpml.launch(arrayOf("text/*", "application/xml", "application/octet-stream")) }) {
                        Icon(Icons.Filled.Add, contentDescription = "OPML importieren")
                    }
                    IconButton(onClick = { exportOpml.launch("nahtlos.opml") }) {
                        Icon(Icons.Filled.Upload, contentDescription = "OPML exportieren")
                    }
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
            banner?.let { text ->
                Text(text, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
            RefreshBanner(podcasts, onRetryFailed = viewModel::retryFailed)
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
                    onRetryFeed = viewModel::retryFeed,
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
private fun RefreshBanner(podcasts: List<PodcastEntity>, onRetryFailed: () -> Unit) {
    val waiting = podcasts.count {
        it.refreshStatus == FeedRefresh.PENDING || it.refreshStatus == FeedRefresh.LOADING
    }
    val failed = podcasts.count { it.refreshStatus == FeedRefresh.FAILED }
    if (podcasts.isEmpty() || (waiting == 0 && failed == 0)) return
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        if (waiting > 0) {
            val done = podcasts.size - waiting
            Text("Folgen laden… $done/${podcasts.size}", style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(
                progress = { done.toFloat() / podcasts.size.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
        if (failed > 0) {
            Text(
                if (failed == 1) "1 Feed konnte nicht geladen werden" else "$failed Feeds konnten nicht geladen werden",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 6.dp),
            )
            TextButton(onClick = onRetryFailed) { Text("Erneut versuchen") }
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
    onRetryFeed: (String) -> Unit,
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
            PodcastRow(
                podcast,
                onClick = { onOpenPodcast(podcast.id) },
                onRetry = if (podcast.refreshStatus == FeedRefresh.FAILED) {
                    { onRetryFeed(podcast.id) }
                } else {
                    null
                },
            )
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
fun PodcastRow(podcast: PodcastEntity, onClick: () -> Unit, onRetry: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(podcast.imageUrl, podcast.title, Modifier.size(56.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(podcast.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val failed = podcast.refreshStatus == FeedRefresh.FAILED
            val subtitle = when (podcast.refreshStatus) {
                FeedRefresh.PENDING, FeedRefresh.LOADING -> "Folgen werden geladen"
                FeedRefresh.FAILED -> podcast.refreshError ?: "Feed konnte nicht geladen werden"
                else -> podcast.author ?: podcast.description
            }
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (onRetry != null) {
            TextButton(onClick = onRetry) { Text("Erneut") }
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
        val context = LocalContext.current
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(url)
                .size(256)
                .memoryCacheKey(url)
                .diskCacheKey(url)
                .crossfade(false)
                .build(),
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
