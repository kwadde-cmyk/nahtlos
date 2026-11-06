package de.jenz.nahtlos.core.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import de.jenz.nahtlos.core.artwork.ArtworkStore
import de.jenz.nahtlos.core.db.EpisodeEntity
import de.jenz.nahtlos.core.db.PodcastEntity
import de.jenz.nahtlos.core.db.extras
import de.jenz.nahtlos.core.db.NahtlosDatabase
import de.jenz.nahtlos.core.feed.FeedRepository
import de.jenz.nahtlos.core.playlist.DownloadStore
import de.jenz.nahtlos.playback.AudioSource
import de.jenz.nahtlos.core.playlist.PlaylistRepository
import de.jenz.nahtlos.core.playlist.entry
import java.net.URLDecoder
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibraryBrowser @Inject constructor(
    private val db: NahtlosDatabase,
    private val feeds: FeedRepository,
    private val playlists: PlaylistRepository,
    private val downloads: DownloadStore,
    private val artwork: ArtworkStore,
) {
    fun rootItem(): MediaItem = folder(LibraryIds.ROOT, "Nahtlos", MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS)

    /** Resume point of the newest in-progress episode, for the car to warm its cache. */
    suspend fun continueToWarm(): Pair<MediaItem, Long>? {
        val row = db.progress().topContinue() ?: return null
        val item = resolvePlayable(row.episodeKey) ?: return null
        return item to row.positionMs.coerceAtLeast(0L)
    }

    suspend fun children(parentId: String): List<MediaItem> = when (parentId) {
        LibraryIds.ROOT -> listOf(
            folder(LibraryIds.PLAYLIST, "Playlist", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
            folder(LibraryIds.CONTINUE, "Weitermachen", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
            folder(LibraryIds.SUBSCRIPTIONS, "Abonnements", MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS),
        )
        LibraryIds.PLAYLIST -> playlistItems()
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

    suspend fun search(raw: String): List<MediaItem> {
        val query = raw.trim()
        if (query.isEmpty()) {
            val resume = continueItems().filter { it.mediaMetadata.isPlayable == true }
            if (resume.isNotEmpty()) return resume.take(20)
            return playlistItems().filter { it.mediaMetadata.isPlayable == true }.take(20)
        }
        val needle = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return db.episodes().search(needle, 30).map { episode ->
            val podcast = db.podcasts().get(episode.podcastId)
            episodeItem(episode, podcast, playable = true)
        }
    }

    suspend fun resolvePlayable(mediaId: String): MediaItem? {
        if (mediaId.isBlank() || LibraryIds.isFolder(mediaId)) return null
        val episode = db.episodes().get(mediaId)
        if (episode != null) {
            val podcast = db.podcasts().get(episode.podcastId)
            return episodeItem(episode, podcast, playable = true)
        }
        val entry = playlists.current().entry(mediaId) ?: return null
        return MediaItem.Builder()
            .setMediaId(entry.episodeKey)
            .setUri(localOrRemote(entry.episodeKey, entry.enclosureUrl))
            .apply { AudioSource.mediaMime(null, entry.enclosureUrl)?.let { setMimeType(it) } }
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(entry.title.ifBlank { "Folge" })
                    .setArtist(entry.podcastTitle)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                    .build(),
            )
            .build()
    }

    private suspend fun playlistItems(): List<MediaItem> {
        val items = playlists.current().items
        if (items.isEmpty()) {
            return listOf(note("empty-playlist", "Keine Playlist", "Am Telefon ein Datum wählen."))
        }
        return items.map { entry ->
            MediaItem.Builder()
                .setMediaId(entry.episodeKey)
                .setUri(localOrRemote(entry.episodeKey, entry.enclosureUrl))
                .apply { AudioSource.mediaMime(null, entry.enclosureUrl)?.let { setMimeType(it) } }
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(entry.title.ifBlank { "Folge" })
                        .setArtist(entry.podcastTitle)
                        .setIsPlayable(true)
                        .setIsBrowsable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                        .build(),
                )
                .build()
        }
    }

    suspend fun streamUri(mediaId: String): Uri? {
        val remote = db.episodes().get(mediaId)?.enclosureUrl
            ?: playlists.current().entry(mediaId)?.enclosureUrl
        if (remote.isNullOrBlank()) return null
        return Uri.parse(remote)
    }

    private fun localOrRemote(episodeKey: String, remote: String): Uri =
        playbackUri(downloads.existingFile(episodeKey), remote)

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
                artwork = artwork.uriForBrowser(podcast.imageUrl)?.toString(),
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
            .setUri(localOrRemote(episode.episodeKey, episode.enclosureUrl))
            .apply { AudioSource.mediaMime(episode.mimeType, episode.enclosureUrl)?.let { setMimeType(it) } }
        val extras = episode.extras()
        val title = if (extras.live) "Live: ${episode.title}" else episode.title
        val chapterHint = episode.chaptersJson != "[]" || !episode.chaptersUrl.isNullOrBlank()
        val shownSubtitle = buildString {
            if (!subtitle.isNullOrBlank()) append(subtitle)
            if (chapterHint) {
                if (isNotEmpty()) append(" · ")
                append("Kapitel")
            }
        }.ifBlank { null }
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(podcast?.title)
            .setAlbumTitle(podcast?.title)
            .setSubtitle(shownSubtitle)
            .setIsPlayable(playable)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
            .setDurationMs(episode.durationMs)
        val art = artwork.uriForBrowser(episode.imageUrl ?: podcast?.imageUrl)
        if (art != null) metadata.setArtworkUri(art)
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
        // artwork is already a content URI when the cover is cached
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
    const val PLAYLIST = "playlist"

    fun podcast(id: String): String = "pod:" + URLEncoder.encode(id, Charsets.UTF_8.name())

    fun podcastId(mediaId: String): String? {
        if (!mediaId.startsWith("pod:")) return null
        return URLDecoder.decode(mediaId.removePrefix("pod:"), Charsets.UTF_8.name())
    }

    fun isFolder(mediaId: String): Boolean =
        mediaId == ROOT || mediaId == CONTINUE || mediaId == SUBSCRIPTIONS || mediaId == PLAYLIST ||
            mediaId.startsWith("pod:") || mediaId.startsWith("empty")
}
