package com.myplayer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/** Persistent per-file duration cache in the `durations` table of [AppDb], filled lazily with
 *  MediaMetadataRetriever the first time a book queue needs it (time-based book progress). Keyed
 *  by the file's document uri. A file whose duration can't be read is stored as 0, so a broken
 *  file never blocks the whole book's data from completing; Rescan drops the table along with the
 *  listing cache (see [FolderCache.clear]). */
object DurationCache {

    /** Durations (ms) for [uris] in the same order, resolving and caching the missing ones.
     *  Suspend: resolving cold files over SAF is slow, and the per-file cancellation check lets a
     *  superseded queue abandon its walk early. */
    suspend fun durations(context: Context, uris: List<String>): LongArray {
        val db = AppDb.db(context)
        val cached = HashMap<String, Long>(uris.size)
        // SQLite caps bound parameters; read the cache in chunks.
        for (chunk in uris.chunked(500)) {
            val placeholders = chunk.joinToString(",") { "?" }
            db.rawQuery(
                "SELECT uri, ms FROM durations WHERE uri IN ($placeholders)",
                chunk.toTypedArray()
            ).use { c -> while (c.moveToNext()) cached[c.getString(0)] = c.getLong(1) }
        }
        return LongArray(uris.size) { i ->
            cached[uris[i]] ?: run {
                coroutineContext.ensureActive()
                resolve(context, uris[i]).also { store(db, uris[i], it) }
            }
        }
    }

    private fun resolve(context: Context, uri: String): Long =
        runCatching {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(context, Uri.parse(uri))
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
            }
        }.getOrDefault(0L)

    private fun store(db: SQLiteDatabase, uri: String, ms: Long) {
        val cv = ContentValues()
        cv.put("uri", uri)
        cv.put("ms", ms)
        db.insertWithOnConflict("durations", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }
}
