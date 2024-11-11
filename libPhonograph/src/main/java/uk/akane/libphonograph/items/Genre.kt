package uk.akane.libphonograph.items

data class Genre<T>(
    override val id: Long?,
    override val title: String?,
    override val songList: List<T>
) : Item<T>