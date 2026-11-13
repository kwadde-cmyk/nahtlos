package de.jenz.nahtlos.automotive

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jenz.nahtlos.core.nostr.NostrViewModel
import de.jenz.nahtlos.nostr.EpisodeComment

@Composable
fun CarComments(
    parked: Boolean,
    episodeGuid: String?,
    podcastGuid: String?,
    showTitle: String,
    episodeTitle: String,
    page: String?,
    nostr: NostrViewModel = hiltViewModel(),
) {
    if (!parked || episodeGuid.isNullOrBlank()) return
    val ui by nostr.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Text("Kommentare", fontSize = AutoDrive.TITLE_SP.sp, fontWeight = FontWeight.SemiBold)
        if (!ui.loggedIn) {
            Text(
                "Zum Schreiben in den Einstellungen mit dem Handy anmelden.",
                fontSize = AutoDrive.BODY_SP.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (ui.comments.isEmpty()) {
            Text(
                "Noch keine Kommentare auf den Relays.",
                fontSize = AutoDrive.BODY_SP.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        ui.comments.take(12).forEach { comment ->
            CommentBlock(comment, ui.authors[comment.pubkey]?.name, ui.pubkey, episodeGuid, podcastGuid, nostr)
        }
        if (ui.loggedIn) {
            var text by remember(episodeGuid) { mutableStateOf("") }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(2_000) },
                label = { Text("Kommentar") },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            Row(Modifier.padding(top = 8.dp)) {
                androidx.compose.material3.Button(
                    enabled = text.isNotBlank() && !ui.busy,
                    onClick = {
                        nostr.comment(episodeGuid, podcastGuid, showTitle, episodeTitle, page, text)
                        text = ""
                    },
                ) { Text("Senden", fontSize = AutoDrive.BODY_SP.sp) }
            }
        }
    }
}

@Composable
private fun CommentBlock(
    comment: EpisodeComment,
    name: String?,
    me: String,
    episodeGuid: String,
    podcastGuid: String?,
    nostr: NostrViewModel,
) {
    val author = name?.ifBlank { null } ?: comment.pubkey.take(8)
    Text(author, fontWeight = FontWeight.SemiBold, fontSize = AutoDrive.BODY_SP.sp, modifier = Modifier.padding(top = 12.dp))
    if (comment.body.isNotBlank()) {
        Text(comment.body, fontSize = AutoDrive.BODY_SP.sp)
    }
    val likes = comment.likes.size
    Text(
        buildString {
            append(if (likes == 1) "1 Like" else "$likes Likes")
            if (comment.zappedSats > 0) append(" · ${comment.zappedSats} sats")
        },
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = AutoDrive.BODY_SP.sp,
    )
    if (me.length == 64) {
        Row {
            androidx.compose.material3.TextButton(
                onClick = { nostr.likeComment(episodeGuid, comment) },
            ) { Text(if (me in comment.likes) "Geliked" else "Like") }
            val address = nostr.state.value.authors[comment.pubkey]?.lud16
            if (!address.isNullOrBlank()) {
                androidx.compose.material3.TextButton(
                    onClick = {
                        nostr.zap(
                            target = comment.id,
                            address = address,
                            sats = nostr.defaultSats(),
                            podcast = "",
                            episode = "",
                            episodeGuid = episodeGuid,
                            podcastGuid = podcastGuid,
                            eventId = comment.id,
                            eventKind = comment.kind,
                            eventAuthor = comment.pubkey,
                        )
                    },
                ) { Text(nostr.state.value.zap.label(comment.id, "Zap ${nostr.defaultSats()}")) }
            }
        }
    }
    comment.replies.take(4).forEach { reply ->
        val replyName = nostr.state.value.authors[reply.pubkey]?.name?.ifBlank { null } ?: reply.pubkey.take(8)
        Text("$replyName: ${reply.body}", fontSize = AutoDrive.BODY_SP.sp, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
    }
}
