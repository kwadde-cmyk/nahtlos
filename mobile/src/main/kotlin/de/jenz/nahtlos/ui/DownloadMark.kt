package de.jenz.nahtlos.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.jenz.nahtlos.core.playlist.DownloadTransfer
import de.jenz.nahtlos.playlist.DownloadMarks

@Composable
fun DownloadMark(onDevice: Boolean, transfer: DownloadTransfer?, modifier: Modifier = Modifier) {
    val caption = DownloadMarks.caption(
        onDevice = onDevice && transfer == null,
        activeWritten = transfer?.written,
        activeTotal = transfer?.total,
    )
    val fraction = transfer?.let { DownloadMarks.fraction(it.written, it.total) }
    Column(modifier) {
        Text(
            caption,
            style = MaterialTheme.typography.labelMedium,
            color = if (onDevice && transfer == null) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (transfer != null) {
            if (fraction == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
            } else {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
    }
}
