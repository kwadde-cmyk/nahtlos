package de.jenz.nahtlos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileDownloadOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material.icons.filled.RemoveDone
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import de.jenz.nahtlos.core.library.Reversible
import de.jenz.nahtlos.playback.SwipeAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharedFlow

class SelectionState {
    var active by mutableStateOf(false)
    var keys by mutableStateOf(setOf<String>())

    fun toggle(id: String) {
        keys = if (id in keys) keys - id else keys + id
        if (keys.isEmpty()) active = false
    }

    fun start(id: String) {
        active = true
        keys = keys + id
    }

    fun all(ids: Collection<String>) {
        keys = ids.toSet()
        active = keys.isNotEmpty()
    }

    fun clear() {
        keys = emptySet()
        active = false
    }
}

@Composable
fun rememberSelection(): SelectionState = remember { SelectionState() }

fun SwipeAction.menuLabel(): String = when (this) {
    SwipeAction.ADD_PLAYLIST -> "Zur Playlist"
    SwipeAction.REMOVE_PLAYLIST -> "Aus Playlist entfernen"
    SwipeAction.DOWNLOAD -> "Herunterladen"
    SwipeAction.DELETE_DOWNLOAD -> "Download löschen"
    SwipeAction.MARK_PLAYED -> "Als gehört markieren"
    SwipeAction.MARK_UNPLAYED -> "Als ungehört markieren"
}

fun SwipeAction.menuIcon(): ImageVector = when (this) {
    SwipeAction.ADD_PLAYLIST -> Icons.AutoMirrored.Filled.PlaylistAdd
    SwipeAction.REMOVE_PLAYLIST -> Icons.Filled.PlaylistRemove
    SwipeAction.DOWNLOAD -> Icons.Filled.Download
    SwipeAction.DELETE_DOWNLOAD -> Icons.Filled.FileDownloadOff
    SwipeAction.MARK_PLAYED -> Icons.Filled.CheckCircle
    SwipeAction.MARK_UNPLAYED -> Icons.Filled.RemoveDone
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeRow(
    start: SwipeAction,
    end: SwipeAction,
    enabled: Boolean,
    onAction: (SwipeAction) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        Box(modifier) { content() }
        return
    }
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            val action = when (value) {
                SwipeToDismissBoxValue.StartToEnd -> start
                SwipeToDismissBoxValue.EndToStart -> end
                SwipeToDismissBoxValue.Settled -> return@rememberSwipeToDismissBoxState false
            }
            onAction(action)
            false
        },
    )
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        backgroundContent = {
            val action = when (state.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd -> start
                SwipeToDismissBoxValue.EndToStart -> end
                else -> null
            }
            val align = if (state.dismissDirection == SwipeToDismissBoxValue.StartToEnd) {
                Alignment.CenterStart
            } else {
                Alignment.CenterEnd
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 20.dp),
                contentAlignment = align,
            ) {
                Text(action?.label.orEmpty(), color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        },
        content = { content() },
    )
}

@Composable
fun EpisodeSelectionBar(
    keys: Collection<String>,
    played: Set<String>,
    inPlaylist: Set<String>,
    downloaded: Set<String>,
    onAll: () -> Unit,
    onNone: () -> Unit,
    onAction: (SwipeAction, List<String>) -> Unit,
) {
    val actions = SwipeAction.matching(keys, played, inPlaylist, downloaded)
    SelectionBar(
        count = keys.size,
        onAll = onAll,
        onNone = onNone,
        actions = actions.map { action ->
            SelectionAction(
                label = action.menuLabel(),
                icon = action.menuIcon(),
                onClick = {
                    onAction(action, SwipeAction.targets(action, keys, played, inPlaylist, downloaded))
                },
            )
        },
    )
}

@Composable
fun EpisodeActionMenuButton(
    played: Boolean,
    inPlaylist: Boolean,
    downloaded: Boolean,
    onAction: (SwipeAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val key = "episode"
    val actions = SwipeAction.matching(
        listOf(key),
        played = if (played) setOf(key) else emptySet(),
        inPlaylist = if (inPlaylist) setOf(key) else emptySet(),
        downloaded = if (downloaded) setOf(key) else emptySet(),
    )
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (open) {
            actions.forEach { action ->
                LabeledIconButton(
                    label = action.menuLabel(),
                    onClick = {
                        open = false
                        onAction(action)
                    },
                ) {
                    Icon(action.menuIcon(), contentDescription = action.menuLabel())
                }
            }
        }
        LabeledIconButton(
            label = if (open) "Schließen" else "Folgenaktionen",
            onClick = { open = !open },
        ) {
            Icon(
                if (open) Icons.Filled.Close else Icons.Filled.MoreVert,
                contentDescription = if (open) "Schließen" else "Folgenaktionen",
            )
        }
    }
}

data class SelectionAction(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

@Composable
fun SelectionBar(
    count: Int,
    onAll: () -> Unit,
    onNone: () -> Unit,
    actions: List<SelectionAction>,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            Text(
                "$count",
                modifier = Modifier.padding(horizontal = 12.dp),
                style = MaterialTheme.typography.titleMedium,
            )
            TextButton(onClick = onAll) { Text("Alle") }
            TextButton(onClick = onNone) { Text("Keine") }
            actions.forEach { action ->
                LabeledIconButton(
                    label = action.label,
                    onClick = action.onClick,
                    enabled = count > 0,
                ) {
                    Icon(action.icon, contentDescription = action.label)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SelectableRow(
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val gesture = if (selecting) {
        Modifier.toggleable(value = selected, onValueChange = { onClick() })
    } else {
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongPress)
    }
    Box(
        modifier
            .then(gesture)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                else androidx.compose.ui.graphics.Color.Transparent,
            ),
    ) { content() }
}

@Composable
fun UndoEffect(events: SharedFlow<Reversible>, snackbar: SnackbarHostState) {
    LaunchedEffect(events) {
        events.collect { action ->
            try {
                val result = snackbar.showSnackbar(action.message, actionLabel = "Rückgängig")
                if (result == SnackbarResult.ActionPerformed) action.undo() else action.commit()
            } catch (cancelled: CancellationException) {
                action.commit()
                throw cancelled
            }
        }
    }
}
