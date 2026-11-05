package de.jenz.nahtlos.core.playback

/** Reihenfolge der Tasten im Mediencenter. Die ersten vier bleiben sichtbar, auch wenn die Leiste kurz ist. */
object CarControls {
    fun names(payments: Boolean): List<String> = buildList {
        add("Zurück")
        add("Vor")
        add("Kapitel zurück")
        add("Nächstes Kapitel")
        if (payments) add("Boost")
        add("Langsamer")
        add("Schneller")
    }
}
