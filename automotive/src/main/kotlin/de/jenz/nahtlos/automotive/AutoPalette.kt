package de.jenz.nahtlos.automotive

import kotlin.math.pow

/**
 * Feste Farben für Seite und Eingabefelder. Das Fenster der Auto-App ist dunkel,
 * ein unvollständiges helles Theme schreibt sonst dunkle Schrift auf dunklen Grund.
 */
object AutoPalette {
    data class Tone(
        val page: Long,
        val onPage: Long,
        val field: Long,
        val text: Long,
        val placeholder: Long,
        val label: Long,
        val cursor: Long,
        val outline: Long,
    )

    val dark = Tone(
        page = 0xFF0E0E10,
        onPage = 0xFFFFF8E7,
        field = 0xFF2A2A30,
        text = 0xFFFFF8E7,
        placeholder = 0xFFE4DCC4,
        label = 0xFFFFD56A,
        cursor = 0xFFFFD56A,
        outline = 0xFFFF7A1A,
    )

    val light = Tone(
        page = 0xFFF4F1EB,
        onPage = 0xFF1A1408,
        field = 0xFFFFFFFF,
        text = 0xFF1A1408,
        placeholder = 0xFF3E382E,
        label = 0xFF6B4300,
        cursor = 0xFF6B4300,
        outline = 0xFF8A5A00,
    )

    fun contrast(foreground: Long, background: Long): Double {
        val lighter = maxOf(luminance(foreground), luminance(background))
        val darker = minOf(luminance(foreground), luminance(background))
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun luminance(argb: Long): Double {
        fun channel(value: Double) = if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        val red = channel(((argb shr 16) and 0xFF) / 255.0)
        val green = channel(((argb shr 8) and 0xFF) / 255.0)
        val blue = channel((argb and 0xFF) / 255.0)
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue
    }
}
