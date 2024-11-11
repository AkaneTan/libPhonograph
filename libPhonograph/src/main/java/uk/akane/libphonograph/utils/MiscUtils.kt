package uk.akane.libphonograph.utils

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import uk.akane.libphonograph.ALLOWED_EXT
import uk.akane.libphonograph.TAG
import uk.akane.libphonograph.items.Album
import uk.akane.libphonograph.items.FileNode
import uk.akane.libphonograph.items.Playlist
import java.io.File

object MiscUtils {
    class FileNodeImpl<T>(
        override val folderName: String
    ) : FileNode<T> {
        override val folderList = hashMapOf<String, FileNode<T>>()
        override val songList = mutableListOf<T>()
        override var albumId: Long? = null
        fun addSong(item: T, id: Long?) {
            if (albumId != null && id != albumId) {
                albumId = null
            } else if (albumId == null && songList.isEmpty()) {
                albumId = id
            }
            songList.add(item)
        }
    }

    fun <T> handleMediaFolder(path: String, rootNode: FileNode<T>): FileNode<T> {
        val newPath = if (path.endsWith('/')) path.substring(1, path.length - 1)
        else path.substring(1)
        val splitPath = newPath.split('/')
        var node: FileNode<T> = rootNode
        for (fld in splitPath.subList(0, splitPath.size - 1)) {
            var newNode = node.folderList[fld]
            if (newNode == null) {
                newNode = FileNodeImpl(fld)
                (node.folderList as HashMap)[newNode.folderName] = newNode
            }
            node = newNode
        }
        return node
    }

    fun <T> handleShallowMediaItem(
        mediaItem: T,
        albumId: Long?,
        path: String,
        shallowFolder: FileNode<T>
    ) {
        val newPath = if (path.endsWith('/')) path.substring(0, path.length - 1) else path
        val splitPath = newPath.split('/')
        if (splitPath.size < 2) throw IllegalArgumentException("splitPath.size < 2: $newPath")
        val lastFolderName = splitPath[splitPath.size - 2]
        var folder = (shallowFolder.folderList as HashMap)[lastFolderName]
        if (folder == null) {
            folder = FileNodeImpl(lastFolderName)
            (shallowFolder.folderList as HashMap)[folder.folderName] = folder
        }
        (folder as FileNodeImpl<T>).addSong(mediaItem, albumId)
    }

    fun findBestCover(songFolder: File): File? {
        var bestScore = 0
        var bestFile: File? = null
        try {
            val files = songFolder.listFiles() ?: return null
            for (file in files) {
                if (file.extension !in ALLOWED_EXT)
                    continue
                var score = 1
                when (file.extension) {
                    "jpg" -> score += 3
                    "png" -> score += 2
                    "jpeg" -> score += 1
                }
                if (file.nameWithoutExtension == "albumart") score += 24
                else if (file.nameWithoutExtension == "cover") score += 20
                else if (file.nameWithoutExtension.startsWith("albumart")) score += 16
                else if (file.nameWithoutExtension.startsWith("cover")) score += 12
                else if (file.nameWithoutExtension.contains("albumart")) score += 8
                else if (file.nameWithoutExtension.contains("cover")) score += 4
                if (bestScore < score) {
                    bestScore = score
                    bestFile = file
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, Log.getStackTraceString(e))
        }
        // allow .jpg or .png files with any name, but only permit more exotic
        // formats if name contains either cover or albumart
        if (bestScore >= 3) {
            return bestFile
        }
        return null
    }

    fun <T> fetchPlaylists(context: Context):
            Pair<List<RawPlaylist<T>>, Boolean> {
        var foundPlaylistContent = false
        val playlists = mutableListOf<RawPlaylist<T>>()
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
                playlists.add(RawPlaylist<T>(playlistId, playlistName, content))
            }
        }
        return Pair(playlists, foundPlaylistContent)
    }

    data class RawPlaylist<T>(
        val id: Long?,
        val title: String?,
        val songList: List<Long>
    ) {
        // idMap may be null if and only if all playlists are empty
        fun toPlaylist(idMap: Map<Long, T>?): Playlist<T> {
            return Playlist(id, title, songList.mapNotNull { value ->
                idMap!![value]
                // if song is null it's 100% of time a library (or MediaStore?) bug
                // and because I found the MediaStore bug in the wild, don't be so stingy
            }.toMutableList()) // TODO remove .toMutableList()
        }
    }

    data class AlbumImpl<T>(
        override val id: Long?,
        override val title: String?,
        override val albumArtist: String?,
        override var albumArtistId: Long?,
        override val albumYear: Int?,
        override var cover: Uri?,
        override val songList: MutableList<T>
    ) : Album<T>
}