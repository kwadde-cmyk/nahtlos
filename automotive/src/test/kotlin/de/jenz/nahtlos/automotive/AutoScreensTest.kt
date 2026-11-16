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
    fun nostrScreenReturnsToSettingsAndStaysClosedWhileDriving() {
        val nostr = AutoNav.initial(openPairing = false).openNostr()
        assertEquals(AutoScreen.Nostr, nostr.visible)
        assertEquals(AutoScreen.Settings, nostr.back()?.visible)
        val locked = nostr.withDriving(true)
        assertEquals(AutoScreen.Locked, locked.visible)
        assertEquals(AutoScreen.Locked, locked.openNostr().visible)
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
    fun actionRowFitsAPortraitScreenWithoutScrolling() {
        val volvo = AutoDrive.actionButtonWidthDp(rowDp = 480, count = 5)
        val wide = AutoDrive.actionButtonWidthDp(rowDp = 900, count = 5)
        assertTrue(volvo < AutoDrive.BUTTON_HEIGHT_DP)
        assertTrue(wide == AutoDrive.BUTTON_WIDTH_DP)
        val used = volvo * 5 + AutoDrive.ACTION_GAP_DP * 4
        assertTrue(used <= 480)
        val portrait = 682
        val cover = AutoDrive.coverEdgeDp(portrait, driving = false)
        assertTrue(AutoDrive.CHROME_WITHOUT_COVER_DP + cover <= portrait)
        assertEquals(AutoDrive.COVER_PARKED_DP, AutoDrive.coverEdgeDp(1100, driving = false))
    }

    @Test
    fun driveTargetsStayLargeEnoughForTheCar() {
        assertTrue(AutoDrive.MIN_TOUCH_DP >= 76)
        assertTrue(AutoDrive.LABEL_SP >= 24)
        assertTrue(AutoDrive.TITLE_SP >= 32)
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
