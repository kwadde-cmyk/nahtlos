package de.jenz.nahtlos.playback

enum class SwipeAction(val id: String, val label: String, val removesRow: Boolean) {
    REMOVE_PLAYLIST("remove_playlist", "Aus Playlist nehmen", true),
    ADD_PLAYLIST("add_playlist", "Zur Playlist", false),
    MARK_PLAYED("mark_played", "Als gehört", false),
    MARK_UNPLAYED("mark_unplayed", "Als ungehört", false),
    DELETE_DOWNLOAD("delete_download", "Download löschen", true),
    DOWNLOAD("download", "Herunterladen", false),
    ;

    /** The action this swipe performs given what is already true for the episode. */
    fun resolved(played: Boolean, inPlaylist: Boolean, downloaded: Boolean): SwipeAction = when (this) {
        MARK_PLAYED, MARK_UNPLAYED -> if (played) MARK_UNPLAYED else MARK_PLAYED
        ADD_PLAYLIST, REMOVE_PLAYLIST -> if (inPlaylist) REMOVE_PLAYLIST else ADD_PLAYLIST
        DELETE_DOWNLOAD, DOWNLOAD -> if (downloaded) DELETE_DOWNLOAD else DOWNLOAD
    }

    companion object {
        val START_DEFAULT = MARK_PLAYED
        val END_DEFAULT = REMOVE_PLAYLIST

        fun fromId(id: String?): SwipeAction = entries.firstOrNull { it.id == id } ?: MARK_PLAYED
    }
}
