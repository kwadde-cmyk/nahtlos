package de.jenz.weiterhoeren.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d. MMM yyyy", Locale.GERMAN)

fun formatClock(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000).toInt()
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

fun formatDate(epochMs: Long?): String {
    if (epochMs == null || epochMs <= 0) return ""
    return Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(dateFormatter)
}

fun formatSyncTime(epochMs: Long?): String {
    if (epochMs == null || epochMs <= 0) return "noch nie"
    val time = DateTimeFormatter.ofPattern("d. MMM, HH:mm", Locale.GERMAN)
    return Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(time)
}
