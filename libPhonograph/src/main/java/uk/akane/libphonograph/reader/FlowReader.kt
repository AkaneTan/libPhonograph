package uk.akane.libphonograph.reader

import android.content.Context
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import kotlin.collections.plus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted.Companion.Eagerly
import kotlinx.coroutines.flow.SharingStarted.Companion.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import uk.akane.libphonograph.collectOtherFlowWhenBeingCollected
import uk.akane.libphonograph.contentObserverVersioningFlow
import uk.akane.libphonograph.dynamicitem.RecentlyAdded
import uk.akane.libphonograph.flowWhileShared
import uk.akane.libphonograph.items.Album
import uk.akane.libphonograph.items.Artist
import uk.akane.libphonograph.items.Date
import uk.akane.libphonograph.items.FileNode
import uk.akane.libphonograph.items.Genre
import uk.akane.libphonograph.sharedFlow

/**
 * SimpleReader reimplementation using flows with focus on efficiency.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FlowReader(
    context: Context,
    minSongLengthSecondsFlow: SharedFlow<Long>,
    blackListSetFlow: SharedFlow<Set<String>>,
    shouldUseEnhancedCoverReadingFlow: SharedFlow<Boolean?>, // null means load if permission is granted
    recentlyAddedFilterSecondFlow: SharedFlow<Long?>, // null means don't generate recently added
    shouldIncludeExtraFormatFlow: SharedFlow<Boolean>,
    coverStubUri: String? = null
) {
    constructor(context: Context, configuration: FlowReaderConfiguration) :
            this(context, configuration.minSongLengthSecondsFlow,
                configuration.blackListSetFlow,
                configuration.shouldUseEnhancedCoverReadingFlow,
                configuration.recentlyAddedFilterSecondFlow,
                configuration.shouldIncludeExtraFormatFlow)
    // IMPORTANT: Do not use distinctUntilChanged() or StateFlow here because equals() on thousands
    // of MediaItems is very, very expensive!
    private var awaitingRefresh = false
    private val scope = CoroutineScope(Dispatchers.IO)
    private val finishRefreshTrigger = MutableSharedFlow<Unit>(replay = 0)
    private val manualRefreshTrigger = MutableSharedFlow<Unit>(replay = 1)
    init {
        manualRefreshTrigger.tryEmit(Unit)
    }
    // Start observing as soon as class gets instantiated. ContentObservers are cheap, and more
    // importantly, this allows us to skip the expensive Reader call if nothing changed while we
    // were inactive - that's the most common case!
    private val rawPlaylistVersionFlow = contentObserverVersioningFlow(
        context, scope,
        @Suppress("deprecation") MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI, true
    ).shareIn(scope, Eagerly, replay = 1)
    private val mediaVersionFlow = contentObserverVersioningFlow(
        context, scope, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true
    ).shareIn(scope, Eagerly, replay = 1)
    // These expensive Reader calls are only done if we have someone (UI) observing the result AND
    // something changed. The flowWhileShared() mechanism allows us to skip any unnecessary work.
    private val rawPlaylistFlow = sharedFlow(scope, replay = 1) { subscriptionCount ->
        rawPlaylistVersionFlow
            .flowWhileShared(subscriptionCount, WhileSubscribed())
            .distinctUntilChanged()
            .flatMapLatest {
                manualRefreshTrigger.mapLatest { _ ->
                    Reader.fetchPlaylists(context).first
                }
            }
    }
    private val readerFlow: SharedFlow<ReaderResult> = sharedFlow(scope, replay = 1) { subscriptionCount ->
        shouldIncludeExtraFormatFlow.distinctUntilChanged().flatMapLatest { shouldIncludeExtraFormat ->
            shouldUseEnhancedCoverReadingFlow.distinctUntilChanged().flatMapLatest { shouldUseEnhancedCoverReading ->
                minSongLengthSecondsFlow.distinctUntilChanged().flatMapLatest { minSongLengthSeconds ->
                    blackListSetFlow.distinctUntilChanged().flatMapLatest { blackListSet ->
                        mediaVersionFlow
                            .flowWhileShared(subscriptionCount, WhileSubscribed())
                            .distinctUntilChanged()
                            .flatMapLatest {
                                // manual refresh may for whatever reason run in background
                                // but all others shouldn't trigger background runs
                                manualRefreshTrigger.mapLatest { _ ->
                                    Reader.readFromMediaStore(
                                        context,
                                        minSongLengthSeconds,
                                        blackListSet,
                                        shouldUseEnhancedCoverReading,
                                        shouldIncludeExtraFormat,
                                        coverStubUri = coverStubUri
                                    )
                                }
                            }
                    }
                }
            }
        }.onEach {
            // These manual emit()s and collectOtherFlowWhenBeingCollected() have a significant
            // advantage over map(): the individual flow's replay cache always contains the latest
            // data we have even if nobody is collecting it. As we generate that data anyway, we
            // should make sure to always use the latest data we have.
            // We can do all these null assertions as we never pass shouldLoad*=false into Reader.
            songListFlowMutable.emit(it.songList)
            albumListFlowMutable.emit(it.albumList!!)
            albumArtistListFlowMutable.emit(it.albumArtistList!!)
            artistListFlowMutable.emit(it.artistList!!)
            genreListFlowMutable.emit(it.genreList!!)
            dateListFlowMutable.emit(it.dateList!!)
            idMapFlow.emit(it.idMap!!)
            folderStructureFlowMutable.emit(it.folderStructure!!)
            shallowFolderFlowMutable.emit(it.shallowFolder!!)
            foldersFlowMutable.emit(it.folders!!)
            finishRefreshTrigger.emit(Unit)
            awaitingRefresh = true
        }
    }
    private val idMapFlow = MutableSharedFlow<Map<Long, MediaItem>>(replay = 1)
        .collectOtherFlowWhenBeingCollected(scope, readerFlow)
    private val songListFlowMutable = MutableSharedFlow<List<MediaItem>>(replay = 1)
        .collectOtherFlowWhenBeingCollected(scope, readerFlow)
    val songListFlow: SharedFlow<List<MediaItem>> = songListFlowMutable
    private val recentlyAddedFlow = recentlyAddedFilterSecondFlow.distinctUntilChanged()
        .combine(songListFlowMutable) { recentlyAddedFilterSecond, songList ->
            if (recentlyAddedFilterSecond != null)
                RecentlyAdded(
                    (System.currentTimeMillis() / 1000L) - recentlyAddedFilterSecond,
                    songList
                )
            else
                null
        }
    private val mappedPlaylistsFlow = idMapFlow.combine(rawPlaylistFlow) { idMap, rawPlaylists ->
        rawPlaylists.map { it.toPlaylist(idMap) }
    }
    private val albumListFlowMutable = MutableSharedFlow<List<Album>>(replay = 1)
        .collectOtherFlowWhenBeingCollected(scope, readerFlow)
    private val albumArtistListFlowMutable = MutableSharedFlow<List<Artist>>(replay = 1)
        .collectOtherFlowWhenBeingCollected(scope, readerFlow)
    private val artistListFlowMutable = MutableSharedFlow<List<Artist>>(replay = 1)
        .collectOtherFlowWhenBeingCollected(scope, readerFlow)
    private val genreListFlowMutable = MutableSharedFlow<List<Genre>>(replay = 1)
        .collectOtherFlowWhenBeingCollected(scope, readerFlow)
    private val dateListFlowMutable = MutableSharedFlow<List<Date>>(replay = 1)
        .collectOtherFlowWhenBeingCollected(scope, readerFlow)
    val albumListFlow: SharedFlow<List<Album>> = albumListFlowMutable
    val albumArtistListFlow: SharedFlow<List<Artist>> = albumArtistListFlowMutable
    val artistListFlow: SharedFlow<List<Artist>> = artistListFlowMutable
    val genreListFlow: SharedFlow<List<Genre>> = genreListFlowMutable
    val dateListFlow: SharedFlow<List<Date>> = dateListFlowMutable
    val playlistListFlow = mappedPlaylistsFlow.combine(recentlyAddedFlow) { mappedPlaylists, recentlyAdded ->
        if (recentlyAdded != null) mappedPlaylists + recentlyAdded else mappedPlaylists
    }.shareIn(scope, WhileSubscribed(), replay = 1)
    private val folderStructureFlowMutable = MutableSharedFlow<FileNode>(replay = 1)
        .collectOtherFlowWhenBeingCollected(scope, readerFlow)
    private val shallowFolderFlowMutable = MutableSharedFlow<FileNode>(replay = 1)
        .collectOtherFlowWhenBeingCollected(scope, readerFlow)
    private val foldersFlowMutable = MutableSharedFlow<Set<String>>(replay = 1)
        .collectOtherFlowWhenBeingCollected(scope, readerFlow)
    val folderStructureFlow: SharedFlow<FileNode> = folderStructureFlowMutable
    val shallowFolderFlow: SharedFlow<FileNode> = shallowFolderFlowMutable
    val foldersFlow: SharedFlow<Set<String>> = foldersFlowMutable

    /**
     * If the library hasn't been loaded yet, forces a load of the library. Otherwise forces a
     * manual refresh of the library. Suspends until new data is available.
     */
    suspend fun refresh() {
        coroutineScope {
            if (!awaitingRefresh) {
                // The playlist flow uses pull principle, and causes readerFlow to refresh, so
                // getting a value here means all data is up to date
                playlistListFlow.first()
                return@coroutineScope
            }
            val waiter = launch {
                finishRefreshTrigger.first()
            }
            manualRefreshTrigger.emit(Unit)
            waiter.join()
        }
    }
}

class FlowReaderConfiguration(
    minSongLengthSeconds: Long = 0,
    blackListSet: Set<String> = setOf(),
    shouldUseEnhancedCoverReading: Boolean? = false, // null means load if permission is granted
    recentlyAddedFilterSecond: Long? = 1_209_600, // null means don't generate recently added
    shouldIncludeExtraFormat: Boolean = true) {
    val minSongLengthSecondsFlow = MutableStateFlow(minSongLengthSeconds)
    val blackListSetFlow = MutableStateFlow(blackListSet)
    val shouldUseEnhancedCoverReadingFlow = MutableStateFlow(shouldUseEnhancedCoverReading)
    val recentlyAddedFilterSecondFlow = MutableStateFlow(recentlyAddedFilterSecond)
    val shouldIncludeExtraFormatFlow = MutableStateFlow(shouldIncludeExtraFormat)
}