package de.jenz.nahtlos.core.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ControllerInfo
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import de.jenz.nahtlos.core.feed.FeedRepository
import de.jenz.nahtlos.core.playlist.DownloadScheduler
import de.jenz.nahtlos.core.playlist.PlaylistRepository
import de.jenz.nahtlos.core.sync.SettingsStore
import de.jenz.nahtlos.core.sync.SyncRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@UnstableApi
@AndroidEntryPoint
class PodcastMediaLibraryService : MediaLibraryService() {
    @Inject lateinit var browser: LibraryBrowser
    @Inject lateinit var sync: SyncRepository
    @Inject lateinit var settings: SettingsStore
    @Inject lateinit var playbackCache: PlaybackCache
    @Inject lateinit var playlists: PlaylistRepository
    @Inject lateinit var downloads: DownloadScheduler
    @Inject lateinit var feeds: FeedRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: ExoPlayer? = null
    private var librarySession: MediaLibrarySession? = null
    private var playbackSync: PlaybackSync? = null
    private var effects: PlaybackEffects? = null

    override fun onCreate() {
        super.onCreate()
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(playbackCache.dataSourceFactory()))
            .setLoadControl(ConfigurableLoadControl { settings.read().bufferAheadMinutes })
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(settings.read().skipBackSec * 1000L)
            .setSeekForwardIncrementMs(settings.read().skipForwardSec * 1000L)
            .build()
        player = exo
        val fx = PlaybackEffects(exo, settings, feeds, scope)
        effects = fx
        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                fx.applyForCurrentItem()
            }
        })
        scope.launch {
            while (isActive) {
                delay(2_000)
                fx.tickOutro()
            }
        }
        val syncer = PlaybackSync(exo, sync, scope) { episodeKey ->
            scope.launch {
                val next = runCatching { playlists.onEpisodeFinished(episodeKey) }.getOrNull()
                if (next != null) {
                    val item = browser.resolvePlayable(next)
                    val position = runCatching { sync.positionForPlayback(next) }.getOrDefault(0L)
                    if (item != null) {
                        withContext(Dispatchers.Main) {
                            val current = player ?: return@withContext
                            playbackSync?.suppressPushes()
                            current.setMediaItem(item, position)
                            current.prepare()
                            current.play()
                        }
                    }
                }
                downloads.enqueue()
            }
        }
        playbackSync = syncer
        syncer.start()
        val sessionActivity = packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
            PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        librarySession = MediaLibrarySession.Builder(this, exo, LibraryCallback())
            .setId("nahtlos")
            .apply { if (sessionActivity != null) setSessionActivity(sessionActivity) }
            .build()
        scope.launch(Dispatchers.IO) {
            runCatching { sync.pullAndMerge(force = true) }
            warmContinueListening()
        }
    }

    /**
     * On AAOS, prepare the continue-listening episode without playing it.
     * The load control then fills the configured window into the disk cache
     * while the modem is still up.
     */
    private suspend fun warmContinueListening() {
        if (!packageManager.hasSystemFeature(AUTOMOTIVE) || !networkAvailable()) return
        val warm = runCatching { browser.continueToWarm() }.getOrNull() ?: return
        val (item, positionMs) = warm
        withContext(Dispatchers.Main) {
            val exo = player ?: return@withContext
            if (exo.isPlaying || exo.mediaItemCount > 0) return@withContext
            playbackSync?.suppressPushes()
            exo.setMediaItem(item, positionMs)
            exo.playWhenReady = false
            exo.prepare()
        }
    }

    private fun networkAvailable(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun onGetSession(controllerInfo: ControllerInfo): MediaLibrarySession? = librarySession

    override fun onTaskRemoved(rootIntent: Intent?) {
        playbackSync?.flushBlocking()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        playbackSync?.flushBlocking()
        effects?.release()
        librarySession?.release()
        librarySession = null
        player?.release()
        player = null
        scope.cancel()
        super.onDestroy()
    }

    private fun carButtons(): ImmutableList<CommandButton> = ImmutableList.of(
        actionButton("Zurück", SessionActions.SKIP_BACK),
        actionButton("Vor", SessionActions.SKIP_FORWARD),
        actionButton("Langsamer", SessionActions.SPEED_DOWN),
        actionButton("Schneller", SessionActions.SPEED_UP),
        actionButton("Kapitel zurück", SessionActions.CHAPTER_PREV),
        actionButton("Nächstes Kapitel", SessionActions.CHAPTER_NEXT),
    )

    private fun actionButton(name: String, action: String): CommandButton =
        CommandButton.Builder()
            .setDisplayName(name)
            .setSessionCommand(SessionCommand(action, Bundle.EMPTY))
            .build()

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: ControllerInfo): MediaSession.ConnectionResult {
            val base = super.onConnect(session, controller)
            if (!base.isAccepted) return base
            val commands = base.availableSessionCommands.buildUpon()
            listOf(
                SessionActions.SPEED_DOWN,
                SessionActions.SPEED_UP,
                SessionActions.SKIP_BACK,
                SessionActions.SKIP_FORWARD,
                SessionActions.CHAPTER_PREV,
                SessionActions.CHAPTER_NEXT,
                SessionActions.SILENCE,
                SessionActions.BOOST,
                SessionActions.INTRO,
                SessionActions.OUTRO,
            ).forEach { commands.add(SessionCommand(it, Bundle.EMPTY)) }
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands.build())
                .setAvailablePlayerCommands(base.availablePlayerCommands)
                .setCustomLayout(carButtons())
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                SessionActions.SPEED_DOWN -> effects?.nudgeSpeed(-1)
                SessionActions.SPEED_UP -> effects?.nudgeSpeed(1)
                SessionActions.SKIP_BACK -> effects?.skip(forward = false)
                SessionActions.SKIP_FORWARD -> effects?.skip(forward = true)
                SessionActions.CHAPTER_PREV -> effects?.jumpChapter(-1)
                SessionActions.CHAPTER_NEXT -> effects?.jumpChapter(1)
                SessionActions.SILENCE -> effects?.toggleSilence()
                SessionActions.BOOST -> effects?.toggleBoost()
                SessionActions.INTRO -> effects?.bumpTrim(intro = true)
                SessionActions.OUTRO -> effects?.bumpTrim(intro = false)
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val automotive = packageManager.hasSystemFeature(AUTOMOTIVE)
            if (automotive && !settings.read().configured) {
                return Futures.immediateFuture(signInRequired())
            }
            scope.launch(Dispatchers.IO) { runCatching { sync.pullAndMerge() } }
            return Futures.immediateFuture(LibraryResult.ofItem(this@PodcastMediaLibraryService.browser.rootItem(), params))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future(Dispatchers.IO) {
            val item = this@PodcastMediaLibraryService.browser.resolvePlayable(mediaId)
                ?: this@PodcastMediaLibraryService.browser.children(LibraryIds.ROOT).firstOrNull { it.mediaId == mediaId }
            if (item == null) LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            else LibraryResult.ofItem(item, null)
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future(Dispatchers.IO) {
            val children = this@PodcastMediaLibraryService.browser.children(parentId)
            LibraryResult.ofItemList(pageOf(children, page, pageSize), params)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<List<MediaItem>> = scope.future(Dispatchers.IO) {
            mediaItems.map { item ->
                this@PodcastMediaLibraryService.browser.resolvePlayable(item.mediaId) ?: item
            }
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future(Dispatchers.IO) {
            val resolved = mediaItems.map { item ->
                this@PodcastMediaLibraryService.browser.resolvePlayable(item.mediaId) ?: item
            }
            val index = if (startIndex == C.INDEX_UNSET) 0 else startIndex.coerceIn(0, (resolved.size - 1).coerceAtLeast(0))
            val target = resolved.getOrNull(index)
            val position = if (startPositionMs == C.TIME_UNSET) {
                target?.mediaId?.let { id ->
                    if (LibraryIds.isFolder(id)) 0L else sync.positionForPlayback(id)
                } ?: 0L
            } else {
                startPositionMs
            }
            MediaSession.MediaItemsWithStartPosition(resolved, index, position)
        }
    }

    private fun signInRequired(): LibraryResult<MediaItem> {
        val extras = Bundle()
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        if (launch != null) {
            val pending = PendingIntent.getActivity(
                this,
                2,
                launch,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            extras.putString(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL_COMPAT, "Server einrichten")
            extras.putParcelable(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT_COMPAT, pending)
        }
        val params = LibraryParams.Builder().setExtras(extras).build()
        return LibraryResult.ofError(LibraryResult.RESULT_ERROR_SESSION_AUTHENTICATION_EXPIRED, params)
    }

    private fun pageOf(items: List<MediaItem>, page: Int, pageSize: Int): List<MediaItem> {
        if (items.isEmpty() || pageSize <= 0 || page < 0) return if (page == 0) items else emptyList()
        val from = page.toLong() * pageSize.toLong()
        if (from >= items.size) return emptyList()
        val to = minOf(items.size.toLong(), from + pageSize.toLong()).toInt()
        return items.subList(from.toInt(), to)
    }

    companion object {
        private const val AUTOMOTIVE = "android.hardware.type.automotive"
    }
}
