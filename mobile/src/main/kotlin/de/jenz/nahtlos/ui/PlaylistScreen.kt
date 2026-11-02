package de.jenz.nahtlos.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import de.jenz.nahtlos.core.playlist.PlaylistRepository
import de.jenz.nahtlos.playlist.PlaylistSort
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class PlaylistViewModel @Inject constructor(
    private val playlists: PlaylistRepository,
) : ViewModel() {
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

    fun move(index: Int, delta: Int) {
        viewModelScope.launch { playlists.move(index, delta) }
    }

    fun remove(episodeKey: String) {
        viewModelScope.launch { playlists.remove(episodeKey) }
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
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
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
                    if (playlist.items.isEmpty()) {
                        Text("Noch keine Folgen in der Playlist.", modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
            itemsIndexed(playlist.items, key = { _, item -> item.episodeKey }) { index, item ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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
                    IconButton(onClick = { onPlay(item.episodeKey) }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Abspielen")
                    }
                    IconButton(onClick = { viewModel.move(index, -1) }, enabled = index > 0) {
                        Icon(Icons.Filled.ArrowUpward, contentDescription = "Nach oben")
                    }
                    IconButton(onClick = { viewModel.move(index, 1) }, enabled = index < playlist.items.lastIndex) {
                        Icon(Icons.Filled.ArrowDownward, contentDescription = "Nach unten")
                    }
                    IconButton(onClick = { viewModel.remove(item.episodeKey) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Entfernen")
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
