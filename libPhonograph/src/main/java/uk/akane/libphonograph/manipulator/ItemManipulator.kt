package uk.akane.libphonograph.manipulator

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Binder
import android.provider.MediaStore
import uk.akane.libphonograph.hasScopedStorageV2

object ItemManipulator {
    // requires requestLegacyExternalStorage for simplicity
    // TODO test on Q :)
    fun deleteSong(context: Context, id: Long): DeleteRequest {
        val uri = ContentUris.withAppendedId(
            MediaStore.Audio.Media.getContentUri("external"), id)
        val selector = "${MediaStore.Images.Media._ID} = ?"
        if (hasScopedStorageV2() && context.checkCallingOrSelfUriPermission(
                uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            val pendingIntent = MediaStore.createDeleteRequest(
                context.contentResolver, listOf(uri)
            )
            return DeleteRequest(pendingIntent.intentSender)
        } else {
            return DeleteRequest {
                return@DeleteRequest try {
                    context.contentResolver.delete(uri, selector, arrayOf(id.toString())) == 1
                } catch (_: SecurityException) {
                    false
                }
            }
        }
    }

    class DeleteRequest {
        val startSystemDialog: IntentSender?
        val continueDelete: (() -> Boolean)?

        constructor(startSystemDialog: IntentSender) {
            this.startSystemDialog = startSystemDialog
            this.continueDelete = null
        }

        constructor(continueDelete: (() -> Boolean)) {
            this.startSystemDialog = null
            this.continueDelete = continueDelete
        }
    }
}