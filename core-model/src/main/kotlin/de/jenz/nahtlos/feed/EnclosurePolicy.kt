package de.jenz.nahtlos.feed

import de.jenz.nahtlos.model.AlternateSource
import de.jenz.nahtlos.playback.AudioSource

/**
 * Die reguläre `<enclosure>` ist die Wiedergabe-Adresse.
 * `podcast:alternateEnclosure` ist nur ein Audio-Fallback und ersetzt die Enclosure nie,
 * auch nicht bei gleicher oder höherer Bitrate. Video und Seiten wie YouTube sind keine Datei.
 */
object EnclosurePolicy {
    data class Chosen(val url: String, val type: String?, val sources: List<AlternateSource>)

    fun shouldKeepEnclosure(
        url: String?,
        type: String?,
        bitrate: Int,
        currentUrl: String?,
        currentBitrate: Int,
    ): Boolean {
        if (!isPlayable(url, type)) return false
        if (currentUrl == null) return true
        return bitrate > currentBitrate
    }

    fun isAudioFallback(url: String?, type: String?): Boolean {
        if (!isPlayable(url, type)) return false
        val mime = mime(type)
        if (mime.startsWith("audio/")) return true
        if (AudioSource.mediaMime(mime, url.orEmpty()) == AudioSource.HLS) return true
        return mime.isEmpty() && hasAudioExtension(url.orEmpty())
    }

    fun choose(
        primaryUrl: String?,
        primaryType: String?,
        fallbacks: List<AlternateSource>,
        primaryBitrate: Int = 0,
    ): Chosen? {
        val audio = fallbacks.filter { isAudioFallback(it.url, it.type) }.distinctBy { it.url }
        if (isPlayable(primaryUrl, primaryType)) {
            val primary = AlternateSource(primaryUrl!!, primaryType, primaryBitrate.coerceAtLeast(0))
            return Chosen(primary.url, primaryType, listOf(primary) + audio.filter { it.url != primary.url })
        }
        val best = audio.maxByOrNull { it.bitrate } ?: return null
        return Chosen(best.url, best.type, listOf(best) + audio.filter { it.url != best.url })
    }

    fun isPlayable(url: String?, type: String?): Boolean {
        if (url.isNullOrBlank()) return false
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        if (isWatchPage(url)) return false
        if (hasAudioExtension(url)) return true
        val mime = mime(type)
        if (mime.startsWith("video/")) return false
        if (mime.startsWith("text/") || mime.contains("html") || mime.contains("json") || mime.contains("xml")) return false
        if (mime.startsWith("audio/")) return true
        return AudioSource.mediaMime(mime, url) == AudioSource.HLS
    }

    fun isWatchPage(url: String): Boolean {
        val host = host(url) ?: return false
        if (host == "youtu.be" || host == "vimeo.com" || host == "www.vimeo.com" || host == "player.vimeo.com") return true
        return host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com")
    }

    private fun mime(type: String?): String = type.orEmpty().lowercase().substringBefore(';').trim()

    private fun hasAudioExtension(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#')
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in AUDIO_EXTENSIONS
    }

    private fun host(url: String): String? {
        val rest = when {
            url.startsWith("https://") -> url.removePrefix("https://")
            url.startsWith("http://") -> url.removePrefix("http://")
            else -> return null
        }
        val authority = rest.substringBefore('/').substringBefore('?')
        val named = if ('@' in authority) authority.substringAfterLast('@') else authority
        return named.substringBefore(':').lowercase().ifBlank { null }
    }

    private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "mp4a", "aac", "ogg", "oga", "opus", "flac", "wav", "m3u8", "m3u")
}
