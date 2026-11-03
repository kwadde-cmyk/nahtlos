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
import androidx.compose.material3.IconButton
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
import de.jenz.nahtlos.core.library.Reversible
import de.jenz.nahtlos.core.playlist.DownloadStore
import de.jenz.nahtlos.core.sync.SettingsStore
import de.jenz.nahtlos.playback.SwipeAction
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    downloads: DownloadStore,
    private val commands: EpisodeCommands,
    settingsStore: SettingsStore,
) : ViewModel() {
    val rows = downloads.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val settings = settingsStore.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), settingsStore.read())
    private val _undo = MutableSharedFlow<Reversible>(extraBufferCapacity = 1)
    val undo = _undo.asSharedFlow()

    fun act(action: SwipeAction, keys: List<String>) {
        viewModelScope.launch { commands.run(action, keys)?.let { _undo.emit(it) } }
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
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val selection = rememberSelection()
    val snackbar = remember { SnackbarHostState() }
    UndoEffect(viewModel.undo, snackbar)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selection.active) "${selection.keys.size} ausgewählt" else "Downloads") },
                navigationIcon = {
                    IconButton(onClick = { if (selection.active) selection.clear() else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
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
                        "Datei weg" to { viewModel.act(SwipeAction.DELETE_DOWNLOAD, selection.keys.toList()); selection.clear() },
                        "Playlist" to { viewModel.act(SwipeAction.ADD_PLAYLIST, selection.keys.toList()); selection.clear() },
                        "Gehört" to { viewModel.act(SwipeAction.MARK_PLAYED, selection.keys.toList()); selection.clear() },
                    ),
                )
            }
        },
    ) { padding ->
        if (rows.isEmpty()) {
            Text("Keine gespeicherten Folgen.", modifier = Modifier.padding(padding).padding(20.dp))
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(rows, key = { it.episodeKey }) { row ->
                    DownloadLine(row, settings.swipeStart, settings.swipeEnd, selection, onPlay, viewModel::act)
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
    onPlay: (String) -> Unit,
    onAction: (SwipeAction, List<String>) -> Unit,
) {
    SwipeRow(start, end, enabled = !selection.active, onAction = { onAction(it, listOf(row.episodeKey)) }) {
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
