package de.jenz.nahtlos.core

import android.content.Context
import de.jenz.nahtlos.diagnose.DiagnoseText
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/**
 * Rolling sync and playback log in app-private storage. Settings shows it under Diagnose
 * so a phone without logcat can still send what the server or the player did.
 */
object AppLog {
    private const val FILE_NAME = "diagnose.txt"
    private const val MAX_CHARS = 48_000

    @Volatile
    private var app: Context? = null

    fun install(context: Context) {
        app = context.applicationContext
    }

    fun record(tag: String, message: String, error: Throwable? = null) {
        val context = app ?: return
        val line = buildString {
            append(Instant.now().toString())
            append(' ')
            append(tag)
            append(' ')
            append(message.trim())
            if (error != null) {
                append('\n')
                val buffer = StringWriter()
                error.printStackTrace(PrintWriter(buffer))
                append(buffer.toString().take(1_500))
            }
            append('\n')
        }
        val safe = DiagnoseText.scrub(line)
        synchronized(this) {
            val file = context.filesDir.resolve(FILE_NAME)
            val previous = if (file.isFile) file.readText() else ""
            file.writeText((previous + safe).takeLast(MAX_CHARS))
        }
    }

    fun read(context: Context): String? {
        val file = context.applicationContext.filesDir.resolve(FILE_NAME)
        if (!file.isFile || file.length() == 0L) return null
        return DiagnoseText.scrub(file.readText()).take(MAX_CHARS)
    }

    fun clear(context: Context) {
        context.applicationContext.filesDir.resolve(FILE_NAME).delete()
    }
}
