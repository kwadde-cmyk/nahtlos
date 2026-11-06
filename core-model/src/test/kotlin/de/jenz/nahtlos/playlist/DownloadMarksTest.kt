package de.jenz.nahtlos.playlist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadMarksTest {
    @Test
    fun labelsStayReadable() {
        assertEquals("Auf dem Gerät", DownloadMarks.caption(onDevice = true, null, null))
        assertEquals("Nicht heruntergeladen", DownloadMarks.caption(onDevice = false, null, null))
        assertEquals("Lädt 42 %", DownloadMarks.caption(onDevice = false, 42, 100))
        assertEquals("Lädt…", DownloadMarks.caption(onDevice = false, 10, -1))
    }

    @Test
    fun fractionTracksTheFile() {
        val fraction = DownloadMarks.fraction(25, 100)
        assertEquals(0.25f, fraction)
        assertNull(DownloadMarks.fraction(10, -1))
        assertTrue(DownloadMarks.percent(99, 100) == 99)
    }
}
