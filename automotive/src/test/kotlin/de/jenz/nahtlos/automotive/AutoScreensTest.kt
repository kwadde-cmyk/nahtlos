package de.jenz.nahtlos.automotive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoScreensTest {
    @Test
    fun gearOpensSettingsAndTheErrorActionOpensPairing() {
        assertEquals(AutoScreen.Settings, AutoNav.initial(openPairing = false).visible)
        assertEquals(AutoScreen.Pairing, AutoNav.initial(openPairing = true).visible)
    }

    @Test
    fun backFromPairingReturnsToSettingsAndThenLeaves() {
        val pairing = AutoNav.initial(openPairing = true)
        val settings = pairing.back()
        assertEquals(AutoScreen.Settings, settings?.visible)
        assertNull(settings?.back())
    }

    @Test
    fun walletScreenReturnsToSettingsAndStaysClosedWhileDriving() {
        val wallet = AutoNav.initial(openPairing = false).openWallet()
        assertEquals(AutoScreen.Wallet, wallet.visible)
        assertEquals(AutoScreen.Settings, wallet.back()?.visible)
        val locked = wallet.withDriving(true)
        assertEquals(AutoScreen.Locked, locked.visible)
        assertEquals(AutoScreen.Locked, locked.openWallet().visible)
        assertNull(locked.back())
    }

    @Test
    fun drivingHidesPairingAndBackLeavesWithoutOpeningIt() {
        val locked = AutoNav.initial(openPairing = true).withDriving(true)
        assertEquals(AutoScreen.Locked, locked.visible)
        assertEquals(AutoScreen.Locked, locked.openPairing().visible)
        assertNull(locked.back())
        assertEquals(AutoScreen.Pairing, locked.withDriving(false).visible)
    }

    @Test
    fun mediaTemplatePointsAtThisAppsBrowser() {
        val component = MediaCenterExit.component("de.jenz.nahtlos.automotive")
        assertEquals(
            "de.jenz.nahtlos.automotive/de.jenz.nahtlos.core.playback.PodcastMediaLibraryService",
            component,
        )
        assertTrue(component.endsWith(MediaCenterExit.SERVICE_CLASS))
    }
}
