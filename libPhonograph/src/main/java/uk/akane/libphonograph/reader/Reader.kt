package uk.akane.libphonograph.reader

import android.content.ContentUris
import android.content.Context
import android.content.res.Resources.NotFoundException
import android.net.Uri
import android.provider.MediaStore
import androidx.core.database.getIntOrNull
import androidx.core.database.getLongOrNull
import androidx.core.database.getStringOrNull
import androidx.core.net.toUri
import uk.akane.libphonograph.getColumnIndexOrNull
import uk.akane.libphonograph.hasAlbumArtistIdInMediaStore
import uk.akane.libphonograph.hasAudioPermission
import uk.akane.libphonograph.hasImagePermission
import uk.akane.libphonograph.hasImprovedMediaStore
import uk.akane.libphonograph.hasScopedStorageWithMediaTypes
import uk.akane.libphonograph.items.Album
import uk.akane.libphonograph.items.Artist
import uk.akane.libphonograph.items.Date
import uk.akane.libphonograph.items.FileNode
import uk.akane.libphonograph.items.Genre
import uk.akane.libphonograph.items.Playlist
import uk.akane.libphonograph.putIfAbsentSupport
import uk.akane.libphonograph.utils.MiscUtils
import uk.akane.libphonograph.utils.MiscUtils.fetchPlaylists
import uk.akane.libphonograph.utils.MiscUtils.findBestCover
import uk.akane.libphonograph.utils.MiscUtils.handleMediaFolder
import uk.akane.libphonograph.utils.MiscUtils.handleShallowMediaItem
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.PriorityQueue

object Reader {
    class RecentlyAdded<T>(minAddDate: Long, songList: PriorityQueue<Pair<Long, T>>) : Playlist<T>(
        -1, null, mutableListOf()
    ) {
        private val rawList: PriorityQueue<Pair<Long, T>> = songList
        private var filteredList: List<T>? = null
        var minAddDate: Long = minAddDate
            set(value) {
                if (field != value) {
                    field = value
                    filteredList = null
                }
            }
        override val songList: MutableList<T>
            get() {
                if (filteredList == null) {
                    val queue = PriorityQueue(rawList)
                    filteredList = mutableListOf<T>().also {
                        while (!queue.isEmpty()) {
                            val item = queue.poll()!!
                            if (item.first < minAddDate) return@also
                            it.add(item.second)
                        }
                    }
                }
                return filteredList!!.toMutableList()
            }
    }

    // TODO try enabling inline when it stops generating kotlin errors in the demo app :'D
    fun <T> readFromMediaStore(
        context: Context,
        itemConstructor: (uri: Uri?, mediaId: Long, mimeType: String, title: String,
                                      writer: String?, compilation: String?, composer: String?,
                                      artist: String?, albumTitle: String?, albumArtist: String?,
                                      artworkUri: Uri, cdTrackNumber: String?, trackNumber: Int?,
                                      discNumber: Int?, genre: String?, recordingDay: Int?,
                                      recordingMonth: Int?, recordingYear: Int?, releaseYear: Int?,
                                      artistId: Long?, albumId: Long?, genreId: Long?,
                                      author: String?, addDate: Long?, duration: Long?,
                                      modifiedDate: Long?) -> T,
        minSongLengthSeconds: Long = 0,
        blackListSet: Set<String> = setOf(),
        shouldUseEnhancedCoverReading: Boolean? = false, // null means load if permission is granted
        shouldIncludeExtraFormat: Boolean = true,
        shouldLoadPlaylists: Boolean = false,
        shouldGenerateRecentlyAdded: Boolean = shouldLoadPlaylists,
        recentlyAddedFilterSecond: Long = 1_209_600,
        shouldLoadAlbums: Boolean = true, // implies album artists too
        shouldLoadArtists: Boolean = true,
        shouldLoadGenres: Boolean = true,
        shouldLoadDates: Boolean = true,
        shouldLoadFolders: Boolean = true,
        shouldLoadFilesystem: Boolean = true
    ): ReaderResult<T> {
        if (!shouldLoadFilesystem && shouldUseEnhancedCoverReading != false) {
            throw IllegalArgumentException("Enhanced cover loading requires loading filesystem")
        }
        if (!context.hasAudioPermission()) {
            throw SecurityException("Audio permission is not granted")
        }
        if (shouldUseEnhancedCoverReading == true && hasScopedStorageWithMediaTypes() &&
            !context.hasImagePermission()
        ) {
            throw SecurityException("Requested enhanced cover reading but permission isn't granted")
        }

        var selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        if (shouldIncludeExtraFormat) {
            selection += listOf(
                "audio/x-wav",
                "audio/ogg",
                "audio/aac",
                "audio/midi"
            ).joinToString("") {
                " or ${MediaStore.Audio.Media.MIME_TYPE} = '$it'"
            }
        }

        val projection =
            arrayListOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ARTIST_ID,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ARTIST,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.YEAR,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.TRACK,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DATE_MODIFIED
            ).apply {
                if (hasImprovedMediaStore()) {
                    add(MediaStore.Audio.Media.GENRE)
                    add(MediaStore.Audio.Media.GENRE_ID)
                    add(MediaStore.Audio.Media.CD_TRACK_NUMBER)
                    add(MediaStore.Audio.Media.COMPILATION)
                    add(MediaStore.Audio.Media.COMPOSER)
                    add(MediaStore.Audio.Media.DATE_TAKEN)
                    add(MediaStore.Audio.Media.WRITER)
                    add(MediaStore.Audio.Media.DISC_NUMBER)
                    add(MediaStore.Audio.Media.AUTHOR)
                }
            }.toTypedArray()

