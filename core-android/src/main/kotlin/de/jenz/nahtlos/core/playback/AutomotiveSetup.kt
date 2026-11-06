package de.jenz.nahtlos.core.playback

import android.content.Intent

/** Einstieg aus dem Mediencenter in die vorhandene Handy-Kopplung. */
object AutomotiveSetup {
    const val EXTRA_OPEN_PAIRING = "de.jenz.nahtlos.OPEN_PAIRING"
    const val RESOLUTION_LABEL = "Handy koppeln"

    const val SETTINGS_CLASS = "de.jenz.nahtlos.automotive.SettingsActivity"

    fun openPairing(launch: Intent): Intent = launch.putExtra(EXTRA_OPEN_PAIRING, true)

    /** Einstellungen, nicht die Startseite. Die Startseite ist die Wiedergabe. */
    fun preferencesIntent(packageName: String): Intent =
        Intent(Intent.ACTION_APPLICATION_PREFERENCES).apply {
            setClassName(packageName, SETTINGS_CLASS)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
