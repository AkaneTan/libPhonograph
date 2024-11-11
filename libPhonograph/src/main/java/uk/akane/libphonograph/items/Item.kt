package uk.akane.libphonograph.items

interface Item<T> {
    val id: Long?
    val title: String?
    val songList: List<T>
}