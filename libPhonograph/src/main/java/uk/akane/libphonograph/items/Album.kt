package uk.akane.libphonograph.items

import android.net.Uri

interface Album<T> : Item<T> {
	override val id: Long?
	override val title: String?
	override val songList: List<T>
	val albumArtist: String?
	val albumArtistId: Long?
	val albumYear: Int?
	val cover: Uri?
}