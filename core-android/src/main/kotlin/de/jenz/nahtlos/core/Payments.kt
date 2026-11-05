package de.jenz.nahtlos.core

/**
 * Ob Boosts und NWC in dieser Fassung an sind.
 * Die Application setzt den Wert aus BuildConfig, bevor Hilt startet.
 * Standard ist an. Eine Play-Variante setzt NAHTLOS_PAYMENTS=false.
 */
object Payments {
    @Volatile
    var enabled: Boolean = true
}
