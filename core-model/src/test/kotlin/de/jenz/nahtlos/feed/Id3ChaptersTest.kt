package de.jenz.nahtlos.feed

import kotlin.test.Test
import kotlin.test.assertEquals

class Id3ChaptersTest {
    @Test
    fun readsAChapterTitleAndStart() {
        val chapters = Id3Chapters.parse(sample())
        assertEquals(1, chapters.size)
        assertEquals(0L, chapters.single().startMs)
        assertEquals("Intro", chapters.single().title)
    }

    private fun sample(): ByteArray {
        val title = "Intro".toByteArray(Charsets.ISO_8859_1)
        val tit2 = ByteArray(10 + 1 + title.size)
        "TIT2".toByteArray(Charsets.ISO_8859_1).copyInto(tit2, 0)
        val textSize = 1 + title.size
        tit2[4] = 0
        tit2[5] = 0
        tit2[6] = 0
        tit2[7] = textSize.toByte()
        tit2[10] = 0
        title.copyInto(tit2, 11)
        val element = "ch1".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0)
        val times = ByteArray(16)
        times[8] = 0xFF.toByte()
        times[9] = 0xFF.toByte()
        times[10] = 0xFF.toByte()
        times[11] = 0xFF.toByte()
        times[12] = 0xFF.toByte()
        times[13] = 0xFF.toByte()
        times[14] = 0xFF.toByte()
        times[15] = 0xFF.toByte()
        val payload = element + times + tit2
        val frame = ByteArray(10 + payload.size)
        "CHAP".toByteArray(Charsets.ISO_8859_1).copyInto(frame, 0)
        val size = payload.size
        frame[4] = ((size shr 24) and 0xFF).toByte()
        frame[5] = ((size shr 16) and 0xFF).toByte()
        frame[6] = ((size shr 8) and 0xFF).toByte()
        frame[7] = (size and 0xFF).toByte()
        payload.copyInto(frame, 10)
        val header = ByteArray(10 + frame.size)
        header[0] = 'I'.code.toByte()
        header[1] = 'D'.code.toByte()
        header[2] = '3'.code.toByte()
        header[3] = 3
        val tagSize = frame.size
        header[6] = ((tagSize shr 21) and 0x7F).toByte()
        header[7] = ((tagSize shr 14) and 0x7F).toByte()
        header[8] = ((tagSize shr 7) and 0x7F).toByte()
        header[9] = (tagSize and 0x7F).toByte()
        frame.copyInto(header, 10)
        return header
    }
}
