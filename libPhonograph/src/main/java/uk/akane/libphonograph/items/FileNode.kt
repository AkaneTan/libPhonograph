package uk.akane.libphonograph.items

import androidx.media3.common.MediaItem

interface FileNode {
	val folderName: String
	val folderList: Map<String, FileNode>
	val songList: List<MediaItem>
	val albumId: Long?
}