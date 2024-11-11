package uk.akane.libphonograph.reader

import uk.akane.libphonograph.items.Album
import uk.akane.libphonograph.items.Artist
import uk.akane.libphonograph.items.Date
import uk.akane.libphonograph.items.FileNode
import uk.akane.libphonograph.items.Genre
import uk.akane.libphonograph.items.Playlist

data class ReaderResult<T>(
    val songList: List<T>,
    val albumList: List<Album<T>>?,
    val albumArtistList: List<Artist<T>>?,
    val artistList: List<Artist<T>>?,
    val genreList: List<Genre<T>>?,
    val dateList: List<Date<T>>?,
    val playlistList: List<Playlist<T>>?,
    val folderStructure: FileNode<T>?,
    val shallowFolder: FileNode<T>?,
    val folders: Set<String>?
)
