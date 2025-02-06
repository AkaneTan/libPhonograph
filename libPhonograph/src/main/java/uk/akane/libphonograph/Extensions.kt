package uk.akane.libphonograph

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicLong
import kotlin.experimental.ExperimentalTypeInference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingCommand
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal inline fun <reified T, reified U> HashMap<T, U>.putIfAbsentSupport(key: T, value: U) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        putIfAbsent(key, value)
    } else {
        // Duh...
        if (!containsKey(key))
            put(key, value)
    }
}

abstract class ContentObserverCompat(handler: Handler?) : ContentObserver(handler) {
    final override fun onChange(selfChange: Boolean) {
        onChange(selfChange, null)
    }

    final override fun onChange(selfChange: Boolean, uri: Uri?) {
        onChange(selfChange, uri, 0)
    }

    final override fun onChange(selfChange: Boolean, uri: Uri?, flags: Int) {
        if (uri == null)
            onChange(selfChange, emptyList(), flags)
        else
            onChange(selfChange, listOf(uri), flags)
    }

    abstract override fun onChange(selfChange: Boolean, uris: Collection<Uri?>, flags: Int)
    abstract override fun deliverSelfNotifications(): Boolean
}

@OptIn(ExperimentalTypeInference::class)
internal fun versioningCallbackFlow(
    @BuilderInference block: suspend ProducerScope<Long>.(() -> Long) -> Unit
): Flow<Long> {
    val versionTracker = AtomicLong()
    return callbackFlow<Long> { block(versionTracker::incrementAndGet) }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun contentObserverVersioningFlow(
    context: Context, scope: CoroutineScope, uri: Uri,
    notifyForDescendants: Boolean
): Flow<Long> {
    return versioningCallbackFlow { nextVersion ->
        val listener = object : ContentObserverCompat(null) {
            override fun onChange(selfChange: Boolean, uris: Collection<Uri?>, flags: Int) {
                // TODO can we use those uris and flags for incremental reload at least on newer
                //  platform versions?
                scope.launch {
                    send(nextVersion())
                }
            }

            override fun deliverSelfNotifications(): Boolean {
                return true
            }
        }
        // TODO is content observer reliable if process gets cached? or are we forced to re-register
        //  and reload everything when regaining active state since Android 13?
        context.contentResolver.registerContentObserver(uri, notifyForDescendants, listener)
        send(nextVersion())
        awaitClose {
            context.contentResolver.unregisterContentObserver(listener)
        }
    }
}

internal fun <T> MutableSharedFlow<T>.collectOtherFlowWhenBeingCollected(
    scope: CoroutineScope, flow: Flow<*>
): MutableSharedFlow<T> {
    scope.launch {
        subscriptionCount
            .map { count -> count > 0 }
            .distinctUntilChanged()
            .collect { hasCollectors ->
                if (hasCollectors) {
                    flow.collect()
                }
            }
    }
    return this
}

// https://bladecoder.medium.com/smarter-shared-kotlin-flows-d6b75fc66754
internal inline fun <T> sharedFlow(
    scope: CoroutineScope,
    replay: Int = 0,
    extraBufferCapacity: Int = 0,
    onBufferOverflow: BufferOverflow = BufferOverflow.SUSPEND,
    producer: (subscriptionCount: StateFlow<Int>) -> Flow<T>
): SharedFlow<T> {
    val shared = MutableSharedFlow<T>(replay, extraBufferCapacity, onBufferOverflow)
    val f = producer(shared.subscriptionCount)
    scope.launch { f.collect(shared) }
    return shared
}

// https://bladecoder.medium.com/smarter-shared-kotlin-flows-d6b75fc66754
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T> Flow<T>.flowWhileShared(
    subscriptionCount: StateFlow<Int>,
    started: SharingStarted
): Flow<T> {
    return started.command(subscriptionCount)
        .distinctUntilChanged()
        .flatMapLatest {
            when (it) {
                SharingCommand.START -> this
                SharingCommand.STOP,
                SharingCommand.STOP_AND_RESET_REPLAY_CACHE -> emptyFlow()
            }
        }
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun Cursor.getColumnIndexOrNull(columnName: String): Int? =
    getColumnIndex(columnName).let { if (it == -1) null else it }

@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
@Suppress("NOTHING_TO_INLINE")
internal inline fun hasImprovedMediaStore(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
@Suppress("NOTHING_TO_INLINE")
internal inline fun hasScopedStorageV2(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
@Suppress("NOTHING_TO_INLINE")
internal inline fun hasScopedStorageWithMediaTypes(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

internal fun Context.hasAudioPermission() =
    hasScopedStorageWithMediaTypes() && ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.READ_MEDIA_AUDIO
    ) == PackageManager.PERMISSION_GRANTED ||
            (!hasScopedStorageV2() && ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED) ||
            (!hasScopedStorageWithMediaTypes() && ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED)

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun Context.hasImagePermission() =
    checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) ==
            PackageManager.PERMISSION_GRANTED

@Suppress("NOTHING_TO_INLINE")
internal inline fun Cursor.getStringOrNullIfThrow(index: Int): String? =
    try {
        getString(index)
    } catch (_: Exception) {
        null
    }

@Suppress("NOTHING_TO_INLINE")
internal inline fun Cursor.getLongOrNullIfThrow(index: Int): Long? =
    try {
        getLong(index)
    } catch (_: Exception) {
        null
    }

@Suppress("NOTHING_TO_INLINE")
internal inline fun Cursor.getIntOrNullIfThrow(index: Int): Int? =
    try {
        getInt(index)
    } catch (_: Exception) {
        null
    }