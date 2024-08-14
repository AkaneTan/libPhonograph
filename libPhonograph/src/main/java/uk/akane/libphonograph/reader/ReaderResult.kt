package uk.akane.libphonograph.reader

import uk.akane.libphonograph.items.Album
import uk.akane.libphonograph.items.Artist
import uk.akane.libphonograph.items.Date
import uk.akane.libphonograph.items.FileNode
import uk.akane.libphonograph.items.Genre
import uk.akane.libphonograph.items.Playlist

data class ReaderResult<T>(
    val songList: MutableList<T>,
    val albumList: MutableList<Album<T>>?,
    val albumArtistList: MutableList<Artist<T>>?,
    val artistList: MutableList<Artist<T>>?,
    val genreList: MutableList<Genre<T>>?,
    val dateList: MutableList<Date<T>>?,
    val playlistList: MutableList<Playlist<T>>?,
    val folderStructure: FileNode<T>?,
    val shallowFolder: FileNode<T>?,
    val folders: Set<String>?
)
