package de.jenz.nahtlos.core.feed

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
import de.jenz.nahtlos.feed.ChapterJsonParser
import de.jenz.nahtlos.feed.EpisodeKeys
import de.jenz.nahtlos.feed.RssFeedParser
import de.jenz.nahtlos.model.Chapter
import de.jenz.nahtlos.model.FeedParseException
import de.jenz.nahtlos.model.NahtlosJson
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FeedRepository @Inject constructor(
    private val db: NahtlosDatabase,
    private val net: Net,
    private val parser: RssFeedParser,
    private val chapters: ChapterJsonParser,
) {
    fun observePodcasts(): Flow<List<PodcastEntity>> = db.podcasts().observeAll()

    fun observePodcast(id: String): Flow<PodcastEntity?> = db.podcasts().observe(id)

    fun observeEpisodes(podcastId: String): Flow<List<EpisodeRow>> = db.episodes().observeForPodcast(podcastId)

    fun observeContinue(): Flow<List<ContinueRow>> = db.progress().observeContinue()

    suspend fun getPodcast(id: String): PodcastEntity? = db.podcasts().get(id)

    suspend fun getEpisode(episodeKey: String): EpisodeEntity? = db.episodes().get(episodeKey)

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
        if (!force && !isStale(podcast)) return
        runCatching { refresh(podcastId) }
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
        val fetched = runCatching {
            withTimeout(12_000) {
                chapters.parse(net.getText(episode.chaptersUrl))
            }
        }.getOrDefault(emptyList())
        if (fetched.isNotEmpty()) {
            db.episodes().updateChapters(episodeKey, NahtlosJson.json.encodeToString(fetched))
        }
        fetched
    }

    private suspend fun refreshUrl(url: String, markNeedsSync: Boolean): PodcastEntity {
        val xml = try {
            withTimeout(20_000) { net.getText(url) }
        } catch (e: Exception) {
            throw FeedParseException(e.message ?: "Feed konnte nicht geladen werden", e)
        }
        val parsed = parser.parse(xml)
        val podcast = parsed.toPodcast(url, markNeedsSync, System.currentTimeMillis())
        db.podcasts().upsert(podcast)
        db.episodes().upsertAll(parsed.episodes.map { it.toEntity(podcast) })
        return podcast
    }

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
