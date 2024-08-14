package uk.akane.libphonograph.flow

import android.content.Context
import android.provider.MediaStore
import androidx.core.net.toFile
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import uk.akane.libphonograph.hasAudioPermission
import uk.akane.libphonograph.hasImagePermission
import uk.akane.libphonograph.hasImprovedMediaStore
import uk.akane.libphonograph.hasScopedStorageWithMediaTypes

class Library(context: Context,
              extraFormatsFlow: SharedFlow<Boolean>,
              enhancedCoverReadingFlow: SharedFlow<Boolean>,
              blacklistedFoldersFlow: SharedFlow<Set<String>>,
              minLengthFlow: SharedFlow<Int>) {
	private val filterFlow = minLengthFlow.combine(blacklistedFoldersFlow) { a, b -> Pair(a, b) }
	@OptIn(ExperimentalCoroutinesApi::class)
	private val rawSongsFlow = extraFormatsFlow.flatMapLatest { extraFormats ->
		val filter = filterFlow.first()
		val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0".let {
			return@let if (extraFormats) {
				 it.plus(listOf(
					"audio/x-wav",
					"audio/ogg",
					"audio/aac",
					"audio/midi"
				).joinToString("") { format ->
					" or ${MediaStore.Audio.Media.MIME_TYPE} = '$format'"
				})
			} else it
		}
		callbackFlow<PreprocessedLibraryData> {
			//if (!shouldLoadFilesystem && shouldUseEnhancedCoverReading != false) {
			//	throw IllegalArgumentException("Enhanced cover loading requires loading filesystem")
			//}
			if (!context.hasAudioPermission()) {
				throw SecurityException("Audio permission is not granted")
			}
			if (shouldUseEnhancedCoverReading == true && hasScopedStorageWithMediaTypes() &&
				!context.hasImagePermission()
			) {
				throw SecurityException("Requested enhanced cover reading but permission isn't granted")
			}

			// TODO register callback
			awaitClose { /* TODO */ }
		}
	}
	val songsFlow = rawSongsFlow.map { it.songs }
	val songsFilteredFlow = rawSongsFlow.combine(filterFlow) { songs, filter ->
		if (filter == songs.filter) {
			return@combine songs.songsFiltered
		}
		songs.filter = filter
		songs.songsFiltered = songs.songs.applyFilters(filter)
		return@combine songs.songsFiltered
	}.distinctUntilChanged()
	private data class PreprocessedLibraryData(val songs: List<MediaItem>, var songsFiltered: List<MediaItem>, var filter: Pair<Int, Set<String>>)
	@androidx.annotation.OptIn(UnstableApi::class)
	private fun List<MediaItem>.applyFilters(filter: Pair<Int, Set<String>>): List<MediaItem> {
		return filter { song ->
			val ap = song.localConfiguration?.uri?.toFile()?.parentFile?.absolutePath
			(ap == null || !filter.second.contains(ap)) && (song.mediaMetadata.durationMs == null
					|| song.mediaMetadata.durationMs!! >= (filter.first * 1000))
		}
	}
	private fun getMediaStoreProjection() =
		arrayListOf(
			MediaStore.Audio.Media._ID,
			MediaStore.Audio.Media.TITLE,
			MediaStore.Audio.Media.ARTIST,
			MediaStore.Audio.Media.ARTIST_ID,
			MediaStore.Audio.Media.ALBUM,
			MediaStore.Audio.Media.ALBUM_ARTIST,
			MediaStore.Audio.Media.DATA,
			MediaStore.Audio.Media.YEAR,
			MediaStore.Audio.Media.ALBUM_ID,
			MediaStore.Audio.Media.MIME_TYPE,
			MediaStore.Audio.Media.TRACK,
			MediaStore.Audio.Media.DURATION,
			MediaStore.Audio.Media.DATE_ADDED,
			MediaStore.Audio.Media.DATE_MODIFIED
		).apply {
			if (hasImprovedMediaStore()) {
				add(MediaStore.Audio.Media.GENRE)
				add(MediaStore.Audio.Media.GENRE_ID)
				add(MediaStore.Audio.Media.CD_TRACK_NUMBER)
				add(MediaStore.Audio.Media.COMPILATION)
				add(MediaStore.Audio.Media.COMPOSER)
				add(MediaStore.Audio.Media.DATE_TAKEN)
				add(MediaStore.Audio.Media.WRITER)
				add(MediaStore.Audio.Media.DISC_NUMBER)
				add(MediaStore.Audio.Media.AUTHOR)
			}
		}.toTypedArray()
	enum class LoadPlaylistSettings {
		No, Yes, YesWithRecentlyAdded, OnlyRecentlyAdded
	}
}