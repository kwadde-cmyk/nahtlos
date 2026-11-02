package de.jenz.nahtlos.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class Net(private val client: OkHttpClient) {
    suspend fun getText(url: String, headers: Map<String, String> = emptyMap(), maxBytes: Int = 5_000_000): String {
        val bytes = execute(request(url, "GET", null, headers), maxBytes)
        return decodeXml(bytes)
    }

    /**
     * Hands the response body to [block] as a reader. The body is not buffered into one string.
     * [block] runs on the IO dispatcher and must finish before it returns; the stream closes afterwards.
     */
    suspend fun <T> read(url: String, maxBytes: Long = MAX_FEED_BYTES, block: (Reader) -> T): T =
        withContext(Dispatchers.IO) {
            val call = client.newCall(request(url, "GET", null, emptyMap()))
            call.timeout().timeout(45, TimeUnit.SECONDS)
            coroutineContext.job.invokeOnCompletion { error ->
                if (error != null) call.cancel()
            }
            runInterruptible {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body ?: throw IOException("Leere Antwort")
                    val source = body.source()
                    source.request(1)
                    val peek = source.peek()
                    peek.request(480)
                    val headLen = minOf(480L, peek.buffer.size).toInt()
                    val head = if (headLen > 0) peek.readByteArray(headLen.toLong()) else ByteArray(0)
                    val charset = charsetOf(head, body.contentType()?.charset(Charsets.UTF_8))
                    LimitedInputStream(source.inputStream(), maxBytes).use { limited ->
                        InputStreamReader(limited, charset).buffered().use { reader -> block(reader) }
                    }
                }
            }
        }

    suspend fun exchange(
        url: String,
        method: String,
        body: String?,
        headers: Map<String, String>,
    ): HttpResult {
        val request = request(url, method, body, headers)
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val text = it.body?.string().orEmpty()
                        if (continuation.isActive) {
                            continuation.resume(HttpResult(it.code, text))
                        }
                    }
                }
            })
        }
    }

    private suspend fun execute(request: Request, maxBytes: Int): ByteArray = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        call.timeout().timeout(30, TimeUnit.SECONDS)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(IOException("HTTP ${it.code}"))
                        }
                        return
                    }
                    val body = it.body
                    if (body == null) {
                        if (continuation.isActive) continuation.resume(ByteArray(0))
                        return
                    }
                    try {
                        val buffer = ByteArrayOutputStream()
                        body.byteStream().use { input ->
                            val chunk = ByteArray(8_192)
                            var total = 0
                            while (true) {
                                val read = input.read(chunk)
                                if (read < 0) break
                                total += read
                                if (total > maxBytes) throw IOException("Antwort ist größer als $maxBytes Bytes")
                                buffer.write(chunk, 0, read)
                            }
                        }
                        if (continuation.isActive) continuation.resume(buffer.toByteArray())
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            }
        })
    }

    private fun request(url: String, method: String, body: String?, headers: Map<String, String>): Request {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
        headers.forEach { (name, value) -> builder.header(name, value) }
        val requestBody = body?.toRequestBody(JSON)
        builder.method(method, if (method == "GET" || method == "DELETE") null else requestBody ?: ByteArray(0).toRequestBody(null))
        return builder.build()
    }

    companion object {
        const val USER_AGENT = "Nahtlos/0.1 (Android; podcast sync)"
        const val MAX_FEED_BYTES = 16L * 1024 * 1024
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun charsetOf(head: ByteArray, fallback: Charset?): Charset {
            val ascii = head.toString(Charsets.US_ASCII)
            val encoding = Regex("""encoding\s*=\s*["']([A-Za-z0-9._\-]+)["']""").find(ascii)?.groupValues?.get(1)
            val name = encoding ?: fallback?.name() ?: "UTF-8"
            return runCatching { Charset.forName(name) }.getOrDefault(Charsets.UTF_8)
        }

        fun decodeXml(bytes: ByteArray): String {
            val head = bytes.copyOfRange(0, minOf(bytes.size, 240)).toString(Charsets.US_ASCII)
            val encoding = Regex("""encoding=["']([A-Za-z0-9_\-]+)["']""").find(head)?.groupValues?.get(1)
            val charset = runCatching { Charset.forName(encoding ?: "UTF-8") }.getOrDefault(Charsets.UTF_8)
            return bytes.toString(charset).removePrefix("\uFEFF")
        }
    }
}

data class HttpResult(val code: Int, val body: String)

private class LimitedInputStream(
    input: InputStream,
    private val maxBytes: Long,
) : FilterInputStream(input) {
    private var total = 0L

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) count(1)
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val read = super.read(b, off, len)
        if (read > 0) count(read.toLong())
        return read
    }

    private fun count(n: Long) {
        total += n
        if (total > maxBytes) throw IOException("Antwort ist größer als $maxBytes Bytes")
    }
}
