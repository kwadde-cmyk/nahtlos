package de.jenz.nahtlos.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private enum class DossierTab(val label: String) { Notes("Shownotes"), Chapters("Kapitel"), Transcript("Transkript"), People("Personen"), Support("Unterstützen") }

@Composable
fun FeatureRow(details: EpisodeDetails) {
    val marks = buildList {
        if (details.live) add("Live")
        if (details.season != null || !details.episodeNumber.isNullOrBlank()) {
            add(listOfNotNull(details.season?.let { "Staffel $it" }, details.episodeNumber?.let { "Folge $it" }).joinToString(" "))
        }
        if (details.chapters.isNotEmpty()) add("Kapitel")
        if (details.transcripts.isNotEmpty() || details.cues.isNotEmpty()) add("Transkript")
        if (details.persons.isNotEmpty()) add("Personen")
        if (details.funding.isNotEmpty() || details.value.isNotEmpty()) add("Unterstützen")
        if (details.soundbites.isNotEmpty()) add("Soundbite")
        if (details.locked) add("Gesperrt")
    }
    if (marks.isEmpty()) return
    Text(
        marks.joinToString(" · "),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpisodeDossier(
    details: EpisodeDetails,
    positionMs: Long,
    onSeek: (Long) -> Unit,
    onSubscribe: (String) -> Unit,
    onUseEnclosure: (String) -> Unit,
    onBoost: () -> Unit = {},
) {
    var tab by remember(details.podcastGuid, details.chapters.size, details.cues.size) {
        mutableStateOf(
            when {
                details.chapters.isNotEmpty() -> DossierTab.Chapters
                details.cues.isNotEmpty() || details.transcripts.isNotEmpty() -> DossierTab.Transcript
                else -> DossierTab.Notes
            },
        )
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 16.dp),
    ) {
        DossierTab.entries.forEach { item ->
            FilterChip(
                selected = tab == item,
                onClick = { tab = item },
                label = { Text(item.label) },
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
    when (tab) {
        DossierTab.Notes -> NotesTab(details, onSeek)
        DossierTab.Chapters -> ChaptersTab(details, positionMs, onSeek)
        DossierTab.Transcript -> TranscriptTab(details, positionMs, onSeek)
        DossierTab.People -> PeopleTab(details)
        DossierTab.Support -> SupportTab(details, onSubscribe, onBoost)
    }
    if (details.alternates.size > 1) {
        Text("Qualität", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
        details.alternates.distinctBy { it.url }.forEach { source ->
            TextButton(onClick = { onUseEnclosure(source.url) }) {
                Text(listOfNotNull(source.type, source.bitrate.takeIf { it > 0 }?.let { "${it / 1000} kbit/s" }, "abspielen").joinToString(" · "))
            }
        }
    }
}

@Composable
private fun NotesTab(details: EpisodeDetails, onSeek: (Long) -> Unit) {
    if (details.live) Text("Live${details.live.let { "" }}", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
    details.location?.let { place ->
        Text("Ort: ${place.name}${place.geo?.let { " ($it)" }.orEmpty()}", modifier = Modifier.padding(top = 8.dp))
    }
    details.license?.let { license ->
        val label = license.name.ifBlank { license.url }.ifBlank { "Lizenz" }
        Text("Lizenz: $label", modifier = Modifier.padding(top = 4.dp))
    }
    details.podcastGuid?.let { Text("GUID: $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
    details.txt.forEach { tag ->
        Text(listOfNotNull(tag.purpose, tag.value).joinToString(": "), style = MaterialTheme.typography.bodySmall)
    }
    details.trailers.forEach { trailer ->
        Text("Trailer: ${trailer.title}", modifier = Modifier.padding(top = 4.dp))
    }
    details.soundbites.forEach { bite ->
        TextButton(onClick = { onSeek(bite.startMs) }) { Text("Soundbite ${formatClock(bite.startMs)} ${bite.title}") }
    }
    if (details.html.contains('<')) HtmlNotes(details.html)
    else if (details.description.isNotBlank()) Text(details.description, modifier = Modifier.padding(top = 12.dp, bottom = 24.dp))
    else Text("Keine Shownotes.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun ChaptersTab(details: EpisodeDetails, positionMs: Long, onSeek: (Long) -> Unit) {
    if (details.chapters.isEmpty()) {
        Text("Keine Kapitel. Weder podcast:chapters noch ID3-Kapitel.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
        return
    }
    val context = LocalContext.current
    details.chapters.forEach { chapter ->
        val later = details.chapters.any { it.startMs > chapter.startMs && positionMs >= it.startMs }
        val current = positionMs >= chapter.startMs && !later
        Row(
            Modifier.fillMaxWidth().clickable { onSeek(chapter.startMs) }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!chapter.imageUrl.isNullOrBlank()) {
                Artwork(chapter.imageUrl, chapter.title, Modifier.size(40.dp))
                Spacer(Modifier.size(8.dp))
            }
            Text(
                "${formatClock(chapter.startMs)}  ${chapter.title}",
                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (!chapter.url.isNullOrBlank()) {
                TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(chapter.url))) } }) { Text("Link") }
            }
        }
    }
}

@Composable
private fun TranscriptTab(details: EpisodeDetails, positionMs: Long, onSeek: (Long) -> Unit) {
    var query by remember { mutableStateOf("") }
    if (details.cues.isEmpty() && details.transcripts.isEmpty()) {
        Text("Kein Transkript im Feed.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
        return
    }
    if (details.cues.isEmpty()) {
        Text("Transkript wird geladen …", modifier = Modifier.padding(top = 12.dp))
        return
    }
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text("Im Transkript suchen") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
    val needle = query.trim()
    val shown = if (needle.isEmpty()) {
        val index = details.cues.indexOfFirst { positionMs in it.startMs until it.endMs }.coerceAtLeast(0)
        details.cues.drop((index - 8).coerceAtLeast(0)).take(40)
    } else {
        details.cues.filter { it.text.contains(needle, ignoreCase = true) }.take(80)
    }
    shown.forEach { cue ->
        val active = positionMs in cue.startMs until cue.endMs
        Text(
            cue.text,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth().clickable { onSeek(cue.startMs) }.padding(vertical = 4.dp),
        )
    }
    if (needle.isEmpty() && details.cues.size > shown.size) {
        Text("${details.cues.size} Zeilen, Ausschnitt um die Wiedergabe.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PeopleTab(details: EpisodeDetails) {
    val context = LocalContext.current
    if (details.persons.isEmpty()) {
        Text("Keine Personen im Feed.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
        return
    }
    details.persons.forEach { person ->
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(person.img, person.name, Modifier.size(48.dp))
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(person.name, style = MaterialTheme.typography.titleSmall)
                Text(listOfNotNull(person.role, person.group).joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!person.href.isNullOrBlank()) {
                TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(person.href))) } }) { Text("Link") }
            }
        }
    }
}

@Composable
private fun SupportTab(details: EpisodeDetails, onSubscribe: (String) -> Unit, onBoost: () -> Unit) {
    val context = LocalContext.current
    if (details.funding.isEmpty() && details.value.isEmpty() && details.podroll.isEmpty()) {
        Text("Keine Funding- oder Value-Angaben.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
    }
    details.funding.forEach { link ->
        Button(
            onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url))) } },
            modifier = Modifier.padding(top = 12.dp),
        ) { Text(link.title.ifBlank { "Unterstützen" }) }
    }
    if (details.value.isNotEmpty()) {
        Text("Value4Value", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
        details.valueSuggested?.let { Text("Vorschlag aus dem Feed: $it") }
        Text(
            "Empfänger und Splits. Ein Boost geht über Alby Hub (NWC), Streaming-Sats kommen später.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = onBoost, modifier = Modifier.padding(top = 8.dp)) { Text("Boost senden") }
        if (details.timeSplits.isNotEmpty()) {
            Text(
                "Zeitfenster ändern die Empfänger an der aktuellen Stelle.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        details.value.forEach { recipient ->
            Text(
                listOfNotNull(recipient.name, recipient.type, recipient.address, recipient.split?.let { "$it %" }).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
    if (details.podroll.isNotEmpty()) {
        Text("Empfehlungen", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
        details.podroll.forEach { item ->
            val url = item.feedUrl
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(item.title ?: item.feedGuid ?: "Podcast", modifier = Modifier.weight(1f))
                if (!url.isNullOrBlank()) TextButton(onClick = { onSubscribe(url) }) { Text("Abonnieren") }
            }
        }
    }
}

@Composable
private fun HtmlNotes(html: String) {
    val color = MaterialTheme.colorScheme.onSurface
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = Modifier.padding(top = 12.dp, bottom = 32.dp),
        factory = { context ->
            android.widget.TextView(context).apply {
                movementMethod = android.text.method.LinkMovementMethod.getInstance()
            }
        },
        update = { view ->
            view.setTextColor(color.toArgb())
            view.text = androidx.core.text.HtmlCompat.fromHtml(html, androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT)
        },
    )
}
