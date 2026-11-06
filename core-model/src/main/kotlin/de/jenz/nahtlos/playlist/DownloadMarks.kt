package de.jenz.nahtlos.playlist

/** Sichtbarer Download-Zustand einer Folge. Kein Icon ohne Text. */
object DownloadMarks {
    fun caption(onDevice: Boolean, activeWritten: Long?, activeTotal: Long?): String {
        if (activeWritten != null) {
            val percent = percent(activeWritten, activeTotal ?: -1L)
            return if (percent == null) "Lädt…" else "Lädt $percent %"
        }
        return if (onDevice) "Auf dem Gerät" else "Nicht heruntergeladen"
    }

    fun fraction(written: Long, total: Long): Float? {
        if (total <= 0L || written < 0L) return null
        return (written.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }

    fun percent(written: Long, total: Long): Int? {
        if (total <= 0L) return null
        return ((written * 100) / total).toInt().coerceIn(0, 99)
    }
}
