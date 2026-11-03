package de.jenz.nahtlos.playlist

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Material's date picker reports UTC midnight. Playback dates are real instants.
 * The playlist rule uses the start of that calendar day in the device zone.
 */
object PlaylistClock {
    private val label: DateTimeFormatter = DateTimeFormatter.ofPattern("d. MMM yyyy", Locale.GERMAN)

    fun fromPickerUtc(utcMidnightMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val date = Instant.ofEpochMilli(utcMidnightMillis).atZone(ZoneOffset.UTC).toLocalDate()
        return date.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    fun startOfLocalDayContaining(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    fun daysAgoStart(days: Int, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Long =
        startOfLocalDayContaining(now - days.coerceAtLeast(0) * 86_400_000L, zone)

    fun format(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMs).atZone(zone).format(label)
}
