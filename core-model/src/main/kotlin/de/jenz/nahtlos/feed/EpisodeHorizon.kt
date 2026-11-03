package de.jenz.nahtlos.feed

/**
 * How far back a refresh keeps episodes from the feed.
 * Episodes already stored (playlist entries, saved positions) are not removed.
 */
enum class EpisodeHorizon(val id: String, val label: String, val days: Int?) {
    WEEK_1("1w", "1 Woche", 7),
    WEEK_2("2w", "2 Wochen", 14),
    WEEK_4("4w", "4 Wochen", 28),
    MONTH_3("3m", "3 Monate", 92),
    MONTH_6("6m", "6 Monate", 183),
    YEAR_1("1y", "1 Jahr", 365),
    ALL("all", "Alle", null),
    ;

    fun cutoffEpochMs(nowEpochMs: Long): Long? = days?.let { nowEpochMs - it * DAY_MS }

    companion object {
        val DEFAULT = WEEK_4
        private const val DAY_MS = 86_400_000L

        fun fromId(id: String?): EpisodeHorizon = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
