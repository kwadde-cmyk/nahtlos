package de.jenz.nahtlos.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import de.jenz.nahtlos.core.Payments
import de.jenz.nahtlos.core.nostr.NostrViewModel
import de.jenz.nahtlos.nostr.EpisodeComment

@Composable
fun CommentsSection(
    episodeGuid: String?,
    podcastGuid: String?,
    showTitle: String,
    episodeTitle: String,
    page: String?,
    zapAddress: String?,
    viewModel: NostrViewModel = hiltViewModel(),
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }
    LaunchedEffect(episodeGuid) {
        if (!episodeGuid.isNullOrBlank()) viewModel.load(episodeGuid)
    }
    Column(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 24.dp)) {
        Text("Kommentare", style = MaterialTheme.typography.titleMedium)
        if (episodeGuid.isNullOrBlank()) {
            Text("Diese Folge hat keine GUID.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        if (!ui.loggedIn) {
            Text(
                "Mit Amber anmelden, dann kannst du schreiben, liken und zappen.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${ui.episodeLikes} Likes", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (ui.episodeZaps > 0) {
                Text(" · ${ui.episodeZaps} sats", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (ui.loggedIn) {
                TextButton(onClick = { viewModel.likeEpisode(episodeGuid, podcastGuid) }) { Text("Like") }
                if (Payments.enabled && !zapAddress.isNullOrBlank()) {
                    TextButton(
                        onClick = {
                            viewModel.zap(
                                target = episodeGuid,
                                address = zapAddress,
                                sats = viewModel.defaultSats(),
                                podcast = showTitle,
                                episode = episodeTitle,
                                episodeGuid = episodeGuid,
                                podcastGuid = podcastGuid,
                            )
                        },
                    ) { Text(ui.zap.label(episodeGuid, "Zap ${viewModel.defaultSats()}")) }
                }
            }
        }
        if (ui.comments.isEmpty()) {
            Text("Noch keine Kommentare. Fountain-Kommentare erscheinen mit derselben GUID.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (ui.notice.isNotBlank() && !ui.notice.startsWith("Zap gesendet") && !ui.notice.startsWith("Zap unklar")) {
            Text(ui.notice, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        }
        ui.comments.forEach { comment ->
            CommentRow(comment, ui, episodeGuid, podcastGuid, showTitle, episodeTitle, viewModel)
        }
        if (ui.loggedIn) {
            var text by remember(episodeGuid) { mutableStateOf("") }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(2_000) },
                label = { Text("Kommentar") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            TextButton(
                enabled = text.isNotBlank() && !ui.busy,
                onClick = {
                    viewModel.comment(episodeGuid, podcastGuid, showTitle, episodeTitle, page, text)
                    text = ""
                },
            ) { Text("Senden") }
        }
        val notice = ui.notice
        if (notice.startsWith("Zap gesendet") || notice.startsWith("Zap unklar")) {
            ZapSentDialog(notice, viewModel::clearNotice)
        }
    }
}

@Composable
private fun CommentRow(
    comment: EpisodeComment,
    ui: de.jenz.nahtlos.core.nostr.NostrUi,
    episodeGuid: String,
    podcastGuid: String?,
    showTitle: String,
    episodeTitle: String,
    viewModel: NostrViewModel,
) {
    val author = ui.authors[comment.pubkey]
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!author?.picture.isNullOrBlank()) {
            AsyncImage(
                model = author?.picture,
                contentDescription = author?.name,
                modifier = Modifier.padding(end = 8.dp).size(36.dp).clip(MaterialTheme.shapes.small),
                contentScale = ContentScale.Crop,
            )
        }
        Text(author?.name?.ifBlank { null } ?: comment.pubkey.take(8), style = MaterialTheme.typography.titleSmall)
    }
    if (comment.body.isNotBlank()) Text(comment.body, modifier = Modifier.padding(top = 2.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${comment.likes.size}" + if (comment.zappedSats > 0) " · ${comment.zappedSats} sats" else "",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        if (ui.loggedIn) {
            TextButton(onClick = { viewModel.likeComment(episodeGuid, comment) }) {
                Text(if (ui.pubkey in comment.likes) "Geliked" else "Like")
            }
            val address = author?.lud16
            if (Payments.enabled && !address.isNullOrBlank()) {
                TextButton(
                    onClick = {
                        viewModel.zap(
                            target = comment.id,
                            address = address,
                            sats = viewModel.defaultSats(),
                            podcast = showTitle,
                            episode = episodeTitle,
                            episodeGuid = episodeGuid,
                            podcastGuid = podcastGuid,
                            eventId = comment.id,
                            eventKind = comment.kind,
                            eventAuthor = comment.pubkey,
                        )
                    },
                ) { Text(ui.zap.label(comment.id, "Zap ${viewModel.defaultSats()}")) }
            }
        }
    }
    comment.replies.forEach { reply ->
        val name = ui.authors[reply.pubkey]?.name?.ifBlank { null } ?: reply.pubkey.take(8)
        Text(
            "$name: ${reply.body}",
            modifier = Modifier.padding(start = 16.dp, top = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
