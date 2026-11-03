package de.jenz.nahtlos.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.jenz.nahtlos.core.library.EpisodeCommands
import de.jenz.nahtlos.core.library.Reversible
import de.jenz.nahtlos.core.playlist.PlaylistRepository
import de.jenz.nahtlos.core.sync.SettingsStore
import de.jenz.nahtlos.playback.SwipeAction
import de.jenz.nahtlos.playlist.PlaylistSort
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@HiltViewModel
class PlaylistViewModel @Inject constructor(
    private val playlists: PlaylistRepository,
    private val commands: EpisodeCommands,
    settingsStore: SettingsStore,
) : ViewModel() {
    val settings = settingsStore.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), settingsStore.read())
    private val _undo = MutableSharedFlow<Reversible>(extraBufferCapacity = 1)
    val undo = _undo.asSharedFlow()
    val playlist = playlists.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), de.jenz.nahtlos.playlist.ListeningPlaylist())

    fun generate(sinceEpochMs: Long) {
        viewModelScope.launch { playlists.createFromDate(sinceEpochMs, playlist.value.sort) }
    }

    fun refresh() {
        viewModelScope.launch { playlists.refresh() }
    }

    fun toggleSort() {
        val next = if (playlist.value.sort == PlaylistSort.OLDEST_FIRST) PlaylistSort.NEWEST_FIRST else PlaylistSort.OLDEST_FIRST
        viewModelScope.launch { playlists.setSort(next) }
    }

    fun reorder(keys: List<String>) {
        viewModelScope.launch { playlists.reorder(keys) }
    }

    fun act(action: SwipeAction, keys: List<String>) {
        viewModelScope.launch { commands.run(action, keys)?.let { _undo.emit(it) } }
    }

    fun setRemoveWhenFinished(enabled: Boolean) {
        viewModelScope.launch { playlists.setRemoveWhenFinished(enabled) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(
    onBack: () -> Unit,
    onPlay: (String) -> Unit,
    viewModel: PlaylistViewModel = hiltViewModel(),
) {
    val playlist by viewModel.playlist.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val selection = rememberSelection()
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    UndoEffect(viewModel.undo, snackbar)
    var since by remember(playlist.sinceEpochMs) {
        mutableLongStateOf(if (playlist.sinceEpochMs > 0) playlist.sinceEpochMs else System.currentTimeMillis() - 7L * 86_400_000L)
    }
    var showPicker by remember { mutableStateOf(false) }
    val picker = rememberDatePickerState(initialSelectedDateMillis = since)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Playlist") },
                navigationIcon = {
                    LabeledIconButton(label = "Zurück", onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
            )
        },
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) },
        bottomBar = {
            if (selection.active) {
                SelectionBar(
                    count = selection.keys.size,
                    onAll = { selection.all(playlist.items.map { it.episodeKey }) },
                    onNone = { selection.clear() },
                    actions = listOf(
                        "Entfernen" to { viewModel.act(SwipeAction.REMOVE_PLAYLIST, selection.keys.toList()); selection.clear() },
                        "Gehört" to { viewModel.act(SwipeAction.MARK_PLAYED, selection.keys.toList()); selection.clear() },
                        "Ungehört" to { viewModel.act(SwipeAction.MARK_UNPLAYED, selection.keys.toList()); selection.clear() },
                        "Datei weg" to { viewModel.act(SwipeAction.DELETE_DOWNLOAD, selection.keys.toList()); selection.clear() },
                    ),
                )
            }
        },
    ) { padding ->
        val dragging = remember { mutableStateOf(false) }
        val pendingKeys = remember { mutableStateOf<List<String>?>(null) }
        val latestKeys = remember { mutableStateOf(playlist.items.map { it.episodeKey }) }
        latestKeys.value = playlist.items.map { it.episodeKey }
        val order = remember { mutableStateListOf<de.jenz.nahtlos.playlist.PlaylistEntry>() }
        if (order.isEmpty() && playlist.items.isNotEmpty() && pendingKeys.value == null && !dragging.value) {
            order.addAll(playlist.items)
        }
        LaunchedEffect(playlist.items) {
            val remote = playlist.items.map { it.episodeKey }
            val waiting = pendingKeys.value
            if (waiting != null && remote != waiting) return@LaunchedEffect
            pendingKeys.value = null
            if (dragging.value) return@LaunchedEffect
            order.clear()
            order.addAll(playlist.items)
        }
        val listState = rememberLazyListState()
        val reorderState = rememberReorderableLazyListState(listState) { from, to ->
            if (from.index in order.indices && to.index in order.indices) {
                order.add(to.index, order.removeAt(from.index))
            }
        }
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(
                    "Alle neuen Folgen der Abos seit einem Datum. Die Reihenfolge geht mit aufs Auto, und nach dem Ende startet die nächste Folge.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { showPicker = true }) {
                    Text("Seit ${formatDate(since)}")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { viewModel.generate(since) }) { Text("Erzeugen") }
                    TextButton(onClick = viewModel::refresh) { Text("Neue Folgen dazu") }
                    TextButton(onClick = viewModel::toggleSort) {
                        Text(if (playlist.sort == PlaylistSort.OLDEST_FIRST) "Älteste zuerst" else "Neueste zuerst")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Fertige Folgen aus der Playlist nehmen", modifier = Modifier.weight(1f))
                    Switch(
                        checked = playlist.removeWhenFinished,
                        onCheckedChange = viewModel::setRemoveWhenFinished,
                    )
                }
                GestureHint(Modifier.padding(top = 4.dp))
                if (order.isEmpty()) {
                    Text(
                        "Noch keine Folgen. Wähle ein Datum und tippe auf „Erzeugen“, oder öffne einen Podcast und wische eine Folge zur Playlist. Langes Drücken wählt mehrere.",
                        modifier = Modifier.padding(top = 12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "Am Griff links ziehen sortiert. Wischen nimmt die Folge heraus, langes Drücken wählt mehrere.",
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                items(order, key = { it.episodeKey }) { item ->
                    ReorderableItem(reorderState, key = item.episodeKey, enabled = !selection.active) { _ ->
                        SwipeRow(
                            start = settings.swipeStart,
                            end = settings.swipeEnd,
                            enabled = !selection.active && !dragging.value,
                            onAction = { viewModel.act(it, listOf(item.episodeKey)) },
                        ) {
                            SelectableRow(
                                selected = item.episodeKey in selection.keys,
                                selecting = selection.active,
                                onClick = {
                                    if (selection.active) selection.toggle(item.episodeKey) else onPlay(item.episodeKey)
                                },
                                onLongPress = { selection.start(item.episodeKey) },
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Filled.DragIndicator,
                                        contentDescription = "Reihenfolge ändern",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .draggableHandle(
                                                enabled = !selection.active,
                                                onDragStarted = { dragging.value = true },
                                                onDragStopped = {
                                                    val keys = order.map { it.episodeKey }
                                                    dragging.value = false
                                                    if (keys == latestKeys.value) {
                                                        pendingKeys.value = null
                                                    } else {
                                                        pendingKeys.value = keys
                                                        viewModel.reorder(keys)
                                                    }
                                                },
                                            )
                                            .size(48.dp)
                                            .padding(12.dp),
                                    )
                                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                        Text(item.title.ifBlank { item.episodeKey }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            listOf(item.podcastTitle, formatDate(item.publishedAt)).filter { it.isNotBlank() }.joinToString(" · "),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    LabeledIconButton(label = "Abspielen", onClick = { onPlay(item.episodeKey) }) {
                                        Icon(Icons.Filled.PlayArrow, contentDescription = "Abspielen")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (showPicker) {
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { selected ->
                        val date = Instant.ofEpochMilli(selected).atZone(ZoneId.of("UTC")).toLocalDate()
                        since = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    }
                    showPicker = false
                }) { Text("Übernehmen") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Abbrechen") } },
        ) {
            DatePicker(state = picker)
        }
    }
}
