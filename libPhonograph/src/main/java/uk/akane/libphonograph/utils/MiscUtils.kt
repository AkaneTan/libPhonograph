package uk.akane.libphonograph.utils

import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import java.io.File
import uk.akane.libphonograph.ALLOWED_EXT
import uk.akane.libphonograph.TAG
import uk.akane.libphonograph.items.Album
import uk.akane.libphonograph.items.FileNode

object MiscUtils {
    data class FileNodeImpl(
        override val folderName: String
    ) : FileNode {
        override val folderList = hashMapOf<String, FileNode>()
        override val songList = mutableListOf<MediaItem>()
        override var albumId: Long? = null
        fun addSong(item: MediaItem, id: Long?) {
            if (albumId != null && id != albumId) {
                albumId = null
            } else if (albumId == null && songList.isEmpty()) {
                albumId = id
            }
            songList.add(item)
        }
    }

    fun handleMediaFolder(path: String, rootNode: FileNode): FileNode {
        val newPath = if (path.endsWith('/')) path.substring(1, path.length - 1)
        else path.substring(1)
        val splitPath = newPath.split('/')
        var node: FileNode = rootNode
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

    fun handleShallowMediaItem(
        mediaItem: MediaItem,
        albumId: Long?,
        path: String,
        shallowFolder: FileNode
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
        (folder as FileNodeImpl).addSong(mediaItem, albumId)
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

    data class AlbumImpl(
        override val id: Long?,
        override val title: String?,
        override val albumArtist: String?,
        override var albumArtistId: Long?,
        override val albumYear: Int?,
        override var cover: Uri?,
        override val songList: MutableList<MediaItem>
    ) : Album
}