package de.jenz.nahtlos.core.playback

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionConnectionTest {
    @Test
    fun ownPackageIsAcceptedEvenWhenTheDefaultCallbackRejectsIt() {
        assertTrue(
            SessionConnection.accepts(
                controllerPackage = "de.jenz.nahtlos",
                ownPackage = "de.jenz.nahtlos",
                trusted = false,
                notificationController = false,
                fallbackAccepted = false,
            ),
        )
    }

    @Test
    fun automotivePackageIsAcceptedTheSameWayOnBothApps() {
        assertTrue(
            SessionConnection.accepts(
                controllerPackage = "de.jenz.nahtlos.automotive",
                ownPackage = "de.jenz.nahtlos.automotive",
                trusted = false,
                notificationController = false,
                fallbackAccepted = false,
            ),
        )
    }

    @Test
    fun trustedSystemControllersAreAccepted() {
        assertTrue(
            SessionConnection.shouldAccept(
                controllerPackage = "com.android.systemui",
                ownPackage = "de.jenz.nahtlos",
                trusted = false,
                notificationController = false,
            ),
        )
        assertTrue(
            SessionConnection.shouldAccept(
                controllerPackage = "com.android.car.media",
                ownPackage = "de.jenz.nahtlos",
                trusted = false,
                notificationController = false,
            ),
        )
        assertTrue(
            SessionConnection.shouldAccept(
                controllerPackage = "com.example.browser",
                ownPackage = "de.jenz.nahtlos",
                trusted = true,
                notificationController = false,
            ),
        )
        assertTrue(
            SessionConnection.shouldAccept(
                controllerPackage = "de.jenz.nahtlos",
                ownPackage = "de.jenz.nahtlos",
                trusted = false,
                notificationController = true,
            ),
        )
    }

    @Test
    fun unknownUntrustedControllerIsNotOnTheAllowList() {
        assertFalse(
            SessionConnection.shouldAccept(
                controllerPackage = "com.example.browser",
                ownPackage = "de.jenz.nahtlos",
                trusted = false,
                notificationController = false,
            ),
        )
    }
}
