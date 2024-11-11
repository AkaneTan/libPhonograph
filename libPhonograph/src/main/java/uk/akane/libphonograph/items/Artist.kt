package uk.akane.libphonograph.items

data class Artist<T>(
    override val id: Long?,
    override val title: String?,
    override val songList: List<T>,
    val albumList: List<Album<T>>
) : Item<T>