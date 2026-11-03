package de.jenz.nahtlos.core.playback

/**
 * Who may connect to the single [PodcastMediaLibraryService] session.
 * The phone's own [androidx.media3.session.MediaController] is often not marked trusted,
 * and a rejected connection surfaces as "Session rejected the connection request."
 */
object SessionConnection {
    val systemPackages: Set<String> = setOf(
        "com.android.systemui",
        "com.android.car.media",
        "com.android.car.carlauncher",
        "com.google.android.projection.gearhead",
    )

    fun shouldAccept(
        controllerPackage: String,
        ownPackage: String,
        trusted: Boolean,
        notificationController: Boolean,
    ): Boolean {
        if (ownPackage.isNotBlank() && controllerPackage == ownPackage) return true
        if (trusted || notificationController) return true
        return controllerPackage in systemPackages
    }

    /** Own package and trusted system controllers stay accepted even when the default callback rejects them. */
    fun accepts(
        controllerPackage: String,
        ownPackage: String,
        trusted: Boolean,
        notificationController: Boolean,
        fallbackAccepted: Boolean,
    ): Boolean = shouldAccept(controllerPackage, ownPackage, trusted, notificationController) || fallbackAccepted
}
