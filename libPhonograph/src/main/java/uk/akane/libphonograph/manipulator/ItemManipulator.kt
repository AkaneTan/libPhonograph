package uk.akane.libphonograph.manipulator

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import uk.akane.libphonograph.R
import uk.akane.libphonograph.TAG
import uk.akane.libphonograph.hasScopedStorageV1
import uk.akane.libphonograph.hasScopedStorageV2

object ItemManipulator {
    fun deleteSong(context: Context, id: Long):
            Pair<Boolean, () -> (() -> Pair<IntentSender?, () -> Boolean>)> {
        val uri = ContentUris.withAppendedId(
            MediaStore.Audio.Media.getContentUri("external"), id)
        val selector = "${MediaStore.Images.Media._ID} = ?"
        if (hasScopedStorageV2() && context.checkUriPermission(
                uri, Binder.getCallingPid(), Binder.getCallingUid(),
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            return Pair(false) {
                val pendingIntent = MediaStore.createDeleteRequest(
                    context.contentResolver, listOf(uri)
                )
                return@Pair {
                    Pair(pendingIntent.intentSender) { true }
                }
            }
        } else {
            return Pair(true) {
                try {
                    /**
                     * In [Build.VERSION_CODES.Q], it isn't possible to modify
                     * or delete items in MediaStore directly, and explicit permission
                     * must usually be obtained to do this.
                     *
                     * The way it works is the OS will throw a [RecoverableSecurityException],
                     * which we can catch here. Inside there's an IntentSender which the
                     * activity can use to prompt the user to grant permission to the item
                     * so it can be either updated or deleted.
                     */
                    val result = context.contentResolver.delete(uri, selector, arrayOf(id.toString())) == 1
                    return@Pair { Pair(null) { result } }
                } catch (securityException: SecurityException) {
                    return@Pair if (hasScopedStorageV1() &&
                        securityException is RecoverableSecurityException
                    ) {
                        {
                            Pair(securityException.userAction.actionIntent.intentSender) {
                                val res = deleteSong(context, id)
                                val res2 = if (res.first) {
                                    res.second()()
                                } else null
                                if (res2 != null && res2.first == null) {
                                    res2.second()
                                } else {
                                    if (res2 == null) {
                                        Log.e(TAG, "Deleting song failed because uri permission still not granted")
                                    } else {
                                        Log.e(TAG, "Deleting song failed because it threw RecoverableSecurityException")
                                    }
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.delete_failed),
                                        Toast.LENGTH_LONG
                                    ).show()
                                    false
                                }
                            }
                        }
                    } else {
                        Log.e(TAG, "Deleting song failed because: "
                                + Log.getStackTraceString(securityException));
                        {
                            Toast.makeText(
                                context,
                                context.getString(R.string.delete_failed),
                                Toast.LENGTH_LONG
                            ).show()
                            Pair(null) { false }
                        }
                    }
                }
            }
        }
    }
}