package uk.akane.libphonograph.items

open class Playlist<T>(
    override val id: Long?,
    override val title: String?,
    override val songList: MutableList<T>
) : Item<T>