package de.jenz.nahtlos.core.playback

import org.junit.Assert.assertTrue
import org.junit.Test

class CarControlsTest {
    @Test
    fun chapterButtonsAreInTheFirstFourSlotsWithoutAWallet() {
        val names = CarControls.names(payments = false)
        assertTrue(names.indexOf("Kapitel zurück") in 0..3)
        assertTrue(names.indexOf("Nächstes Kapitel") in 0..3)
        assertTrue("Boost" !in names)
        assertTrue(names.indexOf("Zurück") < names.indexOf("Kapitel zurück"))
    }
}