        val coverUri = Uri.parse("content://media/external/audio/albumart")

        // Initialize list and maps.
        val coverCache = if (shouldLoadAlbums && (shouldUseEnhancedCoverReading == true ||
            (shouldUseEnhancedCoverReading == null && hasScopedStorageWithMediaTypes() &&
                        context.hasImagePermission())))
            hashMapOf<Long, Pair<File, FileNode<T>>>() else null
        val folders = if (shouldLoadFolders) hashSetOf<String>() else null
        val root = if (shouldLoadFilesystem) MiscUtils.FileNodeImpl<T>("storage") else null
        val shallowRoot = if (shouldLoadFolders) MiscUtils.FileNodeImpl<T>("shallow") else null
        val songs = mutableListOf<T>()
        val albumMap = if (shouldLoadAlbums) hashMapOf<Long?, MiscUtils.AlbumImpl<T>>() else null
        val artistMap = if (shouldLoadArtists) hashMapOf<Long?, Artist<T>>() else null
        val artistCacheMap = if (shouldLoadAlbums) hashMapOf<String?, Long?>() else null
        val albumArtistMap = if (shouldLoadAlbums)
            hashMapOf<String?, Pair<MutableList<Album<T>>, MutableList<T>>>() else null
        // Note: it has been observed on a user's Pixel(!) that MediaStore assigned 3 different IDs
        // for "Unknown genre" (null genre tag), hence we practically ignore genre IDs as key
        val genreMap = if (shouldLoadGenres) hashMapOf<String?, Genre<T>>() else null
        val dateMap = if (shouldLoadDates) hashMapOf<Int?, Date<T>>() else null
        val albumIdToArtistMap = if (hasAlbumArtistIdInMediaStore()) {
            val map = hashMapOf<Long, Pair<Long, String?>>()
            context.contentResolver.query(
                MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, arrayOf(
                    MediaStore.Audio.Albums._ID,
                    MediaStore.Audio.Albums.ARTIST, MediaStore.Audio.Albums.ARTIST_ID
                ), null, null, null
            )?.use {
                val idColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val artistColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val artistIdColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST_ID)
                while (it.moveToNext()) {
                    val artistId = it.getLongOrNull(artistIdColumn)
                    if (artistId != null) {
                        val id = it.getLong(idColumn)
                        val artistName = it.getStringOrNull(artistColumn)?.ifEmpty { null }
                        map[id] = Pair(artistId, artistName)
                    }
                }
            }
            map
        } else null
        val (playlists, foundPlaylistContent) = if (shouldLoadPlaylists)
            fetchPlaylists<T>(context) else Pair(null, false)
        val idMap = if (foundPlaylistContent) hashMapOf<Long, T>() else null
        val cursor = context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            MediaStore.Audio.Media.TITLE + " COLLATE UNICODE ASC",
        )
        val recentlyAddedMap = if (shouldGenerateRecentlyAdded) PriorityQueue<Pair<Long, T>>(
            // PriorityQueue throws if initialCapacity < 1
            (cursor?.count ?: 1).coerceAtLeast(1),
            Comparator { a, b ->
                // reversed int order to sort from most recent to least recent
                return@Comparator if (a.first == b.first) 0 else (if (a.first > b.first) -1 else 1)
            }) else null

        cursor?.use {
            // Get columns from mediaStore.
            val idColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumArtistColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ARTIST)
            val pathColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val yearColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val albumIdColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val artistIdColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST_ID)
            val mimeTypeColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val discNumberColumn = it.getColumnIndexOrNull(MediaStore.Audio.Media.DISC_NUMBER)
            val trackNumberColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val genreColumn = if (hasImprovedMediaStore())
                it.getColumnIndexOrThrow(MediaStore.Audio.Media.GENRE) else null
            val genreIdColumn = if (hasImprovedMediaStore())
                it.getColumnIndexOrThrow(MediaStore.Audio.Media.GENRE_ID) else null
            val cdTrackNumberColumn = if (hasImprovedMediaStore())
                it.getColumnIndexOrThrow(MediaStore.Audio.Media.CD_TRACK_NUMBER) else null
            val compilationColumn = if (hasImprovedMediaStore())
                it.getColumnIndexOrThrow(MediaStore.Audio.Media.COMPILATION) else null
            val dateTakenColumn = if (hasImprovedMediaStore())
                it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_TAKEN) else null
            val composerColumn = if (hasImprovedMediaStore())
                it.getColumnIndexOrThrow(MediaStore.Audio.Media.COMPOSER) else null
            val writerColumn = if (hasImprovedMediaStore())
                it.getColumnIndexOrThrow(MediaStore.Audio.Media.WRITER) else null
            val authorColumn = if (hasImprovedMediaStore())
                it.getColumnIndexOrThrow(MediaStore.Audio.Media.AUTHOR) else null
            val durationColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val addDateColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val modifiedDateColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)

            while (it.moveToNext()) {
                val path = it.getStringOrNull(pathColumn)
                val duration = it.getLongOrNull(durationColumn)
                val pathFile = path?.let { it1 -> File(it1) }
                val fldPath = pathFile?.parentFile?.absolutePath
                val skip = (duration != null && duration < minSongLengthSeconds * 1000) ||
                        (fldPath == null || blackListSet.contains(fldPath))
                // We need to add blacklisted songs to idMap as they can be referenced by playlist
                if (skip && !foundPlaylistContent) continue
                val id = it.getLongOrNull(idColumn)!!
                val title = it.getStringOrNull(titleColumn)!!
                val artist = it.getStringOrNull(artistColumn)
                    .let { v -> if (v == "<unknown>") null else v }
                val album = it.getStringOrNull(albumColumn)
                val albumArtist = it.getStringOrNull(albumArtistColumn)
                val year = it.getIntOrNull(yearColumn).let { v -> if (v == 0) null else v }
                val albumId = it.getLongOrNull(albumIdColumn)
                val artistId = it.getLongOrNull(artistIdColumn)
                val mimeType = it.getStringOrNull(mimeTypeColumn)!!
                var discNumber = discNumberColumn?.let { col -> it.getIntOrNull(col) }
                var trackNumber = it.getIntOrNull(trackNumberColumn)
                val cdTrackNumber = cdTrackNumberColumn?.let { col -> it.getStringOrNull(col) }
                val compilation = compilationColumn?.let { col -> it.getStringOrNull(col) }
                val dateTaken = dateTakenColumn?.let { col -> it.getStringOrNull(col) }
                val composer = composerColumn?.let { col -> it.getStringOrNull(col) }
                val writer = writerColumn?.let { col -> it.getStringOrNull(col) }
                val author = authorColumn?.let { col -> it.getStringOrNull(col) }
                val genre = genreColumn?.let { col -> it.getStringOrNull(col) }
                val genreId = genreIdColumn?.let { col -> it.getLongOrNull(col) }
                val addDate = it.getLongOrNull(addDateColumn)
                val modifiedDate = it.getLongOrNull(modifiedDateColumn)
                val dateTakenParsed = if (hasImprovedMediaStore()) {
                    // the column exists since R, so we can always use these APIs
                    dateTaken?.toLongOrNull()?.let { it1 -> Instant.ofEpochMilli(it1) }
                        ?.atZone(ZoneId.systemDefault())
                } else null
                val dateTakenYear = if (hasImprovedMediaStore()) {
                    dateTakenParsed?.year
                } else null
                val dateTakenMonth = if (hasImprovedMediaStore()) {
                    dateTakenParsed?.monthValue
                } else null
                val dateTakenDay = if (hasImprovedMediaStore()) {
                    dateTakenParsed?.dayOfMonth
                } else null

                // Since we're using glide, we can get album cover with a uri.
                val imgUri = ContentUris.appendId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.buildUpon(), id
                )
                    .appendPath("albumart").build()

                // Process track numbers that have disc number added on.
                // e.g. 1001 - Disc 01, Track 01
                if (trackNumber != null && trackNumber >= 1000) {
                    discNumber = trackNumber / 1000
                    trackNumber %= 1000
                }

                // Build our mediaItem.
                val song = itemConstructor.invoke(
                    pathFile?.toUri(),
                    id,
                    mimeType,
                    title,
                    writer,
                    compilation,
                    composer,
                    artist,
                    album,
                    albumArtist,
                    imgUri,
                    cdTrackNumber,
                    trackNumber,
                    discNumber,
                    genre,
                    dateTakenDay,
                    dateTakenMonth,
                    dateTakenYear,
                    year,
                    artistId,
                    albumId,
                    genreId,
                    author,
                    addDate,
                    duration,
                    modifiedDate,
                )
                // Build our metadata maps/lists.
                idMap?.put(id, song)
                // Now that the song can be found by playlists, do NOT register other metadata.
                if (skip) continue
                songs.add(song)
                if (addDate != null) {
                    recentlyAddedMap?.add(Pair(addDate, song))
                }
                artistMap?.getOrPut(artistId) {
                    Artist(artistId, artist, mutableListOf(), mutableListOf())
                }?.songList?.add(song)
                artistCacheMap?.putIfAbsentSupport(artist, artistId)
                albumMap?.getOrPut(albumId) {
                    // in enhanced cover loading case, cover uri is created later using coverCache
                    val cover = if (coverCache != null || albumId == null) null else
                            ContentUris.withAppendedId(coverUri, albumId)
                    val artistStr = albumArtist ?: artist
                    val likelyArtist = albumIdToArtistMap?.get(albumId)
                        ?.run { if (second == artistStr) this else null }
                    MiscUtils.AlbumImpl<T>(
                        albumId,
                        album,
                        artistStr,
                        likelyArtist?.first,
                        year,
                        cover,
                        mutableListOf()
                    ).also { alb ->
                        albumArtistMap?.getOrPut(artistStr) {
                            Pair(
                                mutableListOf(),
                                mutableListOf()
                            )
                        }?.first?.add(alb)
                    }
                }?.also { alb ->
                    albumArtistMap?.getOrPut(alb.albumArtist) {
                        Pair(mutableListOf(), mutableListOf())
                    }?.second?.add(song)
                }?.songList?.add(song)
                genreMap?.getOrPut(genre) { Genre(genreId, genre, mutableListOf()) }?.songList?.add(
                    song
                )
                dateMap?.getOrPut(year) {
                    Date(
                        year?.toLong() ?: 0,
                        year?.toString(),
                        mutableListOf()
                    )
                }?.songList?.add(song)
                if (shouldLoadFilesystem) {
                    val fn = handleMediaFolder(path!!, root!!)
                    (fn as MiscUtils.FileNodeImpl<T>).addSong(song, albumId)
                    if (albumId != null) {
                        coverCache?.putIfAbsentSupport(albumId, Pair(pathFile!!.parentFile!!, fn))
                    }
                }
                if (shouldLoadFolders) {
                    handleShallowMediaItem(song, albumId, path!!, shallowRoot!!)
                    folders!!.add(fldPath!!)
                }
            }
        }

        // Parse all the lists.
        val albumList = albumMap?.values?.onEach {
            if (it.albumArtistId == null) {
                it.albumArtistId = artistCacheMap!![it.albumArtist]
            }
            artistMap?.get(it.albumArtistId)?.albumList?.add(it)
            // coverCache == null if !haveImgPerm
            coverCache?.get(it.id)?.let { p ->
                // if this is false, folder contains >1 albums
                if (p.second.albumId == it.id) {
                    findBestCover(p.first)?.let { f -> it.cover = f.toUri() }
                }
            }
        }?.toMutableList<Album<T>>()
        val artistList = artistMap?.values?.toMutableList()
        val albumArtistList = albumArtistMap?.entries?.map { (artist, albumsAndSongs) ->
            Artist(artistCacheMap!![artist], artist, albumsAndSongs.second, albumsAndSongs.first)
        }?.toMutableList()
        val genreList = genreMap?.values?.toMutableList()
        val dateList = dateMap?.values?.toMutableList()
        val playlistsFinal = playlists?.map {
                it.first.also { playlist ->
                    playlist.songList.addAll(it.second.mapNotNull { value ->
                        idMap!![value]
                        // if value is null it's 100% of time a library (or MediaStore?) bug
                        // and because I found the MediaStore bug in the wild, don't be so stingy
                    })
                }
            }?.toMutableList() ?: if (recentlyAddedMap != null) mutableListOf() else null

        if (recentlyAddedMap != null) {
            playlistsFinal!!.add(
                RecentlyAdded(
                    (System.currentTimeMillis() / 1000) - recentlyAddedFilterSecond,
                    recentlyAddedMap
                )
            )
        }

        folders?.addAll(blackListSet)

        return ReaderResult(
            songs,
            albumList,
            albumArtistList,
            artistList,
            genreList,
            dateList,
            playlistsFinal,
            root,
            shallowRoot,
            folders
        )
    }
}