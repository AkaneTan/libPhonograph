package uk.akane.libphonograph

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicLong
import kotlin.experimental.ExperimentalTypeInference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
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

inline fun <reified T, reified U> HashMap<T, U>.putIfAbsentSupport(key: T, value: U) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        putIfAbsent(key, value)
    } else {
        // Duh...
        if (!containsKey(key))
            put(key, value)
    }
}

@OptIn(ExperimentalTypeInference::class)
fun versioningCallbackFlow(
    @BuilderInference block: suspend ProducerScope<Long>.(() -> Long) -> Unit): Flow<Long> {
    val versionTracker = AtomicLong()
    return callbackFlow<Long> { block(versionTracker::incrementAndGet) }
}

@OptIn(ExperimentalCoroutinesApi::class)
fun contentObserverVersioningFlow(context: Context, scope: CoroutineScope, uri: Uri,
    notifyForDescendants: Boolean): Flow<Long> {
    return versioningCallbackFlow { nextVersion ->
        val listener = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                scope.launch {
                    send(nextVersion())
                }
            }
        }
        context.contentResolver.registerContentObserver(uri, notifyForDescendants, listener)
        send(nextVersion())
        awaitClose {
            context.contentResolver.unregisterContentObserver(listener)
        }
    }
}

fun <T> MutableSharedFlow<T>.collectOtherFlowWhenBeingCollected(
    scope: CoroutineScope, flow: Flow<*>): MutableSharedFlow<T> {
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
inline fun <T> sharedFlow(
    scope: CoroutineScope,
    replay: Int = 0,
    extraBufferCapacity: Int = 0,
    onBufferOverflow: BufferOverflow = BufferOverflow.SUSPEND,
    producer: (subscriptionCount: StateFlow<Int>) -> Flow<T>
): SharedFlow<T> {
    val shared = MutableSharedFlow<T>(replay, extraBufferCapacity, onBufferOverflow)
    producer(shared.subscriptionCount).launchIn(scope, shared)
    return shared
}

// https://bladecoder.medium.com/smarter-shared-kotlin-flows-d6b75fc66754
fun <T> Flow<T>.launchIn(scope: CoroutineScope, collector: FlowCollector<T>): Job = scope.launch {
    collect(collector)
}

// https://bladecoder.medium.com/smarter-shared-kotlin-flows-d6b75fc66754
@OptIn(ExperimentalCoroutinesApi::class)
fun <T> Flow<T>.flowWhileShared(
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
inline fun Cursor.getColumnIndexOrNull(columnName: String): Int? =
    getColumnIndex(columnName).let { if (it == -1) null else it }

@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
@Suppress("NOTHING_TO_INLINE")
inline fun hasImprovedMediaStore(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
@Suppress("NOTHING_TO_INLINE")
inline fun hasScopedStorageV2(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
@Suppress("NOTHING_TO_INLINE")
inline fun hasScopedStorageWithMediaTypes(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

fun Context.hasAudioPermission() =
    hasScopedStorageWithMediaTypes() && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED ||
            (!hasScopedStorageV2() && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) ||
            (!hasScopedStorageWithMediaTypes() && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun Context.hasImagePermission() =
    checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) ==
            PackageManager.PERMISSION_GRANTED