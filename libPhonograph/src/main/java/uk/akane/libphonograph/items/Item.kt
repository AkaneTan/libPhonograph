package uk.akane.libphonograph.items

import androidx.media3.common.MediaItem

interface Item {
    val id: Long?
    val title: String?
    val songList: List<MediaItem>
}