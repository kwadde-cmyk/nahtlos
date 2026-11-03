package de.jenz.nahtlos.feed

import de.jenz.nahtlos.model.Chapter
import java.io.File

/**
 * Reads ID3v2 CHAP frames from the start of an audio file. Used only when the feed
 * has neither inline chapters nor a podcast:chapters URL. Any broken tag is ignored.
 */
object Id3Chapters {
    fun read(file: File, maxBytes: Int = 512 * 1024): List<Chapter> = try {
        val size = minOf(file.length(), maxBytes.toLong()).toInt()
        if (size < 10) emptyList()
        else file.inputStream().use { input ->
            val bytes = ByteArray(size)
            val read = input.read(bytes)
            if (read < 10) emptyList() else parse(bytes.copyOf(read))
        }
    } catch (_: Throwable) {
        emptyList()
    }

    fun parse(bytes: ByteArray): List<Chapter> {
        if (bytes.size < 10 || bytes[0] != 'I'.code.toByte() || bytes[1] != 'D'.code.toByte() || bytes[2] != '3'.code.toByte()) {
            return emptyList()
        }
        val version = bytes[3].toInt() and 0xFF
        val tagSize = synchsafe(bytes, 6)
        val limit = minOf(bytes.size, 10 + tagSize)
        var offset = 10
        val chapters = mutableListOf<Chapter>()
        while (offset + 10 <= limit) {
            val id = String(bytes, offset, 4, Charsets.ISO_8859_1)
            if (id[0].code == 0) break
            val frameSize = if (version >= 4) synchsafe(bytes, offset + 4) else be32(bytes, offset + 4)
            val dataStart = offset + 10
            val dataEnd = dataStart + frameSize
            if (frameSize <= 0 || dataEnd > limit) break
            if (id == "CHAP") chapter(bytes, dataStart, dataEnd)?.let { chapters += it }
            offset = dataEnd
        }
        return chapters.sortedBy { it.startMs }
    }

    private fun chapter(bytes: ByteArray, start: Int, end: Int): Chapter? {
        var index = start
        while (index < end && bytes[index] != 0.toByte()) index++
        if (index >= end) return null
        val afterId = index + 1
        if (afterId + 16 > end) return null
        val startMs = be32(bytes, afterId).toLong().coerceAtLeast(0)
        var cursor = afterId + 16
        var title = "Kapitel"
        while (cursor + 10 <= end) {
            val id = String(bytes, cursor, 4, Charsets.ISO_8859_1)
            val size = be32(bytes, cursor + 4)
            val body = cursor + 10
            if (size < 0 || body + size > end) break
            if (id == "TIT2" && size > 1) {
                val encoding = bytes[body].toInt() and 0xFF
                val raw = bytes.copyOfRange(body + 1, body + size)
                title = decodeText(encoding, raw).ifBlank { title }
            }
            cursor = body + size
        }
        return Chapter(startMs = startMs, title = title)
    }

    private fun decodeText(encoding: Int, raw: ByteArray): String = when (encoding) {
        1, 2 -> String(raw, Charsets.UTF_16)
        3 -> String(raw, Charsets.UTF_8)
        else -> String(raw, Charsets.ISO_8859_1)
    }.trim { it <= ' ' || it == '\u0000' }

    private fun synchsafe(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)
    }

    private fun be32(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }
}
