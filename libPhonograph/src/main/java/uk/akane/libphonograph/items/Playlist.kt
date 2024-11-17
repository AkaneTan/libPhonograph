package uk.akane.libphonograph.items

import androidx.media3.common.MediaItem

open class Playlist protected constructor(
    override val id: Long?,
    override val title: String?
) : Item {
    private var _songList: List<MediaItem>? = null
    constructor(id: Long?, title: String?, songList: List<MediaItem>) : this(id, title) {
        _songList = songList
    }
    override val songList: List<MediaItem>
        get() = _songList ?: throw IllegalStateException("code bug: Playlist subclass used " +
                "protected constructor but did not override songList")

    override fun hashCode(): Int {
        var result = id?.hashCode() ?: 0
        result = 31 * result + (title?.hashCode() ?: 0)
        result = 31 * result + songList.hashCode()
        return result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Playlist

        if (id != other.id) return false
        if (title != other.title) return false
        if (songList != other.songList) return false

        return true
    }
}

data class RawPlaylist(
    val id: Long?,
    val title: String?,
    val songList: List<Long>
) {
    // idMap may be null if and only if all playlists are empty
    fun toPlaylist(idMap: Map<Long, MediaItem>?): Playlist {
        return Playlist(id, title, songList.mapNotNull { value ->
            idMap!![value]
            // if song is null it's 100% of time a library (or MediaStore?) bug
            // and because I found the MediaStore bug in the wild, don't be so stingy
        })
    }
}