package uk.akane.libphonograph.constructor

object FolderStructureConstructor {
    class FileNode <T> (
        val folderName: String
    ) {
        val folderList = hashMapOf<String, FileNode<T>>()
        val songList = mutableListOf<T>()
        var albumId: Long? = null
            private set
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
                newNode = FileNode(fld)
                node.folderList[newNode.folderName] = newNode
            }
            node = newNode
        }
        return node
    }

    fun <T> handleShallowMediaItem(
        mediaItem: T,
        albumId: Long?,
        path: String,
        shallowFolder: FileNode<T>,
        folderArray: MutableList<String>
    ) {
        val newPath = if (path.endsWith('/')) path.substring(0, path.length - 1) else path
        val splitPath = newPath.split('/')
        if (splitPath.size < 2) throw IllegalArgumentException("splitPath.size < 2: $newPath")
        val lastFolderName = splitPath[splitPath.size - 2]
        var folder = shallowFolder.folderList[lastFolderName]
        if (folder == null) {
            folder = FileNode(lastFolderName)
            shallowFolder.folderList[folder.folderName] = folder
            // hack to cut off /
            folderArray.add(
                newPath.substring(0, splitPath[splitPath.size - 1].length + 1)
            )
        }
        folder.addSong(mediaItem, albumId)
    }
}