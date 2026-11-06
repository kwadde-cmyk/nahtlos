package de.jenz.nahtlos.playback

import java.io.File

/**
 * Was eine Enclosure wirklich ist. Ein erzwungenes audio/mpeg lässt Media3 die Adresse
 * ignorieren und nur Progressive-Extractor versuchen. HLS und HTML scheitern dann beide
 * mit UnrecognizedInputFormatException, contentIsMalformed=false.
 */
object AudioSource {
    const val HLS = "application/x-mpegURL"

    fun mediaMime(declared: String?, url: String): String? {
        val type = declared.orEmpty().lowercase().substringBefore(';').trim()
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        if (isHls(type, path)) return HLS
        if (type.startsWith("audio/") || type.startsWith("video/")) return type
        return null
    }

    fun shouldStore(contentType: String?, header: ByteArray): Boolean {
        val kind = headerKind(header)
        if (kind == HeaderKind.PLAYLIST || kind == HeaderKind.MARKUP) return false
        val type = contentType.orEmpty().lowercase()
        if (type.contains("html") || type.contains("json") || type.contains("xml") || type.contains("mpegurl") || type.contains("mpeg-url")) {
            return false
        }
        if (kind == HeaderKind.AUDIO) return true
        return type.startsWith("audio/") || type.startsWith("video/")
    }

    fun isProgressiveAudio(file: File): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        val header = ByteArray(64)
        val read = file.inputStream().use { it.read(header) }
        if (read <= 0) return false
        return headerKind(header.copyOf(read)) == HeaderKind.AUDIO
    }

    fun headerKind(bytes: ByteArray): HeaderKind {
        if (bytes.isEmpty()) return HeaderKind.UNKNOWN
        val text = bytes.decodeToString().trimStart()
        if (text.startsWith("#EXTM3U", ignoreCase = true) || text.startsWith("#EXTINF", ignoreCase = true)) {
            return HeaderKind.PLAYLIST
        }
        if (text.startsWith("<") || text.startsWith("{") || text.startsWith("<!") || text.startsWith("<?xml", ignoreCase = true)) {
            return HeaderKind.MARKUP
        }
        if (bytes.size >= 3 && bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()) {
            return HeaderKind.AUDIO
        }
        if (bytes.size >= 4 && bytes[0] == 'f'.code.toByte() && bytes[1] == 'L'.code.toByte() && bytes[2] == 'a'.code.toByte() && bytes[3] == 'C'.code.toByte()) {
            return HeaderKind.AUDIO
        }
        if (bytes.size >= 4 && bytes[0] == 'O'.code.toByte() && bytes[1] == 'g'.code.toByte() && bytes[2] == 'g'.code.toByte() && bytes[3] == 'S'.code.toByte()) {
            return HeaderKind.AUDIO
        }
        if (bytes.size >= 4 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()) {
            return HeaderKind.AUDIO
        }
        if (bytes.size >= 4 && bytes[0] == 'F'.code.toByte() && bytes[1] == 'O'.code.toByte() && bytes[2] == 'R'.code.toByte() && bytes[3] == 'M'.code.toByte()) {
            return HeaderKind.AUDIO
        }
        if (bytes.size >= 8 && bytes[4] == 'f'.code.toByte() && bytes[5] == 't'.code.toByte() && bytes[6] == 'y'.code.toByte() && bytes[7] == 'p'.code.toByte()) {
            return HeaderKind.AUDIO
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xE0) == 0xE0) {
            return HeaderKind.AUDIO
        }
        return HeaderKind.UNKNOWN
    }

    private fun isHls(type: String, path: String): Boolean {
        if (path.endsWith(".m3u8") || path.endsWith(".m3u")) return true
        return type.contains("mpegurl") || type.contains("mpeg-url") || type == "application/vnd.apple.mpegurl"
    }
}

enum class HeaderKind { AUDIO, PLAYLIST, MARKUP, UNKNOWN }
