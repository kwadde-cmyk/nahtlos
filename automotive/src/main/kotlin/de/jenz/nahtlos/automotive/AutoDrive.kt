package de.jenz.nahtlos.automotive

/** Touch-Ziele und Schrift der Wiedergabe. AAOS verlangt mindestens 76 dp. */
object AutoDrive {
    const val MIN_TOUCH_DP = 84
    const val BUTTON_WIDTH_DP = 120
    const val BUTTON_HEIGHT_DP = 152
    const val LABEL_SP = 26
    const val TITLE_SP = 32
    const val BODY_SP = 24
    const val ACTION_GAP_DP = 10
    const val COVER_PARKED_DP = 200
    const val COVER_DRIVING_DP = 180
    const val COVER_MIN_DP = 72

    /**
     * Platz für Titel, Zeiten, Transport und die Aktionsreihe, ohne Cover.
     * Bewusst großzügig, damit die Reihe auch mit Schriftmetrik auf dem Bildschirm bleibt.
     */
    const val CHROME_WITHOUT_COVER_DP = 600

    /** Breite einer Taste in der Aktionsreihe. Oben gedeckelt, damit sie schmal bleibt, und so weit verkleinert, dass die Reihe ohne Scrollen passt. */
    fun actionButtonWidthDp(rowDp: Int, count: Int, gapDp: Int = ACTION_GAP_DP, maxDp: Int = BUTTON_WIDTH_DP): Int {
        if (count <= 0 || rowDp <= 0) return maxDp
        val gaps = gapDp * (count - 1).coerceAtLeast(0)
        val share = (rowDp - gaps) / count
        return share.coerceAtMost(maxDp).coerceAtLeast(1)
    }

    /** Cover nur so hoch, dass Transport und Aktionsreihe im Hochformat ohne Scrollen bleiben. */
    fun coverEdgeDp(viewportHeightDp: Int, driving: Boolean): Int {
        val cap = if (driving) COVER_DRIVING_DP else COVER_PARKED_DP
        if (viewportHeightDp <= 0) return cap
        return (viewportHeightDp - CHROME_WITHOUT_COVER_DP).coerceIn(COVER_MIN_DP, cap)
    }
}
