package de.jenz.nahtlos.core.playback

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutomotiveSetupTest {
    @Test
    fun browseErrorOpensThePairingScreenByName() {
        val launch = AutomotiveSetup.openPairing(Intent("android.intent.action.MAIN"))
        assertEquals(AutomotiveSetup.RESOLUTION_LABEL, "Handy koppeln")
        assertTrue(launch.getBooleanExtra(AutomotiveSetup.EXTRA_OPEN_PAIRING, false))
    }
}
