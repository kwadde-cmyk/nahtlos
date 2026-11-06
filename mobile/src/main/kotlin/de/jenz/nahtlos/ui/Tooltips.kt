package de.jenz.nahtlos.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.jenz.nahtlos.core.sync.SettingsStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabeledIconButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, modifier = modifier, enabled = enabled) { content() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabeledFilledIconButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        FilledIconButton(onClick = onClick, modifier = modifier, enabled = enabled) { content() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IconTextButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        TextButton(onClick = onClick, modifier = modifier, enabled = enabled) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(label)
        }
    }
}

@HiltViewModel
class GestureHintViewModel @Inject constructor(
    private val settings: SettingsStore,
) : ViewModel() {
    val seen = settings.observeGesturesHint().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        settings.gesturesHintSeen(),
    )

    fun dismiss() {
        settings.markGesturesHintSeen()
    }
}

@Composable
fun GestureHint(modifier: Modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
    val viewModel: GestureHintViewModel = hiltViewModel()
    val seen by viewModel.seen.collectAsStateWithLifecycle()
    if (seen) return
    Column(modifier) {
        Text(
            "In der Folgenliste wischt du nach rechts für Gehört und nach links für die Playlist. Langes Drücken wählt mehrere. Die Playlist selbst hat kein Wischen.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = viewModel::dismiss) { Text("Verstanden") }
    }
}
