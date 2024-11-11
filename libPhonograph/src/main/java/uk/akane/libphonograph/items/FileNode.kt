package uk.akane.libphonograph.items

interface FileNode<T> {
	val folderName: String
	val folderList: Map<String, FileNode<T>>
	val songList: List<T>
	val albumId: Long?
}