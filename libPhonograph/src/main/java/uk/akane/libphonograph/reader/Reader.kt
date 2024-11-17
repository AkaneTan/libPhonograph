package uk.akane.libphonograph.reader

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.core.database.getIntOrNull
import androidx.core.database.getLongOrNull
import androidx.core.database.getStringOrNull
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
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
import uk.akane.libphonograph.putIfAbsentSupport
import uk.akane.libphonograph.utils.MiscUtils
import uk.akane.libphonograph.utils.MiscUtils.findBestCover
import uk.akane.libphonograph.utils.MiscUtils.handleMediaFolder
import uk.akane.libphonograph.utils.MiscUtils.handleShallowMediaItem
import java.io.File
import java.time.Instant
import java.time.ZoneId
import uk.akane.libphonograph.items.EXTRA_ADD_DATE
import uk.akane.libphonograph.items.EXTRA_ALBUM_ID
import uk.akane.libphonograph.items.EXTRA_ARTIST_ID
import uk.akane.libphonograph.items.EXTRA_AUTHOR
import uk.akane.libphonograph.items.EXTRA_CD_TRACK_NUMBER
import uk.akane.libphonograph.items.EXTRA_GENRE_ID
import uk.akane.libphonograph.items.EXTRA_MODIFIED_DATE
import uk.akane.libphonograph.items.RawPlaylist

object Reader {
    private val baseCoverUri = Uri.parse("content://media/external/audio/albumart")
    private val projection =
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

