package de.jenz.nahtlos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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

fun bulkLabel(
    action: SwipeAction,
    keys: Collection<String>,
    played: Set<String>,
    inPlaylist: Set<String>,
    downloaded: Set<String>,
): String {
    val labels = keys.map { key ->
        action.resolved(key in played, key in inPlaylist, key in downloaded).label
    }.distinct()
    return if (labels.size == 1) labels.first() else action.label
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
fun SelectionBar(
    count: Int,
    onAll: () -> Unit,
    onNone: () -> Unit,
    actions: List<Pair<String, () -> Unit>>,
) {
    Row(
        Modifier.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("$count", modifier = Modifier.padding(end = 8.dp))
        TextButton(onClick = onAll) { Text("Alle") }
        TextButton(onClick = onNone) { Text("Keine") }
        actions.forEach { (label, action) ->
            TextButton(onClick = action, enabled = count > 0) { Text(label) }
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
