package com.myplayer

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Persistent, user-visible debug log for tracking rare/floating issues across days. Appends lines to
 *  Documents/MyPlayer/myplayer-log.txt via MediaStore (the only way to write shared storage under
 *  scoped storage), so the file survives restarts and the user can open it in any file manager.
 *  Best-effort: any failure is swallowed — logging must never affect app behaviour. */
object FileLog {
    private const val DIR = "Documents/MyPlayer"
    private const val FILE = "myplayer-log.txt"
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    // Serializes append so concurrent writers don't interleave partial lines.
    private val lock = Any()

    fun log(context: Context, msg: String) {
        val line = "${stamp.format(Date())} $msg\n"
        synchronized(lock) {
            try {
                val uri = ensureFile(context) ?: return
                context.contentResolver.openOutputStream(uri, "wa")?.use {
                    it.write(line.toByteArray())
                }
            } catch (_: Exception) {
                // Best-effort: never let a logging failure surface.
            }
        }
    }

    /** The existing log file's uri, or a freshly created one. */
    private fun ensureFile(context: Context): Uri? {
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf("$DIR/", FILE),
            null
        )?.use { c ->
            if (c.moveToFirst()) return ContentUris.withAppendedId(collection, c.getLong(0))
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, FILE)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, DIR)
        }
        return resolver.insert(collection, values) ?: throw IOException("MediaStore insert failed")
    }
}