    @OptIn(UnstableApi::class)
    fun readFromMediaStore(
        context: Context,
        minSongLengthSeconds: Long = 0,
        blackListSet: Set<String> = setOf(),
        shouldUseEnhancedCoverReading: Boolean? = false, // null means load if permission is granted
        shouldIncludeExtraFormat: Boolean = true,
        shouldLoadAlbums: Boolean = true, // implies album artists too
        shouldLoadArtists: Boolean = true,
        shouldLoadGenres: Boolean = true,
        shouldLoadDates: Boolean = true,
        shouldLoadFolders: Boolean = true,
        shouldLoadFilesystem: Boolean = true,
        shouldLoadIdMap: Boolean = true
    ): ReaderResult {
        if (!shouldLoadFilesystem && shouldUseEnhancedCoverReading != false) {
            throw IllegalArgumentException("Enhanced cover loading requires loading filesystem")
        }
        if (!context.hasAudioPermission()) {
            throw SecurityException("Audio permission is not granted")
        }
        val useEnhancedCoverReading = if (hasScopedStorageWithMediaTypes() && !context.hasImagePermission()) {
            if (shouldUseEnhancedCoverReading == true)
                throw SecurityException("Requested enhanced cover reading but permission isn't granted")
            false
        } else shouldUseEnhancedCoverReading != false

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

        // Initialize list and maps.
        val coverCache = if (shouldLoadAlbums && useEnhancedCoverReading)
            hashMapOf<Long, Pair<File, FileNode>>() else null
        val folders = if (shouldLoadFolders) hashSetOf<String>() else null
        val root = if (shouldLoadFilesystem) MiscUtils.FileNodeImpl("storage") else null
        val shallowRoot = if (shouldLoadFolders) MiscUtils.FileNodeImpl("shallow") else null
        val songs = mutableListOf<MediaItem>()
        val albumMap = if (shouldLoadAlbums) hashMapOf<Long?, MiscUtils.AlbumImpl>() else null
        val artistMap = if (shouldLoadArtists) hashMapOf<Long?, Artist>() else null
        val artistCacheMap = if (shouldLoadAlbums) hashMapOf<String?, Long?>() else null
        val albumArtistMap = if (shouldLoadAlbums)
            hashMapOf<String?, Pair<MutableList<Album>, MutableList<MediaItem>>>() else null
        // Note: it has been observed on a user's Pixel(!) that MediaStore assigned 3 different IDs
        // for "Unknown genre" (null genre tag), hence we practically ignore genre IDs as key
        val genreMap = if (shouldLoadGenres) hashMapOf<String?, Genre>() else null
        val dateMap = if (shouldLoadDates) hashMapOf<Int?, Date>() else null
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
        val idMap = if (shouldLoadIdMap) hashMapOf<Long, MediaItem>() else null
        val cursor = context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            MediaStore.Audio.Media.TITLE + " COLLATE UNICODE ASC",
        )

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
                val skip = (duration != null && duration != 0L &&
                        duration < minSongLengthSeconds * 1000) || (fldPath == null
                        || blackListSet.contains(fldPath))
                // We need to add blacklisted songs to idMap as they can be referenced by playlist
                if (skip && idMap == null) continue
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
                var cdTrackNumber = cdTrackNumberColumn?.let { col -> it.getStringOrNull(col) }
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
                val imgUri = ContentUris.appendId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.buildUpon(), id
                ).appendPath("albumart").build()

                if (cdTrackNumber != null && trackNumber == null) {
                    cdTrackNumber.toIntOrNull()?.let {
                        trackNumber = it
                        cdTrackNumber = null
                    }
                }
                // Process track numbers that have disc number added on.
                // e.g. 1001 - Disc 01, Track 01
                if ((discNumber == null || discNumber == 0) && trackNumber != null &&
                    trackNumber >= 1000) {
                    discNumber = trackNumber / 1000
                    trackNumber %= 1000
                }

                // Build our mediaItem.
                val song = MediaItem.Builder()
                    .setUri(pathFile?.toUri())
                    .setMediaId(id.toString())
                    .setMimeType(mimeType)
                    .setMediaMetadata(
                        MediaMetadata
                            .Builder()
                            .setIsBrowsable(false)
                            .setIsPlayable(true)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                            .setDurationMs(duration)
                            .setTitle(title)
                            .setWriter(writer)
                            .setCompilation(compilation)
                            .setComposer(composer)
                            .setArtist(artist)
                            .setAlbumTitle(album)
                            .setAlbumArtist(albumArtist)
                            .setArtworkUri(imgUri)
                            .setTrackNumber(trackNumber)
                            .setDiscNumber(discNumber)
                            .setGenre(genre)
                            .setRecordingDay(dateTakenDay)
                            .setRecordingMonth(dateTakenMonth)
                            .setRecordingYear(dateTakenYear)
                            .setReleaseYear(year)
                            .setExtras(Bundle().apply {
                                if (artistId != null) {
                                    putLong(EXTRA_ARTIST_ID, artistId)
                                }
                                if (albumId != null) {
                                    putLong(EXTRA_ALBUM_ID, albumId)
                                }
                                if (genreId != null) {
                                    putLong(EXTRA_GENRE_ID, genreId)
                                }
                                putString(EXTRA_AUTHOR, author)
                                if (addDate != null) {
                                    putLong(EXTRA_ADD_DATE, addDate)
                                }
                                if (modifiedDate != null) {
                                    putLong(EXTRA_MODIFIED_DATE, modifiedDate)
                                }
                                putString(EXTRA_CD_TRACK_NUMBER, cdTrackNumber)
                            })
                            .build(),
                    ).build()
                // Build our metadata maps/lists.
                idMap?.put(id, song)
                // Now that the song can be found by playlists, do NOT register other metadata.
                if (skip) continue
                songs.add(song)
                (artistMap?.getOrPut(artistId) {
                    Artist(artistId, artist, mutableListOf(), mutableListOf())
                }?.songList as MutableList?)?.add(song)
                artistCacheMap?.putIfAbsentSupport(artist, artistId)
                albumMap?.getOrPut(albumId) {
                    // in enhanced cover loading case, cover uri is created later using coverCache
                    val cover = if (coverCache != null || albumId == null) null else
                            ContentUris.withAppendedId(baseCoverUri, albumId)
                    val artistStr = albumArtist ?: artist
                    val likelyArtist = albumIdToArtistMap?.get(albumId)
                        ?.run { if (second == artistStr) this else null }
                    MiscUtils.AlbumImpl(
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
                (genreMap?.getOrPut(genre) { Genre(genreId, genre, mutableListOf()) }?.songList
                        as MutableList?)?.add(song)
                (dateMap?.getOrPut(year) {
                    Date(
                        year?.toLong() ?: 0,
                        year?.toString(),
                        mutableListOf()
                    )
                }?.songList as MutableList?)?.add(song)
                if (shouldLoadFilesystem) {
                    val fn = handleMediaFolder(path, root!!)
                    (fn as MiscUtils.FileNodeImpl).addSong(song, albumId)
                    if (albumId != null) {
                        coverCache?.putIfAbsentSupport(albumId, Pair(pathFile.parentFile!!, fn))
                    }
                }
                if (shouldLoadFolders) {
                    handleShallowMediaItem(song, albumId, path, shallowRoot!!)
                    folders!!.add(fldPath)
                }
            }
        }

        // Parse all the lists.
        val albumList = albumMap?.values?.onEach {
            if (it.albumArtistId == null) {
                it.albumArtistId = artistCacheMap!![it.albumArtist]
            }
            (artistMap?.get(it.albumArtistId)?.albumList as MutableList?)?.add(it)
            // coverCache == null if !useEnhancedCoverReading
            coverCache?.get(it.id)?.let { p ->
                // if this is false, folder contains >1 albums
                if (p.second.albumId == it.id) {
                    findBestCover(p.first)?.let { f -> it.cover = f.toUri() }
                }
            }
        }?.toList<Album>()
        val artistList = artistMap?.values?.toList()
        val albumArtistList = albumArtistMap?.entries?.map { (artist, albumsAndSongs) ->
            Artist(artistCacheMap!![artist], artist, albumsAndSongs.second, albumsAndSongs.first)
        }?.toList()
        val genreList = genreMap?.values?.toList()
        val dateList = dateMap?.values?.toList()

        folders?.addAll(blackListSet)

        return ReaderResult(
            songs,
            albumList,
            albumArtistList,
            artistList,
            genreList,
            dateList,
            idMap,
            root,
            shallowRoot,
            folders
        )
    }

    fun fetchPlaylists(context: Context): Pair<List<RawPlaylist>, Boolean> {
        var foundPlaylistContent = false
        val playlists = mutableListOf<RawPlaylist>()
        context.contentResolver.query(
            @Suppress("DEPRECATION")
            MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI, arrayOf(
                @Suppress("DEPRECATION") MediaStore.Audio.Playlists._ID,
                @Suppress("DEPRECATION") MediaStore.Audio.Playlists.NAME
            ), null, null, null
        )?.use {
            val playlistIdColumn = it.getColumnIndexOrThrow(
                @Suppress("DEPRECATION") MediaStore.Audio.Playlists._ID
            )
            val playlistNameColumn = it.getColumnIndexOrThrow(
                @Suppress("DEPRECATION") MediaStore.Audio.Playlists.NAME
            )
            while (it.moveToNext()) {
                val playlistId = it.getLong(playlistIdColumn)
                val playlistName = it.getString(playlistNameColumn)?.ifEmpty { null }
                val content = mutableListOf<Long>()
                context.contentResolver.query(
                    @Suppress("DEPRECATION") MediaStore.Audio
                        .Playlists.Members.getContentUri("external", playlistId), arrayOf(
                        @Suppress("DEPRECATION") MediaStore.Audio.Playlists.Members.AUDIO_ID,
                    ), null, null, @Suppress("DEPRECATION")
                    MediaStore.Audio.Playlists.Members.PLAY_ORDER + " ASC"
                )?.use { cursor ->
                    val column = cursor.getColumnIndexOrThrow(
                        @Suppress("DEPRECATION") MediaStore.Audio.Playlists.Members.AUDIO_ID
                    )
                    while (cursor.moveToNext()) {
                        foundPlaylistContent = true
                        content.add(cursor.getLong(column))
                    }
                }
                playlists.add(RawPlaylist(playlistId, playlistName, content))
            }
        }
        return Pair(playlists, foundPlaylistContent)
    }
}