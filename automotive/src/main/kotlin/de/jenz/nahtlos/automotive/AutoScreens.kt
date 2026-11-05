package de.jenz.nahtlos.automotive

/**
 * Einstellungen, Koppel-Ansicht und Fahrsperre.
 * [back] liefert null, wenn die Activity zum Mediencenter verlassen werden soll.
 * Während der Fahrt gibt es keine andere Ansicht und keinen Sprung in die Kopplung.
 */
data class AutoNav(
    val parked: AutoScreen = AutoScreen.Settings,
    val driving: Boolean = false,
) {
    val visible: AutoScreen
        get() = if (driving) AutoScreen.Locked else parked

    fun openPairing(): AutoNav = if (driving) this else copy(parked = AutoScreen.Pairing)

    fun withDriving(nowDriving: Boolean): AutoNav = copy(driving = nowDriving)

    fun back(): AutoNav? = when {
        driving -> null
        parked == AutoScreen.Pairing -> copy(parked = AutoScreen.Settings)
        else -> null
    }

    companion object {
        fun initial(openPairing: Boolean): AutoNav =
            AutoNav(parked = if (openPairing) AutoScreen.Pairing else AutoScreen.Settings)
    }
}

enum class AutoScreen { Settings, Pairing, Locked }
