package uk.akane.phonographdemo

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.akane.libphonograph.hasScopedStorageV2
import uk.akane.libphonograph.hasScopedStorageWithMediaTypes
import uk.akane.libphonograph.reader.Reader
import uk.akane.libphonograph.reader.ReaderResult
import kotlin.system.measureTimeMillis

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_READ_MEDIA_AUDIO = 100
    }
    
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: Adapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        recyclerView = findViewById(R.id.rv)
        adapter = Adapter()
        
        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)

        val topAppBar: MaterialToolbar = findViewById(R.id.topAppBar)
        topAppBar.setOnMenuItemClickListener {
            fetchData()
            true
        }

        if ((hasScopedStorageWithMediaTypes()
                    && ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.READ_MEDIA_AUDIO,
            ) != PackageManager.PERMISSION_GRANTED)
            || (!hasScopedStorageV2()
                    && ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED)
            || (!hasScopedStorageWithMediaTypes()
                    && ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED)
        ) {
            // Ask if was denied.
            ActivityCompat.requestPermissions(
                this,
                if (hasScopedStorageWithMediaTypes())
                    arrayOf(android.Manifest.permission.READ_MEDIA_AUDIO)
                else if (hasScopedStorageV2())
                    arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                else
                    arrayOf(
                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ),
                PERMISSION_READ_MEDIA_AUDIO,
            )
        } else {
            fetchData()
        }
    }
    @SuppressLint("StringFormatMatches")
    private fun showPrompt() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.complete)
            .setIcon(R.drawable.ic_check_circle)
            .setMessage(
                getString(
                    R.string.prompt_str,
                    result!!.songList.size,
                    result!!.albumList!!.size,
                    result!!.artistList!!.size,
                    result!!.playlistList!!.size,
                    result!!.folders!!.size,
                    lastResultTime
                ))
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private var coroutineScope: Job? = null
    private var result: ReaderResult<MediaItem>? = null
    private var lastResultTime: Long = 0

    private fun fetchData() {
        coroutineScope?.cancel()
        coroutineScope = CoroutineScope(Dispatchers.IO).launch {
            lastResultTime = measureTimeMillis {
                result = Reader.readFromMediaStore(
                    this@MainActivity,
                    { uri, mediaId, mimeType, title, writer, compilation,
                      composer, artist, albumTitle, albumArtist, artworkUri,
                      cdTrackNumber, trackNumber, discNumber, genre,
                      recordingDay, recordingMonth, recordingYear, releaseYear,
                      artistId, albumId, genreId, author, addDate,
                      duration, modifiedDate ->
                        return@readFromMediaStore MediaItem
                            .Builder()
                            .setUri(uri)
                            .setMediaId(mediaId.toString())
                            .setMimeType(mimeType)
                            .setMediaMetadata(
                                MediaMetadata
                                    .Builder()
                                    .setIsBrowsable(false)
                                    .setIsPlayable(true)
                                    .setTitle(title)
                                    .setWriter(writer)
                                    .setCompilation(compilation)
                                    .setComposer(composer)
                                    .setArtist(artist)
                                    .setAlbumTitle(albumTitle)
                                    .setAlbumArtist(albumArtist)
                                    .setArtworkUri(artworkUri)
                                    .setTrackNumber(trackNumber)
                                    .setDiscNumber(discNumber)
                                    .setGenre(genre)
                                    .setRecordingDay(recordingDay)
                                    .setRecordingMonth(recordingMonth)
                                    .setRecordingYear(recordingYear)
                                    .setReleaseYear(releaseYear)
                                    .setDurationMs(duration)
                                    .setExtras(Bundle().apply {
                                        if (artistId != null) {
                                            putLong("ArtistId", artistId)
                                        }
                                        if (albumId != null) {
                                            putLong("AlbumId", albumId)
                                        }
                                        if (genreId != null) {
                                            putLong("GenreId", genreId)
                                        }
                                        putString("Author", author)
                                        if (addDate != null) {
                                            putLong("AddDate", addDate)
                                        }
                                        if (modifiedDate != null) {
                                            putLong("ModifiedDate", modifiedDate)
                                        }
                                        cdTrackNumber?.toIntOrNull()
                                            ?.let { it1 -> putInt("CdTrackNumber", it1) }
                                    })
                                    .build(),
                            ).build()
                    },
                    shouldLoadPlaylists = true
                )
            }
            withContext(Dispatchers.Main) {
                adapter.updateList(result!!.songList)
                showPrompt()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_READ_MEDIA_AUDIO) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                fetchData()
            }
        }
    }
}