package de.jenz.nahtlos.core

import android.content.Context
import de.jenz.nahtlos.diagnose.DiagnoseText
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Rolling sync and playback log in app-private storage. Settings shows it under Diagnose
 * so a phone without logcat can still send what the server or the player did.
 *
 * Behält nur die letzten 5 Minuten.
 */
object AppLog {
    private const val FILE_NAME = "diagnose.txt"
    private const val RETENTION_MINUTES = 5L
    private const val MAX_CHARS = 48_000 // Sicherheitsnetz, falls sehr viele Events in 5 Min

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
            val cutoff = Instant.now().minus(RETENTION_MINUTES, ChronoUnit.MINUTES)
            val kept = keepRecent(previous, cutoff)
            val next = (kept + safe).takeLast(MAX_CHARS)
            file.writeText(next)
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

    /**
     * Behält nur Einträge, deren Start-Zeitstempel nicht vor dem Cutoff liegt.
     * Ein Eintrag beginnt mit einer Zeile, die mit einem ISO-Instant beginnt.
     */
    private fun keepRecent(text: String, cutoff: Instant): String {
        if (text.isBlank()) return ""
        val lines = text.lines()
        val result = StringBuilder()
        var current = StringBuilder()
        var entryStart: Instant? = null

        fun flush() {
            if (entryStart != null && !entryStart!!.isBefore(cutoff) && current.isNotEmpty()) {
                result.append(current)
            }
            current = StringBuilder()
            entryStart = null
        }

        for (line in lines) {
            val ts = tryParseTimestamp(line)
            if (ts != null) {
                flush()
                entryStart = ts
            }
            if (current.isNotEmpty() || ts != null) {
                current.append(line).append('\n')
            }
        }
        flush()
        return result.toString()
    }

    private fun tryParseTimestamp(line: String): Instant? {
        val space = line.indexOf(' ')
        if (space < 10) return null
        val candidate = line.substring(0, space)
        return try {
            Instant.parse(candidate)
        } catch (_: Exception) {
            null
        }
    }
}
