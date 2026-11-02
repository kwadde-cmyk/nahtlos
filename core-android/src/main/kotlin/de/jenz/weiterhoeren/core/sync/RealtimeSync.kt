package de.jenz.weiterhoeren.core.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class RealtimeSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sync: SyncRepository,
    private val settings: SettingsStore,
    private val okHttp: OkHttpClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        val manager = context.getSystemService(ConnectivityManager::class.java)
        runCatching {
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope.launch { sync.onNetworkAvailable() }
                }
            })
        }.onFailure { error -> Log.w(TAG, "Netzwerk-Callback nicht registriert", error) }
        scope.launch { socketLoop() }
    }

    private suspend fun socketLoop() {
        var backoff = 1_000L
        while (scope.isActive) {
            val current = settings.read()
            if (!current.configured) {
                delay(3_000)
                continue
            }
            try {
                awaitSocket(current)
                backoff = 1_000L
            } catch (e: Exception) {
                Log.d(TAG, "WebSocket zu: ${e.message}")
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    private suspend fun awaitSocket(current: ServerSettings) = suspendCancellableCoroutine { continuation ->
        val token = URLEncoder.encode(current.token, Charsets.UTF_8.name())
        val request = Request.Builder()
            .url(webSocketUrl(current.serverUrl, token))
            .header("Authorization", "Bearer ${current.token}")
            .build()
        val socket = okHttp.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch { sync.onSocketMessage(text) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (continuation.isActive) continuation.resume(Unit)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (continuation.isActive) continuation.resumeWithException(t)
            }
        })
        continuation.invokeOnCancellation { socket.cancel() }
    }

    private fun webSocketUrl(base: String, encodedToken: String): String {
        val trimmed = base.trimEnd('/')
        val ws = when {
            trimmed.startsWith("https://") -> "wss://" + trimmed.removePrefix("https://")
            trimmed.startsWith("http://") -> "ws://" + trimmed.removePrefix("http://")
            else -> throw IllegalArgumentException("Server-URL")
        }
        return "$ws/v1/stream?token=$encodedToken"
    }

    companion object {
        private const val TAG = "Weiterhoeren"
    }
}
