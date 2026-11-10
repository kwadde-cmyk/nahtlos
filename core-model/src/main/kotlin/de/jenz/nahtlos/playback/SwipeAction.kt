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

        /**
         * Actions that fit the selection. A pair shows one side when every episode agrees,
         * and both sides when the selection is mixed.
         */
        fun matching(
            keys: Collection<String>,
            played: Set<String>,
            inPlaylist: Set<String>,
            downloaded: Set<String>,
        ): List<SwipeAction> {
            if (keys.isEmpty()) return emptyList()
            return buildList {
                if (keys.any { it !in inPlaylist }) add(ADD_PLAYLIST)
                if (keys.any { it in inPlaylist }) add(REMOVE_PLAYLIST)
                if (keys.any { it !in downloaded }) add(DOWNLOAD)
                if (keys.any { it in downloaded }) add(DELETE_DOWNLOAD)
                if (keys.any { it !in played }) add(MARK_PLAYED)
                if (keys.any { it in played }) add(MARK_UNPLAYED)
            }
        }

        /** Episodes the action would actually change, so a mixed selection does not reset the rest. */
        fun targets(
            action: SwipeAction,
            keys: Collection<String>,
            played: Set<String>,
            inPlaylist: Set<String>,
            downloaded: Set<String>,
        ): List<String> = keys.distinct().filter { key ->
            when (action) {
                ADD_PLAYLIST -> key !in inPlaylist
                REMOVE_PLAYLIST -> key in inPlaylist
                DOWNLOAD -> key !in downloaded
                DELETE_DOWNLOAD -> key in downloaded
                MARK_PLAYED -> key !in played
                MARK_UNPLAYED -> key in played
            }
        }
    }
}
