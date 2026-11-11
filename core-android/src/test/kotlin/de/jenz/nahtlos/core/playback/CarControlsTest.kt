package de.jenz.nahtlos.core.playback

import androidx.media3.session.CommandButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarControlsTest {
    @Test
    fun boostSitsBesidePlayAndAheadOfChapterAndSpeed() {
        val buttons = CarControls.buttons(payments = true, automotive = true)
        val boost = buttons.first()
        assertEquals("Boost", boost.name)
        assertEquals(SessionActions.QUICK_BOOST, boost.action)
        assertEquals(CommandButton.SLOT_FORWARD_SECONDARY, boost.slots.first())
        assertTrue(CommandButton.SLOT_OVERFLOW in boost.slots)
        assertTrue(boost.iconRes != 0)
        assertTrue(buttons.indexOfFirst { it.name == "Boost" } < buttons.indexOfFirst { it.name == "Kapitel zurück" })
        assertTrue(buttons.indexOfFirst { it.name == "Boost" } < buttons.indexOfFirst { it.name == "Langsamer" })
        buttons.drop(1).forEach { button ->
            assertEquals(listOf(CommandButton.SLOT_OVERFLOW), button.slots.toList())
            assertFalse(CommandButton.SLOT_BACK in button.slots)
            assertFalse(CommandButton.SLOT_FORWARD in button.slots)
        }
    }

    @Test
    fun nahtlosOpensFromTheSystemPlayerOverflow() {
        val button = CarControls.buttons(payments = true, automotive = true).first { it.action == SessionActions.OPEN_PLAYER }
        assertEquals("Nahtlos", button.name)
        assertEquals(listOf(CommandButton.SLOT_OVERFLOW), button.slots.toList())
        assertFalse(CommandButton.SLOT_FORWARD_SECONDARY in button.slots)
        assertTrue(button.iconRes != 0)
        assertFalse(CarControls.buttons(payments = true, automotive = false).any { it.action == SessionActions.OPEN_PLAYER })
    }

    @Test
    fun withoutPaymentsTheCarStillShowsChaptersButNoBoost() {
        val names = CarControls.buttons(payments = false, automotive = true).map { it.name }
        assertFalse(names.contains("Boost"))
        assertEquals("Kapitel zurück", names.first())
    }

    @Test
    fun phoneLayoutStaysIndependentOfTheCarSlot() {
        val names = CarControls.buttons(payments = true, automotive = false).map { it.name }
        assertEquals("Zurück", names.first())
        assertTrue(names.indexOf("Boost") > names.indexOf("Vor"))
    }

    @Test
    fun missingWalletOrValueGetsAVisibleAnswer() {
        assertEquals(
            "Boost nicht gesendet. Kein Wallet auf diesem Auto. Im Stand unter Einstellungen holen.",
            CarControls.boostNotice("Kein Wallet verbunden."),
        )
        assertEquals(
            "Boost nicht gesendet. Dieser Feed hat keine Empfänger. Nichts gesendet.",
            CarControls.boostNotice("Keine zahlbaren Empfänger."),
        )
    }
}
