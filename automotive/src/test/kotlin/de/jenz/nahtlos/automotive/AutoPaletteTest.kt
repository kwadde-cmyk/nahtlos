package de.jenz.nahtlos.automotive

import kotlin.test.Test
import kotlin.test.assertTrue

class AutoPaletteTest {
    @Test
    fun fieldTextCursorAndPlaceholderStayReadable() {
        listOf(AutoPalette.dark, AutoPalette.light).forEach { tone ->
            assertTrue(AutoPalette.contrast(tone.text, tone.field) >= 7.0, "Text ${tone.text}")
            assertTrue(AutoPalette.contrast(tone.placeholder, tone.field) >= 4.5, "Platzhalter")
            assertTrue(AutoPalette.contrast(tone.label, tone.field) >= 4.5, "Label")
            assertTrue(AutoPalette.contrast(tone.cursor, tone.field) >= 4.5, "Cursor")
            assertTrue(AutoPalette.contrast(tone.onPage, tone.page) >= 7.0, "Seitentext")
        }
    }
}
