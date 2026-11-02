package de.jenz.weiterhoeren.core.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import de.jenz.weiterhoeren.core.db.EpisodeEntity
import de.jenz.weiterhoeren.core.db.PodcastEntity
import de.jenz.weiterhoeren.core.db.WeiterDatabase
import de.jenz.weiterhoeren.core.feed.FeedRepository
import java.net.URLDecoder
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibraryBrowser @Inject constructor(
    private val db: WeiterDatabase,
    private val feeds: FeedRepository,
) {
    fun rootItem(): MediaItem = folder(LibraryIds.ROOT, "Weiterhören", MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS)

    suspend fun children(parentId: String): List<MediaItem> = when (parentId) {
        LibraryIds.ROOT -> listOf(
            folder(LibraryIds.CONTINUE, "Weiterhören", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
            folder(LibraryIds.SUBSCRIPTIONS, "Abonnements", MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS),
        )
        LibraryIds.CONTINUE -> continueItems()
        LibraryIds.SUBSCRIPTIONS -> subscriptionItems()
        else -> {
            val podcastId = LibraryIds.podcastId(parentId) ?: return emptyList()
            feeds.ensureFresh(podcastId)
            val podcast = db.podcasts().get(podcastId) ?: return emptyList()
            val episodes = db.episodes().forPodcast(podcastId)
            if (episodes.isEmpty()) {
                listOf(note("empty:$podcastId", "Keine Episoden", podcast.title))
            } else {
                episodes.map { episodeItem(it, podcast, playable = true) }
            }
        }
    }

    suspend fun resolvePlayable(mediaId: String): MediaItem? {
        if (mediaId.isBlank() || LibraryIds.isFolder(mediaId)) return null
        val episode = db.episodes().get(mediaId) ?: return null
        val podcast = db.podcasts().get(episode.podcastId)
        return episodeItem(episode, podcast, playable = true)
    }

    private suspend fun continueItems(): List<MediaItem> {
        val items = continueRows()
        if (items.isEmpty()) {
            return listOf(note("empty-continue", "Nichts auf Halde", "Pausiere eine Folge auf einem Gerät"))
        }
        return items
    }

    private suspend fun continueRows(): List<MediaItem> {
        val progress = db.progress().all()
            .filter { it.positionMs > 3_000 && it.status != "COMPLETED" }
            .filter { it.durationMs <= 0 || it.positionMs < it.durationMs - 15_000 }
            .sortedByDescending { it.updatedAt }
            .take(30)
        val items = mutableListOf<MediaItem>()
        for (row in progress) {
            val episode = db.episodes().get(row.episodeKey) ?: continue
            val podcast = db.podcasts().get(episode.podcastId)
            val subtitle = podcast?.title?.let { title ->
                "${formatClock(row.positionMs)} · $title"
            } ?: formatClock(row.positionMs)
            items += episodeItem(episode, podcast, playable = true, subtitle = subtitle)
        }
        return items
    }

    private suspend fun subscriptionItems(): List<MediaItem> {
        val podcasts = db.podcasts().all()
        if (podcasts.isEmpty()) {
            return listOf(
                note(
                    "empty-subs",
                    "Keine Abos",
                    "Am Telefon einen RSS-Feed hinzufügen. Er erscheint hier nach dem Sync.",
                ),
            )
        }
        return podcasts.map { podcast ->
            folder(
                id = LibraryIds.podcast(podcast.id),
                title = podcast.title,
                mediaType = MediaMetadata.MEDIA_TYPE_PODCAST,
                subtitle = podcast.author,
                artwork = podcast.imageUrl,
            )
        }
    }

    private fun episodeItem(
        episode: EpisodeEntity,
        podcast: PodcastEntity?,
        playable: Boolean,
        subtitle: String? = podcast?.title,
    ): MediaItem {
        val builder = MediaItem.Builder()
            .setMediaId(episode.episodeKey)
            .setUri(episode.enclosureUrl)
            .setMimeType(episode.mimeType ?: "audio/mpeg")
        val metadata = MediaMetadata.Builder()
            .setTitle(episode.title)
            .setArtist(podcast?.title)
            .setAlbumTitle(podcast?.title)
            .setSubtitle(subtitle)
            .setIsPlayable(playable)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
            .setDurationMs(episode.durationMs)
        val art = episode.imageUrl ?: podcast?.imageUrl
        if (!art.isNullOrBlank()) metadata.setArtworkUri(Uri.parse(art))
        return builder.setMediaMetadata(metadata.build()).build()
    }

    private fun folder(
        id: String,
        title: String,
        mediaType: Int,
        subtitle: String? = null,
        artwork: String? = null,
    ): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(mediaType)
        if (!artwork.isNullOrBlank()) metadata.setArtworkUri(Uri.parse(artwork))
        return MediaItem.Builder().setMediaId(id).setMediaMetadata(metadata.build()).build()
    }

    private fun note(id: String, title: String, subtitle: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(false)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MIXED)
                    .build(),
            )
            .build()

    private fun formatClock(ms: Long): String {
        val total = (ms.coerceAtLeast(0) / 1000)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
    }
}

object LibraryIds {
    const val ROOT = "root"
    const val CONTINUE = "continue"
    const val SUBSCRIPTIONS = "subscriptions"

    fun podcast(id: String): String = "pod:" + URLEncoder.encode(id, Charsets.UTF_8.name())

    fun podcastId(mediaId: String): String? {
        if (!mediaId.startsWith("pod:")) return null
        return URLDecoder.decode(mediaId.removePrefix("pod:"), Charsets.UTF_8.name())
    }

    fun isFolder(mediaId: String): Boolean =
        mediaId == ROOT || mediaId == CONTINUE || mediaId == SUBSCRIPTIONS || mediaId.startsWith("pod:") || mediaId.startsWith("empty")
}
