package de.jenz.nahtlos.core

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.jenz.nahtlos.core.artwork.ArtworkStore
import de.jenz.nahtlos.core.feed.PodcastIndexClient
import de.jenz.nahtlos.core.playlist.DownloadStore
import de.jenz.nahtlos.core.db.NahtlosDatabase
import de.jenz.nahtlos.core.feed.FeedRefresh
import de.jenz.nahtlos.core.feed.FeedRepository
import de.jenz.nahtlos.core.net.Net
import de.jenz.nahtlos.core.sync.SettingsStore
import javax.inject.Provider
import de.jenz.nahtlos.feed.ChapterJsonParser
import de.jenz.nahtlos.feed.RssFeedParser
import de.jenz.nahtlos.opml.Opml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.zip.GZIPInputStream

/**
 * Runs on an Android runtime, where the JDK XML parser is not the one the app uses.
 * The import path must store titles only; the recorded Einundzwanzig body is the feed
 * that previously dominated memory.
 */
@RunWith(AndroidJUnit4::class)
class ImportOnDeviceTest {
    @Test
    fun streamingParserHandlesRecordedEinundzwanzigFeed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bytes = GZIPInputStream(context.assets.open("einundzwanzig.xml.gz")).use { it.readBytes() }
        val started = android.os.SystemClock.elapsedRealtime()
        val feed = RssFeedParser().parse(
            bytes.inputStream().reader(Charsets.UTF_8),
            RssFeedParser.MAX_EPISODES_PER_REFRESH,
        )
        val elapsed = android.os.SystemClock.elapsedRealtime() - started
        assertTrue(feed.title.contains("Einundzwanzig"))
        assertEquals(RssFeedParser.MAX_EPISODES_PER_REFRESH, feed.episodes.size)
        assertTrue(feed.episodes.all { it.enclosureUrl.startsWith("http") })
        assertTrue("parse took ${elapsed}ms", elapsed < 20_000)
    }

    @Test
    fun hoppAndPocketCastsImportsStoreTitlesWithoutDownloading() = runBlocking(Dispatchers.IO) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, NahtlosDatabase::class.java).build()
        val http = OkHttpClient.Builder().addInterceptor {
            throw IOException("Import darf noch keinen Feed laden")
        }.build()
        val repo = FeedRepository(
            db,
            Net(http),
            Provider { RssFeedParser() },
            ChapterJsonParser(),
            SettingsStore(context),
            ArtworkStore(context, http),
            DownloadStore(context, db),
            PodcastIndexClient(Net(http)),
        )
        try {
            for (name in listOf("hopp-export.xml", "pocketcasts-export.xml")) {
                withContext(Dispatchers.IO) { db.clearAllTables() }
                val xml = context.assets.open(name).bufferedReader().use { it.readText() }
                val count = repo.rememberOutlines(Opml.parse(xml))
                assertEquals(name, 36, count)
                val podcasts = db.podcasts().all()
                assertEquals(36, podcasts.size)
                assertTrue(podcasts.all { it.refreshStatus == FeedRefresh.PENDING && it.title.isNotBlank() && it.feedUrl.startsWith("http") })
                assertTrue(podcasts.all { db.episodes().keysForPodcast(it.id).isEmpty() })
            }
        } finally {
            db.close()
        }
    }
}
