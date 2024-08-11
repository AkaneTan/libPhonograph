package uk.akane.libphonograph.items

import android.net.Uri

data class AlbumImpl<T>(
    override val id: Long?,
    override val title: String?,
    override val albumArtist: String?,
    override var albumArtistId: Long?,
    override val albumYear: Int?,
    override var cover: Uri?,
    override val songList: MutableList<T>
) : Album<T>