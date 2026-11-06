package de.jenz.nahtlos.core.playback

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import dagger.hilt.android.qualifiers.ApplicationContext
import de.jenz.nahtlos.core.net.Net
import de.jenz.nahtlos.core.sync.SettingsStore
import de.jenz.nahtlos.playback.AudioSource
import de.jenz.nahtlos.playback.PlaybackCachePolicy
import android.net.Uri
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
        return CacheDataSource.Factory()
            .setCache(simpleCache())
            .setUpstreamDataSourceFactory(upstreamFactory(context))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    companion object {
        /** file://, content:// and http(s). A bare HTTP source cannot open a downloaded episode. */
        fun upstreamFactory(context: Context): DataSource.Factory {
            val http = DefaultHttpDataSource.Factory()
                .setUserAgent(Net.USER_AGENT)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(8_000)
                .setReadTimeoutMs(20_000)
            return DefaultDataSource.Factory(context, http)
        }
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

/**
 * Source errors stop after one extra attempt. A local file that cannot be opened
 * fails immediately so the session can switch once to the stream URL.
 */
@UnstableApi
class PlaybackLoadPolicy : DefaultLoadErrorHandlingPolicy(2) {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorInfo): Long {
        if (isLocalSourceFailure(loadErrorInfo.exception) || loadErrorInfo.errorCount >= 2) {
            return C.TIME_UNSET
        }
        return super.getRetryDelayMsFor(loadErrorInfo)
    }
}

/** Prefer a local file only when its header is progressive audio. HTML and playlists stream instead. */
fun playbackUri(local: File?, remote: String): Uri {
    if (local != null && AudioSource.isProgressiveAudio(local) && local.canRead()) {
        return Uri.fromFile(local)
    }
    return Uri.parse(remote)
}

internal fun isLocalSourceFailure(error: Throwable): Boolean {
    var current: Throwable? = error
    while (current != null) {
        if (current is ClassCastException) return true
        if (current.javaClass.name.endsWith("UnrecognizedInputFormatException")) return true
        val message = current.message.orEmpty()
        if ("FileURLConnection" in message) return true
        current = current.cause
    }
    return false
}
