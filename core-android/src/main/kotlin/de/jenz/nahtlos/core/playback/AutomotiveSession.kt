package de.jenz.nahtlos.core.playback

import android.content.Intent
import android.os.Bundle

/**
 * AAOS koppelt die Systemkachel und die Lenkradtasten an die Session, deren Extras
 * den Klassennamen dieses MediaBrowserService tragen. Ohne den Eintrag bleibt die
 * vorherige Quelle aktiv, auch wenn hier schon Ton läuft.
 */
object AutomotiveSession {
    const val BROWSE_SERVICE_EXTRA = "android.media.session.BROWSE_SERVICE"
    const val PLAYER_CLASS = "de.jenz.nahtlos.automotive.NowPlayingActivity"

    fun extras(serviceClassName: String): Bundle =
        Bundle().apply { putString(BROWSE_SERVICE_EXTRA, serviceClassName) }

    /** Eigene Wiedergabe. Das System kann denselben Intent als Sitzungs-Activity senden. */
    fun playerIntent(packageName: String): Intent =
        Intent(Intent.ACTION_MAIN).apply {
            setClassName(packageName, PLAYER_CLASS)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
}
