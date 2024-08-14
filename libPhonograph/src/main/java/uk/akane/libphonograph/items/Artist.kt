package uk.akane.libphonograph.items

data class Artist<T>(
    override val id: Long?,
    override val title: String?,
    override val songList: MutableList<T>,
    val albumList: MutableList<Album<T>>
) : Item<T>