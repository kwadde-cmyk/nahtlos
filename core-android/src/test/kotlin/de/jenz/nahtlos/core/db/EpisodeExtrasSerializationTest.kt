package de.jenz.nahtlos.core.db

import de.jenz.nahtlos.model.NahtlosJson
import de.jenz.nahtlos.model.Soundbite
import de.jenz.nahtlos.model.ValueRecipient
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals

class EpisodeExtrasSerializationTest {
    @Test
    fun episodeExtrasRoundTrip() {
        val original = EpisodeExtras(
            descriptionHtml = "<p>Notizen</p>",
            season = 2,
            episodeNumber = "5",
            live = true,
            soundbites = listOf(Soundbite(startMs = 73_000, durationMs = 30_000, title = "Ausschnitt")),
            value = listOf(ValueRecipient(name = "Ada", type = "node", address = "03abc", split = 100)),
        )
        val text = NahtlosJson.json.encodeToString(original)
        val decoded = NahtlosJson.json.decodeFromString<EpisodeExtras>(text)
        assertEquals(original, decoded)
    }

    @Test
    fun podcastPlaybackRoundTrip() {
        val original = PodcastPlayback(speed = 1.4f, skipSilence = true, boost = false, introSec = 15, outroSec = 30)
        val text = NahtlosJson.json.encodeToString(original)
        assertEquals(original, NahtlosJson.json.decodeFromString<PodcastPlayback>(text))
    }

    @Test
    fun brokenExtrasJsonDoesNotEscape() {
        val episode = EpisodeEntity(
            episodeKey = "pod|ep",
            podcastId = "pod",
            title = "Folge",
            description = "",
            enclosureUrl = "https://cdn.example/1.mp3",
            mimeType = null,
            guid = "ep",
            durationMs = null,
            publishedAt = null,
            imageUrl = null,
            chaptersJson = "[]",
            chaptersUrl = null,
            transcriptsJson = "[]",
            personsJson = "[]",
            extrasJson = "not-json",
        )
        assertEquals(EpisodeExtras(), episode.extras())
    }
}
