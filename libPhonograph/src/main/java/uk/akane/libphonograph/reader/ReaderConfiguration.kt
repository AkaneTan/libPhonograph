package uk.akane.libphonograph.reader

import uk.akane.libphonograph.constructor.ItemConstructor

data class ReaderConfiguration<T>(
    val itemConstructor: ItemConstructor<T>,

    val itemLengthLimiter: Long = 0,
    val blackListSet: Set<String> = setOf(),

    val shouldUseEnhancedCoverReading: Boolean = false,
    val shouldIncludeExtraFormat: Boolean = false,
    val shouldFetchPlaylist: Boolean = false,

    val shouldGenerateRecentlyAdded: Boolean = false,
    val recentlyAddedFilterSecond: Long = 1_209_600,
)
