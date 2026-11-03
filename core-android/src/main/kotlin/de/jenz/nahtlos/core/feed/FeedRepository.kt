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
import de.jenz.nahtlos.core.net.Net
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
        if (inline.isNotEmpty() || episode.chaptersUrl.isNullOrBlank()) return@withContext inline
        val fetched = try {
            withTimeout(12_000) {
                chapters.parse(net.getText(episode.chaptersUrl))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
        if (fetched.isNotEmpty()) {
            db.episodes().updateChapters(episodeKey, NahtlosJson.json.encodeToString(fetched))
        }
        fetched
    }

    private suspend fun refreshUrl(url: String, markNeedsSync: Boolean): PodcastEntity {
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
                    ).copy(refreshStatus = FeedRefresh.OK, refreshError = null)
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
            return podcast
        } catch (timeout: TimeoutCancellationException) {
            existing?.let { markFailed(it.id, "Zeitüberschreitung") }
            throw FeedParseException("Zeitüberschreitung", timeout)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
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
