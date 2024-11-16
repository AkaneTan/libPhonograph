package uk.akane.libphonograph.dynamicitem

import androidx.media3.common.MediaItem
import uk.akane.libphonograph.items.Playlist
import uk.akane.libphonograph.items.addDate

class RecentlyAdded(minAddDate: Long, songList: List<MediaItem>) : Playlist(-1, null) {
    private val rawList: List<MediaItem> = songList.sortedByDescending {
        it.mediaMetadata.addDate ?: -1 }
    private var filteredList = filterList(minAddDate)
    var minAddDate: Long = minAddDate
        set(value) {
            if (field != value) {
                field = value
                filteredList = filterList(value)
            }
        }
    override val songList: List<MediaItem>
        get() = filteredList
    private fun filterList(minAddDate: Long): List<MediaItem> {
        return if (rawList.isEmpty()) rawList else rawList.let { l ->
            l.binarySearch {
                it.mediaMetadata.addDate?.let { minAddDate.compareTo(it) } ?: -1
            }.let {
                if (it >= 0) it else (-it - 1) // insertion point ~= first index
            }
        }.let {
            if (it > 0)
                rawList.subList(0, it)
            else
                listOf()
        }
    }
}