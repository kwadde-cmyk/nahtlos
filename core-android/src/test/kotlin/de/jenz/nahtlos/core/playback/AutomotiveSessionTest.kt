package de.jenz.nahtlos.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutomotiveSessionTest {
    @Test
    fun extrasNameTheBrowseServiceTheCarUsesToClaimTheSource() {
        val extras = AutomotiveSession.extras("de.jenz.nahtlos.core.playback.PodcastMediaLibraryService")
        assertEquals(
            "de.jenz.nahtlos.core.playback.PodcastMediaLibraryService",
            extras.getString(AutomotiveSession.BROWSE_SERVICE_EXTRA),
        )
        assertEquals("android.media.session.BROWSE_SERVICE", AutomotiveSession.BROWSE_SERVICE_EXTRA)
    }

    @Test
    fun playerIntentOpensTheDrivingActivity() {
        val intent = AutomotiveSession.playerIntent("de.jenz.nahtlos.automotive")
        assertEquals("de.jenz.nahtlos.automotive", intent.component?.packageName)
        assertEquals(AutomotiveSession.PLAYER_CLASS, intent.component?.className)
        assertTrue(intent.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }
}
