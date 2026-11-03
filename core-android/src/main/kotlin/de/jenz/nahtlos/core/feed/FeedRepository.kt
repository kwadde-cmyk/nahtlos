package de.jenz.nahtlos.core.feed

import androidx.room.withTransaction
import de.jenz.nahtlos.core.SyncPolicy
import de.jenz.nahtlos.core.db.ContinueRow
import de.jenz.nahtlos.core.db.EpisodeEntity
import de.jenz.nahtlos.core.db.EpisodeRow
import de.jenz.nahtlos.core.db.PodcastEntity
import de.jenz.nahtlos.core.db.NahtlosDatabase
import de.jenz.nahtlos.core.db.chapters
import de.jenz.nahtlos.core.db.toEntity
import de.jenz.nahtlos.core.db.toPodcast
import de.jenz.nahtlos.core.artwork.ArtworkStore
import de.jenz.nahtlos.core.playlist.DownloadStore
import de.jenz.nahtlos.feed.Id3Chapters
import de.jenz.nahtlos.core.db.PodcastPlayback
import de.jenz.nahtlos.core.db.cachedCues
import de.jenz.nahtlos.core.db.extras
import de.jenz.nahtlos.core.db.playback
import de.jenz.nahtlos.core.db.transcripts
import de.jenz.nahtlos.core.net.Net
import de.jenz.nahtlos.feed.TranscriptParser
import de.jenz.nahtlos.model.TranscriptCue
import de.jenz.nahtlos.core.sync.SettingsStore
import de.jenz.nahtlos.feed.ChapterJsonParser
import de.jenz.nahtlos.feed.EpisodeHorizon
import de.jenz.nahtlos.feed.EpisodeKeys
import de.jenz.nahtlos.feed.RssFeedParser
import javax.inject.Provider
import de.jenz.nahtlos.opml.Opml
import de.jenz.nahtlos.opml.OpmlOutline
import de.jenz.nahtlos.model.Chapter
import de.jenz.nahtlos.model.FeedParseException
import de.jenz.nahtlos.model.NahtlosJson
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FeedRepository @Inject constructor(
    private val db: NahtlosDatabase,
    private val net: Net,
    private val parser: Provider<RssFeedParser>,
    private val chapters: ChapterJsonParser,
    private val settings: SettingsStore,
    private val artwork: ArtworkStore,
    private val downloads: DownloadStore,
    private val index: PodcastIndexClient,
) {
    fun observePodcasts(): Flow<List<PodcastEntity>> = db.podcasts().observeAll()

    fun observePodcast(id: String): Flow<PodcastEntity?> = db.podcasts().observe(id)

    fun observeEpisodes(podcastId: String): Flow<List<EpisodeRow>> = db.episodes().observeForPodcast(podcastId)

    fun observeContinue(): Flow<List<ContinueRow>> = db.progress().observeContinue()

    suspend fun getPodcast(id: String): PodcastEntity? = db.podcasts().get(id)

    suspend fun getEpisode(episodeKey: String): EpisodeEntity? = db.episodes().get(episodeKey)

    suspend fun exportOpml(): String = withContext(Dispatchers.IO) {
        val outlines = db.podcasts().all().map { OpmlOutline(title = it.title, feedUrl = it.feedUrl) }
        Opml.write("Nahtlos", outlines)
    }

    /**
     * Stores title and URL only. Episode bodies are loaded later by [FeedRefreshWork].
     * Returns how many outlines had a usable http(s) address, including ones already stored.
     */
    suspend fun rememberOutlines(outlines: List<OpmlOutline>): Int = withContext(Dispatchers.IO) {
        var count = 0
        for (outline in outlines) {
            val url = try {
                normalizeHttp(outline.feedUrl)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                continue
            }
            count++
            if (db.podcasts().byFeedUrl(url) != null) continue
            db.podcasts().upsert(stub(url, outline.title.ifBlank { url }))
        }
        count
    }

    suspend fun requeueInterrupted() = withContext(Dispatchers.IO) {
        db.podcasts().requeueInterrupted()
    }

    suspend fun pendingRefresh(): List<PodcastEntity> = withContext(Dispatchers.IO) {
        db.podcasts().pending()
    }

    suspend fun retryFailed() = withContext(Dispatchers.IO) {
        db.podcasts().requeueFailed()
    }

    suspend fun retryOne(id: String) = withContext(Dispatchers.IO) {
        db.podcasts().requeue(id)
    }

    suspend fun markFailed(id: String, message: String?) = withContext(Dispatchers.IO) {
        db.podcasts().setRefresh(id, FeedRefresh.FAILED, message?.take(180) ?: "Feed konnte nicht geladen werden")
    }

    suspend fun subscribe(rawUrl: String, markNeedsSync: Boolean): PodcastEntity = withContext(Dispatchers.IO) {
        val url = normalizeHttp(rawUrl)
        val existing = db.podcasts().byFeedUrl(url)
        if (existing != null && !markNeedsSync && !isStale(existing)) return@withContext existing
        refreshUrl(url, markNeedsSync)
    }

    suspend fun refresh(podcastId: String): PodcastEntity = withContext(Dispatchers.IO) {
        val podcast = db.podcasts().get(podcastId) ?: throw FeedParseException("Podcast nicht gefunden")
        refreshUrl(podcast.feedUrl, podcast.needsSync)
    }

    suspend fun ensureFresh(podcastId: String, force: Boolean = false) {
        val podcast = db.podcasts().get(podcastId) ?: return
        if (!force) {
            when (podcast.refreshStatus) {
                FeedRefresh.PENDING, FeedRefresh.LOADING, FeedRefresh.FAILED -> return
                FeedRefresh.OK -> if (!isStale(podcast)) return
            }
        }
        try {
            refresh(podcastId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (force) throw error
        }
    }

    suspend fun deleteLocal(podcastId: String) = withContext(Dispatchers.IO) {
        val keys = db.episodes().keysForPodcast(podcastId)
        if (keys.isNotEmpty()) {
            db.progress().deleteKeys(keys)
            db.outbox().deleteKeys(keys)
        }
        db.episodes().deleteForPodcast(podcastId)
        db.podcasts().delete(podcastId)
    }

    suspend fun ensureChapters(episodeKey: String): List<Chapter> = withContext(Dispatchers.IO) {
        val episode = db.episodes().get(episodeKey) ?: return@withContext emptyList()
        val inline = episode.chapters()
        if (inline.isNotEmpty()) return@withContext inline
        if (!episode.chaptersUrl.isNullOrBlank()) {
            val fetched = try {
                withTimeout(12_000) { chapters.parse(net.getText(episode.chaptersUrl)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                emptyList()
            }
            if (fetched.isNotEmpty()) {
                db.episodes().updateChapters(episodeKey, NahtlosJson.json.encodeToString(fetched))
                return@withContext fetched
            }
        }
        val id3 = runCatching { downloads.existingFile(episodeKey)?.let { Id3Chapters.read(it) }.orEmpty() }.getOrDefault(emptyList())
        if (id3.isNotEmpty()) {
            runCatching { db.episodes().updateChapters(episodeKey, NahtlosJson.json.encodeToString(id3)) }
        }
        id3
    }

    suspend fun useEnclosure(episodeKey: String, url: String) = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext
        val episode = db.episodes().get(episodeKey) ?: return@withContext
        val mime = episode.extras().alternates.firstOrNull { it.url == url }?.type ?: episode.mimeType
        db.episodes().updateEnclosure(episodeKey, url, mime)
    }

    suspend fun savePlayback(podcastId: String, playback: PodcastPlayback) = withContext(Dispatchers.IO) {
        db.podcasts().updatePlayback(podcastId, NahtlosJson.json.encodeToString(playback))
    }

    suspend fun playbackForEpisode(episodeKey: String): PodcastPlayback? = withContext(Dispatchers.IO) {
        val episode = db.episodes().get(episodeKey) ?: return@withContext null
        db.podcasts().get(episode.podcastId)?.playback()
    }

    suspend fun podcastIdForEpisode(episodeKey: String): String? =
        db.episodes().get(episodeKey)?.podcastId

    suspend fun ensureTranscript(episodeKey: String): List<TranscriptCue> = withContext(Dispatchers.IO) {
        val episode = db.episodes().get(episodeKey) ?: return@withContext emptyList()
        val cached = episode.cachedCues()
        if (cached.isNotEmpty()) return@withContext cached
        val link = episode.transcripts().firstOrNull() ?: return@withContext emptyList()
        val body = try {
            withTimeout(12_000) { net.getText(link.url, maxBytes = 400_000) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return@withContext emptyList()
        }
        val cues = TranscriptParser.parse(body.take(200_000), link.type).take(4_000)
        if (cues.isNotEmpty()) {
            db.episodes().updateTranscript(episodeKey, NahtlosJson.json.encodeToString(cues))
        }
        cues
    }

    suspend fun chapterJump(episodeKey: String, positionMs: Long, direction: Int): Long? = withContext(Dispatchers.IO) {
        val chapters = ensureChapters(episodeKey).sortedBy { it.startMs }
        if (chapters.isEmpty()) return@withContext null
        val index = chapters.indexOfLast { it.startMs <= positionMs }.coerceAtLeast(0)
        val next = (index + direction).coerceIn(0, chapters.lastIndex)
        chapters[next].startMs
    }

    fun episodeLabel(episode: de.jenz.nahtlos.core.db.EpisodeEntity): String {
        val extra = episode.extras()
        return buildString {
            if (extra.live) append("Live")
            if (extra.season != null || extra.episodeNumber != null) {
                if (isNotEmpty()) append(" · ")
                extra.season?.let { append("S$it ") }
                extra.episodeNumber?.let { append("E$it") }
            }
        }.trim()
    }

    private suspend fun refreshUrl(url: String, markNeedsSync: Boolean, triedGuid: Boolean = false): PodcastEntity {
        val existing = db.podcasts().byFeedUrl(url)
        if (existing != null) {
            db.podcasts().setRefresh(existing.id, FeedRefresh.LOADING, null)
        }
        try {
            val bundle = withTimeout(FeedRefresh.TIMEOUT_MS) {
                net.read(url) { reader ->
                    val cutoff = EpisodeHorizon.fromId(settings.read().episodeHorizonId)
                        .cutoffEpochMs(System.currentTimeMillis())
                    // Upsert only. Episodes already stored — including ones in a playlist or
                    // with a saved position — stay even when they fall outside the horizon.
                    val parsed = try {
                        parser.get().parse(reader, publishedAfterEpochMs = cutoff)
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        throw error
                    }
                    val podcast = parsed.toPodcast(
                        feedUrl = url,
                        needsSync = markNeedsSync,
                        fetchedAt = System.currentTimeMillis(),
                        existingId = existing?.id,
                    ).copy(
                        refreshStatus = FeedRefresh.OK,
                        refreshError = null,
                        playbackJson = existing?.playbackJson ?: "{}",
                    )
                    podcast to parsed.episodes.map { it.toEntity(podcast) }
                }
            }
            val (podcast, episodes) = bundle
            db.withTransaction {
                db.podcasts().upsert(podcast)
                episodes.chunked(FeedRefresh.EPISODE_BATCH).forEach { chunk ->
                    db.episodes().upsertAll(chunk)
                }
            }
            runCatching { artwork.ensure(podcast.imageUrl) }
            return podcast
        } catch (timeout: TimeoutCancellationException) {
            existing?.let { markFailed(it.id, "Zeitüberschreitung") }
            throw FeedParseException("Zeitüberschreitung", timeout)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val guid = existing?.podcastGuid
            if (!triedGuid && !guid.isNullOrBlank()) {
                val keys = settings.read()
                val hit = runCatching { index.byGuid(keys.podcastIndexKey, keys.podcastIndexSecret, guid) }.getOrNull()
                val next = hit?.url?.let { EpisodeKeys.normalizeFeedUrl(it) }
                if (!next.isNullOrBlank() && next != url) {
                    db.podcasts().updateFeedUrl(existing.id, next)
                    return refreshUrl(next, markNeedsSync, triedGuid = true)
                }
            }
            val message = (error as? FeedParseException)?.message ?: error.message
            existing?.let { markFailed(it.id, message) }
            if (error is FeedParseException) throw error
            throw FeedParseException(message ?: "Feed konnte nicht geladen werden", error)
        }
    }

    private fun stub(url: String, title: String) = PodcastEntity(
        id = EpisodeKeys.podcastId(null, url),
        feedUrl = url,
        title = title,
        description = "",
        imageUrl = null,
        podcastGuid = null,
        author = null,
        link = null,
        personsJson = "[]",
        lastFetchedAt = 0,
        needsSync = true,
        refreshStatus = FeedRefresh.PENDING,
        refreshError = null,
    )

    private fun isStale(podcast: PodcastEntity): Boolean =
        System.currentTimeMillis() - podcast.lastFetchedAt > SyncPolicy.FEED_STALE_MS

    private fun normalizeHttp(rawUrl: String): String {
        val url = EpisodeKeys.normalizeFeedUrl(rawUrl)
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw FeedParseException("Die Adresse muss mit http:// oder https:// beginnen")
        }
        return url
    }
}
