package uk.akane.libphonograph.constructor

import android.net.Uri

fun interface ItemConstructor<T> {
   fun constructItem(
       uri: Uri,
       mediaId: Long,
       mimeType: String,
       title: String,
       writer: String?,
       compilation: String?,
       composer: String?,
       artist: String?,
       albumTitle: String?,
       albumArtist: String?,
       artworkUri: Uri,
       cdTrackNumber: String?,
       trackNumber: Int?,
       discNumber: Int?,
       genre: String?,
       recordingDay: Int?,
       recordingMonth: Int?,
       recordingYear: Int?,
       releaseYear: Int?,
       artistId: Long?,
       albumId: Long?,
       genreId: Long?,
       author: String?,
       addDate: Long?,
       duration: Long?,
       modifiedDate: Long?,
   ): T
}