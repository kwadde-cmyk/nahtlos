package de.jenz.nahtlos.core.playback

import android.content.Intent

/** Einstieg aus dem Mediencenter in die vorhandene Handy-Kopplung. */
object AutomotiveSetup {
    const val EXTRA_OPEN_PAIRING = "de.jenz.nahtlos.OPEN_PAIRING"
    const val RESOLUTION_LABEL = "Handy koppeln"

    fun openPairing(launch: Intent): Intent = launch.putExtra(EXTRA_OPEN_PAIRING, true)
}
