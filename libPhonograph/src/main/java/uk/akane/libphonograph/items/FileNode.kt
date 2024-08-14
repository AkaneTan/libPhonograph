package uk.akane.libphonograph.items

interface FileNode<T> {
	val folderName: String
	val folderList: HashMap<String, FileNode<T>>
	val songList: MutableList<T>
	val albumId: Long?
}