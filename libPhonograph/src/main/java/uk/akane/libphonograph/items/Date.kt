package uk.akane.libphonograph.items

import androidx.media3.common.MediaItem

data class Date(
    override val id: Long?,
    override val title: String?,
    override val songList: List<MediaItem>
) : Item