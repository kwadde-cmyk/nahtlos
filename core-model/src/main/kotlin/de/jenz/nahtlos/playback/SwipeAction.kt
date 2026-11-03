package de.jenz.nahtlos.playback

enum class SwipeAction(val id: String, val label: String, val removesRow: Boolean) {
    REMOVE_PLAYLIST("remove_playlist", "Aus Playlist nehmen", true),
    ADD_PLAYLIST("add_playlist", "Zur Playlist", false),
    MARK_PLAYED("mark_played", "Als gehört", false),
    MARK_UNPLAYED("mark_unplayed", "Als ungehört", false),
    DELETE_DOWNLOAD("delete_download", "Download löschen", true),
    ;

    companion object {
        val START_DEFAULT = MARK_PLAYED
        val END_DEFAULT = REMOVE_PLAYLIST

        fun fromId(id: String?): SwipeAction = entries.firstOrNull { it.id == id } ?: MARK_PLAYED
    }
}
