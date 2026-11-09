package de.jenz.nahtlos.core.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.widget.Toast
import android.net.NetworkCapabilities
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ControllerInfo
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import de.jenz.nahtlos.core.AppLog
import de.jenz.nahtlos.core.artwork.ArtworkStore
import de.jenz.nahtlos.core.Payments
import de.jenz.nahtlos.core.feed.FeedRepository
import de.jenz.nahtlos.core.wallet.BoostService
import de.jenz.nahtlos.wallet.SecretScrub
import de.jenz.nahtlos.core.playlist.DownloadScheduler
import de.jenz.nahtlos.core.playlist.DownloadStore
import de.jenz.nahtlos.diagnose.DiagnoseText
import de.jenz.nahtlos.playback.AudioSource
import de.jenz.nahtlos.playback.HeaderKind
import de.jenz.nahtlos.playback.PlaybackRecovery
import de.jenz.nahtlos.core.net.Net
import okhttp3.OkHttpClient
import okhttp3.Request
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
    @Inject lateinit var downloadFiles: DownloadStore
    @Inject lateinit var http: OkHttpClient
    @Inject lateinit var feeds: FeedRepository
    @Inject lateinit var artwork: ArtworkStore
    @Inject lateinit var boosts: BoostService
    @Volatile private var currentMediaId: String? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: ExoPlayer? = null
    private var librarySession: MediaLibrarySession? = null
    private var playbackSync: PlaybackSync? = null
    private var effects: PlaybackEffects? = null
    private val fallingBack = mutableSetOf<String>()
    private val probed = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(playbackCache.dataSourceFactory()).also {
                    // HLS wird per Klassenname geladen. Die Referenz hält die Klasse im Release.
                    check(androidx.media3.exoplayer.hls.HlsMediaSource.Factory::class.java.name.isNotEmpty())
                }
                    .setLoadErrorHandlingPolicy(PlaybackLoadPolicy()),
            )
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
        val fx = PlaybackEffects(exo, settings, feeds, artwork, scope)
        effects = fx
        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                currentMediaId = mediaItem?.mediaId
                fx.applyForCurrentItem()
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                AppLog.record("Player", error.message ?: "Wiedergabe fehlgeschlagen", error)
                val current = player ?: return
                val item = current.currentMediaItem ?: return
                val uri = item.localConfiguration?.uri ?: return
                val scheme = uri.scheme?.lowercase()
                val position = current.currentPosition.coerceAtLeast(0L)
                if (scheme == "file" || scheme == "content") {
                    if (!fallingBack.add(item.mediaId)) return
                    scope.launch {
                        val remote = runCatching { browser.streamUri(item.mediaId) }.getOrNull()
                        runCatching { downloadFiles.delete(item.mediaId) }
                        withContext(Dispatchers.Main) {
                            val playerNow = player
                            if (remote == null || playerNow == null || playerNow.currentMediaItem?.mediaId != item.mediaId) {
                                fallingBack.remove(item.mediaId)
                                return@withContext
                            }
                            AppLog.record("Player", "Lokale Datei für ${item.mediaId} fehlgeschlagen, weiche auf den Stream aus.")
                            playbackSync?.suppressPushes()
                            playerNow.setMediaItem(playableItem(item, remote), position)
                            playerNow.prepare()
                            playerNow.play()
                        }
                    }.invokeOnCompletion { fallingBack.remove(item.mediaId) }
                    return
                }
                if ((scheme == "http" || scheme == "https") && unrecognized(error)) {
                    if (!probed.add(item.mediaId)) {
                        skipUnplayable(item, "Diese Folge lässt sich nicht abspielen und wird übersprungen.")
                        return
                    }
                    scope.launch(Dispatchers.IO) {
                        val peeked = peekStream(uri.toString())
                        val action = PlaybackRecovery.action(
                            kind = peeked?.kind ?: HeaderKind.UNKNOWN,
                            mime = peeked?.mime,
                            currentUrl = uri.toString(),
                            finalUrl = peeked?.finalUrl ?: uri.toString(),
                        )
                        withContext(Dispatchers.Main) {
                            val playerNow = player ?: return@withContext
                            if (playerNow.currentMediaItem?.mediaId != item.mediaId) return@withContext
                            if (action == PlaybackRecovery.REPLAY && peeked != null) {
                                val mime = PlaybackRecovery.replayMime(peeked.kind, peeked.mime)
                                val next = item.buildUpon().setUri(peeked.finalUrl)
                                if (mime != null) next.setMimeType(mime)
                                AppLog.record("Player", "Stream für ${item.mediaId} wird über die aufgelöste Adresse erneut gestartet.")
                                playbackSync?.suppressPushes()
                                playerNow.setMediaItem(next.build(), position)
                                playerNow.prepare()
                                playerNow.play()
                            } else {
                                skipUnplayable(item, "Diese Folge ist kein abspielbares Audio und wird übersprungen.")
                            }
                        }
                    }
                }
            }
        })
        scope.launch {
            while (isActive) {
                delay(2_000)
                fx.tickOutro()
                fx.tickChapter()
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
        if (librarySession == null) {
            librarySession = MediaLibrarySession.Builder(this, skipTransport(exo), LibraryCallback())
                .setId("nahtlos")
                .apply { if (sessionActivity != null) setSessionActivity(sessionActivity) }
                .build()
        }
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

    override fun onGetSession(controllerInfo: ControllerInfo): MediaLibrarySession? {
        val session = librarySession
        if (session == null) {
            AppLog.record("Player", "Keine Session für ${controllerInfo.packageName}")
        }
        return session
    }

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

    private fun playableItem(item: androidx.media3.common.MediaItem, remote: android.net.Uri): androidx.media3.common.MediaItem {
        val next = item.buildUpon().setUri(remote)
        val mime = AudioSource.mediaMime(null, remote.toString())
        if (mime != null) next.setMimeType(mime)
        return next.build()
    }

    private fun unrecognized(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current.javaClass.name.endsWith("UnrecognizedInputFormatException")) return true
            current = current.cause
        }
        return false
    }

    private data class StreamPeek(val finalUrl: String, val mime: String?, val kind: HeaderKind)

    private fun peekStream(url: String): StreamPeek? = runCatching {
        val request = Request.Builder().url(url).header("User-Agent", Net.USER_AGENT).header("Range", "bytes=0-511").build()
        http.newBuilder().callTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
            val buffer = ByteArray(512)
            val read = response.body?.byteStream()?.use { it.read(buffer) } ?: -1
            val bytes = if (read > 0) buffer.copyOf(read) else byteArrayOf()
            val type = response.header("Content-Type")
            val finalUrl = response.request.url.toString()
            val kind = AudioSource.headerKind(bytes)
            val mime = AudioSource.mediaMime(type, finalUrl)
            StreamPeek(finalUrl = finalUrl, mime = mime, kind = if (mime == AudioSource.HLS) HeaderKind.PLAYLIST else kind)
        }
    }.getOrNull()

    private fun skipUnplayable(item: androidx.media3.common.MediaItem, message: String) {
        val text = DiagnoseText.scrub(message).take(180)
        PlaybackNotice.show(text)
        AppLog.record("Player", text)
        scope.launch(Dispatchers.IO) {
            val next = runCatching { playlists.skipBroken(item.mediaId) }.getOrNull()
            val resolved = next?.let { runCatching { browser.resolvePlayable(it) }.getOrNull() }
            val position = next?.let { runCatching { sync.positionForPlayback(it) }.getOrDefault(0L) } ?: 0L
            withContext(Dispatchers.Main) {
                Toast.makeText(this@PodcastMediaLibraryService, text, Toast.LENGTH_LONG).show()
                val playerNow = player ?: return@withContext
                if (resolved == null) {
                    playerNow.stop()
                    return@withContext
                }
                playbackSync?.suppressPushes()
                playerNow.setMediaItem(resolved, position)
                playerNow.prepare()
                playerNow.play()
            }
        }
    }

    private val quickBoostBusy = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun quickBoost() {
        if (!Payments.enabled) return
        if (!de.jenz.nahtlos.sync.BoostSync.acceptTap(quickBoostBusy.get()) || !quickBoostBusy.compareAndSet(false, true)) {
            showBoost("Eine Zahlung läuft schon.")
            return
        }
        val key = player?.currentMediaItem?.mediaId
        val position = player?.currentPosition ?: 0L
        if (key.isNullOrBlank() || LibraryIds.isFolder(key)) {
            quickBoostBusy.set(false)
            showBoost("Boost nicht gesendet. Keine Folge in der Wiedergabe.")
            return
        }
        scope.launch(Dispatchers.IO) {
            val message = try {
                runCatching { boosts.quickBoost(key, position) }.fold(
                    { boosts.outcomeText(it) },
                    { CarControls.boostNotice(SecretScrub.message(it.message)) },
                )
            } finally {
                quickBoostBusy.set(false)
            }
            showBoost(message)
        }
    }

    private fun showBoost(message: String) {
        boosts.announce(message)
        AppLog.record("Wallet", message)
        scope.launch(Dispatchers.Main) {
            Toast.makeText(this@PodcastMediaLibraryService, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun carButtons(): ImmutableList<CommandButton> {
        val automotive = packageManager.hasSystemFeature(AUTOMOTIVE)
        return ImmutableList.copyOf(
            CarControls.buttons(payments = Payments.enabled, automotive = automotive).map { spec ->
                val builder = CommandButton.Builder(spec.icon)
                    .setDisplayName(spec.name)
                    .setSessionCommand(SessionCommand(spec.action, Bundle.EMPTY))
                if (spec.iconRes != 0) builder.setIconResId(spec.iconRes)
                if (spec.slots.isNotEmpty()) builder.setSlots(*spec.slots)
                builder.build()
            },
        )
    }

    private fun accepted(session: MediaSession): MediaSession.ConnectionResult {
        val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
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
        if (Payments.enabled) commands.add(SessionCommand(SessionActions.QUICK_BOOST, Bundle.EMPTY))
        val buttons = carButtons()
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(commands.build())
            .setAvailablePlayerCommands(Player.Commands.Builder().addAllCommands().build())
            .setCustomLayout(buttons)
            .setMediaButtonPreferences(buttons)
            .build()
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: ControllerInfo): MediaSession.ConnectionResult {
            val notification = controller.packageName == packageName &&
                controller.connectionHints.getBoolean(MediaController.KEY_MEDIA_NOTIFICATION_CONTROLLER_FLAG, false)
            val fallback = runCatching { super.onConnect(session, controller) }.getOrElse { error ->
                AppLog.record("Player", "Standard-onConnect für ${controller.packageName}: ${error.message}", error)
                null
            }
            val allowed = SessionConnection.accepts(
                controllerPackage = controller.packageName,
                ownPackage = packageName,
                trusted = controller.isTrusted,
                notificationController = notification,
                fallbackAccepted = fallback?.isAccepted == true,
            )
            if (!allowed) return MediaSession.ConnectionResult.reject()
            return runCatching { accepted(session) }.getOrElse { error ->
                AppLog.record(
                    "Player",
                    "Verbindung zu ${controller.packageName} ohne eigene Befehle: ${error.message}",
                    error,
                )
                MediaSession.ConnectionResult.AcceptedResultBuilder(session).build()
            }
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
                SessionActions.QUICK_BOOST -> quickBoost()
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            // Ein Fehler-Root wird vom Mediencenter von AAOS 15 als „No root“ verworfen.
            // Die Fläche bleibt dann leer, auch wenn Einstellungen schon funktionieren.
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

        override fun onSearch(
            session: MediaLibrarySession,
            browser: ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = Futures.immediateFuture(LibraryResult.ofVoid())

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future(Dispatchers.IO) {
            val found = runCatching { this@PodcastMediaLibraryService.browser.search(query) }.getOrElse { error ->
                AppLog.record("Player", "Suche ohne Netz: ${error.message}")
                emptyList()
            }
            LibraryResult.ofItemList(pageOf(found, page, pageSize), params)
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future(Dispatchers.IO) {
            val automotive = packageManager.hasSystemFeature(AUTOMOTIVE)
            val children = runCatching {
                if (automotive && parentId == LibraryIds.ROOT) automotiveRoot()
                else this@PodcastMediaLibraryService.browser.children(parentId)
            }.getOrElse { error ->
                AppLog.record(
                    "Bibliothek",
                    DiagnoseText.scrub("Ordner $parentId: ${error.javaClass.simpleName}: ${error.message}"),
                    error,
                )
                listOf(this@PodcastMediaLibraryService.browser.failureNote(parentId))
            }
            if (parentId == LibraryIds.ROOT || parentId == LibraryIds.PLAYLIST || parentId == LibraryIds.BROWSE || children.size <= 1) {
                AppLog.record("Bibliothek", "Ordner $parentId: ${children.size} Einträge")
            }
            LibraryResult.ofItemList(pageOf(children, page, pageSize), params)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<List<MediaItem>> = scope.future(Dispatchers.IO) {
            mediaItems.map { item -> resolveIncoming(item) }
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future(Dispatchers.IO) {
            val resolved = mediaItems.map { item -> resolveIncoming(item) }
            val index = if (startIndex == C.INDEX_UNSET) 0 else startIndex.coerceIn(0, (resolved.size - 1).coerceAtLeast(0))
            val target = resolved.getOrNull(index)
            val position = if (startPositionMs == C.TIME_UNSET) {
                target?.mediaId?.let { id ->
                    if (LibraryIds.isFolder(id)) 0L else sync.localPosition(id)
                } ?: 0L
            } else {
                startPositionMs
            }
            val resumeKey = if (startPositionMs == C.TIME_UNSET) target?.mediaId else null
            if (!resumeKey.isNullOrBlank() && !LibraryIds.isFolder(resumeKey)) {
                val uri = target?.localConfiguration?.uri
                if (uri == null || uri.toString().isBlank()) {
                    AppLog.record("Player", "Keine Audio-Adresse für $resumeKey")
                }
                scope.launch(Dispatchers.IO) {
                    val newer = runCatching { sync.newerRemotePosition(resumeKey) }.getOrNull() ?: return@launch
                    withContext(Dispatchers.Main) {
                        val exo = player ?: return@withContext
                        if (exo.currentMediaItem?.mediaId != resumeKey) return@withContext
                        if (kotlin.math.abs(exo.currentPosition - newer) < 1_500) return@withContext
                        playbackSync?.suppressPushes()
                        exo.seekTo(newer)
                    }
                }
            }
            MediaSession.MediaItemsWithStartPosition(resolved, index, position)
        }
    }

    private suspend fun automotiveRoot(): List<MediaItem> {
        if (!settings.read().configured) {
            return listOf(browser.setupNote(), browser.browseFolder())
        }
        val currentId = currentMediaId?.takeIf { !LibraryIds.isFolder(it) }
        val current = currentId?.let { browser.resolvePlayable(it) }
        return listOf(current ?: browser.emptyPlayback(), browser.browseFolder())
    }

    private suspend fun resolveIncoming(item: MediaItem): MediaItem {
        runCatching { browser.resolvePlayable(item.mediaId) }.getOrNull()?.let { return it }
        if (item.localConfiguration?.uri != null) return item
        val query = item.requestMetadata.searchQuery?.toString()?.trim().orEmpty()
            .ifEmpty { item.mediaId.trim() }
        return runCatching { browser.search(query).firstOrNull() }.getOrNull() ?: item
    }

    private fun pageOf(items: List<MediaItem>, page: Int, pageSize: Int): List<MediaItem> {
        if (items.isEmpty() || pageSize <= 0 || page < 0) return if (page == 0) items else emptyList()
        val from = page.toLong() * pageSize.toLong()
        if (from >= items.size) return emptyList()
        val to = minOf(items.size.toLong(), from + pageSize.toLong()).toInt()
        return items.subList(from.toInt(), to)
    }

    private fun skipTransport(player: Player) = SkipTransport(player) { forward ->
        val stored = settings.read()
        val seconds = if (forward) stored.skipForwardSec else stored.skipBackSec
        seconds.coerceAtLeast(0) * 1000L
    }

    companion object {
        private const val AUTOMOTIVE = "android.hardware.type.automotive"
    }
}
