package de.jenz.nahtlos.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.jenz.nahtlos.core.db.DownloadRow
import de.jenz.nahtlos.core.library.EpisodeCommands
import de.jenz.nahtlos.core.playlist.PlaylistRepository
import de.jenz.nahtlos.core.library.Reversible
import de.jenz.nahtlos.core.playlist.DownloadStore
import de.jenz.nahtlos.playback.SwipeAction
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    downloads: DownloadStore,
    private val commands: EpisodeCommands,
    playlists: PlaylistRepository,
) : ViewModel() {
    val rows = downloads.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val playlistKeys = playlists.observe().map { playlist -> playlist.items.map { it.episodeKey }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
    val played = playlists.observePlayed()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
    private val _undo = MutableSharedFlow<Reversible>(extraBufferCapacity = 1)
    val undo = _undo.asSharedFlow()

    fun act(action: SwipeAction, keys: List<String>) {
        viewModelScope.launch { commands.run(action, keys)?.let { _undo.emit(it) } }
    }

    fun toggle(action: SwipeAction, keys: List<String>) {
        viewModelScope.launch { commands.runToggled(action, keys)?.let { _undo.emit(it) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    onPlay: (String) -> Unit,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val playlistKeys by viewModel.playlistKeys.collectAsStateWithLifecycle()
    val played by viewModel.played.collectAsStateWithLifecycle()
    val downloaded = rows.map { it.episodeKey }.toSet()
    val selection = rememberSelection()
    val snackbar = remember { SnackbarHostState() }
    UndoEffect(viewModel.undo, snackbar)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selection.active) "${selection.keys.size} ausgewählt" else "Downloads") },
                navigationIcon = {
                    LabeledIconButton(
                        label = if (selection.active) "Auswahl aufheben" else "Zurück",
                        onClick = { if (selection.active) selection.clear() else onBack() },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (selection.active) "Auswahl aufheben" else "Zurück",
                        )
                    }
                },
                actions = {
                    if (selection.active) {
                        TextButton(onClick = { selection.all(rows.map { it.episodeKey }) }) { Text("Alle") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (selection.active) {
                SelectionBar(
                    count = selection.keys.size,
                    onAll = { selection.all(rows.map { it.episodeKey }) },
                    onNone = { selection.clear() },
                    actions = listOf(
                        bulkLabel(SwipeAction.DELETE_DOWNLOAD, selection.keys, played, playlistKeys, downloaded) to {
                            viewModel.toggle(SwipeAction.DELETE_DOWNLOAD, selection.keys.toList()); selection.clear()
                        },
                        bulkLabel(SwipeAction.ADD_PLAYLIST, selection.keys, played, playlistKeys, downloaded) to {
                            viewModel.toggle(SwipeAction.ADD_PLAYLIST, selection.keys.toList()); selection.clear()
                        },
                        bulkLabel(SwipeAction.MARK_PLAYED, selection.keys, played, playlistKeys, downloaded) to {
                            viewModel.toggle(SwipeAction.MARK_PLAYED, selection.keys.toList()); selection.clear()
                        },
                    ),
                )
            }
        },
    ) { padding ->
        if (rows.isEmpty()) {
            Column(Modifier.padding(padding).padding(20.dp)) {
                Text("Keine gespeicherten Folgen.")
                Text(
                    "Folgen aus der Playlist lädt das Handy von selbst. Eine Datei löschst du durch Wischen oder langes Drücken.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                GestureHint(Modifier.padding(top = 8.dp))
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                item { GestureHint() }
                items(rows, key = { it.episodeKey }) { row ->
                    DownloadLine(row, SwipeAction.START_DEFAULT, SwipeAction.END_DEFAULT, selection, playlistKeys, played, onPlay, viewModel::act)
                }
            }
        }
    }
}

@Composable
private fun DownloadLine(
    row: DownloadRow,
    start: SwipeAction,
    end: SwipeAction,
    selection: SelectionState,
    playlistKeys: Set<String>,
    played: Set<String>,
    onPlay: (String) -> Unit,
    onAction: (SwipeAction, List<String>) -> Unit,
) {
    val resolvedStart = start.resolved(row.episodeKey in played, row.episodeKey in playlistKeys, downloaded = true)
    val resolvedEnd = end.resolved(row.episodeKey in played, row.episodeKey in playlistKeys, downloaded = true)
    SwipeRow(resolvedStart, resolvedEnd, enabled = !selection.active, onAction = { onAction(it, listOf(row.episodeKey)) }) {
        SelectableRow(
            selected = row.episodeKey in selection.keys,
            selecting = selection.active,
            onClick = { if (selection.active) selection.toggle(row.episodeKey) else onPlay(row.episodeKey) },
            onLongPress = { selection.start(row.episodeKey) },
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text(row.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(row.podcastTitle, "${row.bytes / 1024 / 1024} MB").filter { it.isNotBlank() }.joinToString(" · "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
