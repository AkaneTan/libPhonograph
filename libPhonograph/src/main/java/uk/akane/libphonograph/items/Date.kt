package uk.akane.libphonograph.items

data class Date<T>(
    override val id: Long?,
    override val title: String?,
    override val songList: MutableList<T>
) : Item<T>