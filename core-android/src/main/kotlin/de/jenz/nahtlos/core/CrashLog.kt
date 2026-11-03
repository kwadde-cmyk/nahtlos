package de.jenz.nahtlos.core

import android.content.Context
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/**
 * Writes the last uncaught exception to app-private storage so it can be shown
 * without logcat. The previous handler still runs, so the process still crashes.
 */
object CrashLog {
    private const val FILE_NAME = "last-crash.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                write(app, thread, error)
                AppLog.install(app)
                AppLog.record("Absturz", error.message ?: error.javaClass.simpleName, error)
            } catch (_: Throwable) {
                // Never hide the original crash behind a logging failure.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(context: Context): String? {
        val file = context.applicationContext.filesDir.resolve(FILE_NAME)
        if (!file.isFile || file.length() == 0L) return null
        return file.readText().take(20_000)
    }

    fun clear(context: Context) {
        context.applicationContext.filesDir.resolve(FILE_NAME).delete()
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val buffer = StringWriter()
        buffer.append(Instant.now().toString()).append('\n')
        buffer.append(thread.name).append('\n')
        error.printStackTrace(PrintWriter(buffer))
        context.filesDir.resolve(FILE_NAME).writeText(buffer.toString())
    }
}
