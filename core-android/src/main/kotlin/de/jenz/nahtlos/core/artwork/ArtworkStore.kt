package de.jenz.nahtlos.core.artwork

import android.content.Context
import android.net.Uri
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import dagger.hilt.android.qualifiers.ApplicationContext
import de.jenz.nahtlos.feed.EpisodeKeys
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ArtworkStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
) {
    private val dir = File(context.filesDir, "artwork").apply { mkdirs() }

    fun fileFor(url: String): File = File(dir, EpisodeKeys.sha256(url).take(40) + ".img")

    fun contentUri(url: String?): Uri? {
        if (url.isNullOrBlank()) return null
        val file = fileFor(url)
        if (!file.isFile || file.length() == 0L) return null
        return Uri.parse("content://${context.packageName}.artwork/img/${file.name}")
    }

    /** Prefer the local content URI so AAOS does not download the cover again. */
    fun uriForBrowser(url: String?): Uri? = contentUri(url) ?: url?.let(Uri::parse)

    suspend fun ensure(url: String?) {
        if (url.isNullOrBlank() || !url.startsWith("http")) return
        val target = fileFor(url)
        if (target.isFile && target.length() > 0L) return
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val response = client.newCall(
                    Request.Builder().url(url).header("User-Agent", de.jenz.nahtlos.core.net.Net.USER_AGENT).build(),
                ).execute()
                response.use {
                    if (!it.isSuccessful) return@use
                    val body = it.body ?: return@use
                    val tmp = File(target.path + ".part")
                    body.byteStream().use { input -> tmp.outputStream().use { output -> input.copyTo(output) } }
                    if (tmp.length() in 1..MAX_BYTES) tmp.renameTo(target) else tmp.delete()
                }
            }
        }
    }

    companion object {
        const val MAX_BYTES = 4L * 1024 * 1024
        const val DISK_CACHE_BYTES = 250L * 1024 * 1024

        fun installCoil(context: Context) {
            val loader = ImageLoader.Builder(context)
                .memoryCache {
                    MemoryCache.Builder(context)
                        .maxSizePercent(0.25)
                        .build()
                }
                .diskCache {
                    DiskCache.Builder()
                        .directory(context.cacheDir.resolve("coil-artwork"))
                        .maxSizeBytes(DISK_CACHE_BYTES)
                        .build()
                }
                .respectCacheHeaders(false)
                .crossfade(false)
                .build()
            coil.Coil.setImageLoader(loader)
        }
    }
}
