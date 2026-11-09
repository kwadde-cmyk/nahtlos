package de.jenz.nahtlos.playback

/**
 * Nach einem Source-Error: einmal die aufgelöste Adresse spielen, sonst die Folge verlassen.
 * Dieselbe Adresse noch einmal zu starten würde nur die Fehlschleife wiederholen.
 */
object PlaybackRecovery {
    const val REPLAY = "replay"
    const val SKIP = "skip"

    fun action(kind: HeaderKind, mime: String?, currentUrl: String, finalUrl: String): String {
        if (kind == HeaderKind.PLAYLIST || mime == AudioSource.HLS) return REPLAY
        if (kind == HeaderKind.AUDIO && !sameResource(currentUrl, finalUrl)) return REPLAY
        return SKIP
    }

    fun replayMime(kind: HeaderKind, sniffedMime: String?): String? {
        if (kind == HeaderKind.PLAYLIST || sniffedMime == AudioSource.HLS) return AudioSource.HLS
        if (!sniffedMime.isNullOrBlank() && (sniffedMime.startsWith("audio/") || sniffedMime.startsWith("video/"))) {
            return sniffedMime
        }
        if (kind == HeaderKind.AUDIO) return "audio/mpeg"
        return null
    }

    private fun sameResource(currentUrl: String, finalUrl: String): Boolean =
        currentUrl.substringBefore('?').trimEnd('/')
            .equals(finalUrl.substringBefore('?').trimEnd('/'), ignoreCase = true)
}
