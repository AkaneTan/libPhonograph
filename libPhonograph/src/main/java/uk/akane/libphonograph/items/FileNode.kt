package uk.akane.libphonograph.items

import androidx.media3.common.MediaItem

interface FileNode {
	val folderName: String
	val folderList: Map<String, FileNode>
	val songList: List<MediaItem>
	val albumId: Long?
}

object EmptyFileNode : FileNode {
	override val folderName: String
		get() = ""
	override val folderList = mapOf<String, FileNode>()
	override val songList = listOf<MediaItem>()
	override val albumId = null
}