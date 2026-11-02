package de.jenz.nahtlos.core.playback

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import dagger.hilt.android.qualifiers.ApplicationContext
import de.jenz.nahtlos.core.net.Net
import de.jenz.nahtlos.core.sync.SettingsStore
import de.jenz.nahtlos.playback.PlaybackCachePolicy
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One process-wide disk cache. ExoPlayer reads and writes through [dataSourceFactory],
 * so audio that was buffered stays playable after the network drops.
 * The cache is not released with the media service: a second session in the same
 * process must keep using the same [SimpleCache] instance.
 */
@UnstableApi
@Singleton
class PlaybackCache @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
) {
    private val lock = Any()
    private var cache: SimpleCache? = null

    fun dataSourceFactory(): DataSource.Factory {
        val upstream = DefaultHttpDataSource.Factory()
            .setUserAgent(Net.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(20_000)
        return CacheDataSource.Factory()
            .setCache(simpleCache())
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    private fun simpleCache(): SimpleCache {
        synchronized(lock) {
            cache?.let { return it }
            val directory = File(context.cacheDir, "nahtlos-playback")
            val bytes = PlaybackCachePolicy.diskCacheBytes(settings.read().bufferAheadMinutes)
            val created = SimpleCache(
                directory,
                LeastRecentlyUsedCacheEvictor(bytes),
                StandaloneDatabaseProvider(context),
            )
            cache = created
            return created
        }
    }
}
