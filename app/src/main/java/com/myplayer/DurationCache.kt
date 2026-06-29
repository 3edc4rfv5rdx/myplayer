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
 *  file never blocks the whole book's data from completing. Cleanup: Rescan drops the whole table
 *  with the listing cache ([FolderCache.clear]); a finished book's rows and a deleted folder's
 *  rows are removed via [remove]. */
object DurationCache {

    /** Marks a duration not resolved yet in the snapshots passed to [durations]' onUpdate. A file
     *  whose duration genuinely can't be read is 0, never UNKNOWN_MS. */
    const val UNKNOWN_MS = -1L

    // Cold files resolve one MediaMetadataRetriever each over SAF (slow); snapshot per small batch
    // so a large cold book's progress readout refines every few files, not after the whole walk.
    private const val RESOLVE_BATCH = 10

    /** Durations (ms) for [uris] in the same order, resolving and caching the missing ones.
     *  Incremental: [onUpdate] gets a snapshot with everything already cached right away (missing
     *  entries are [UNKNOWN_MS]), then a fresh one after each resolved batch. Suspend: resolving
     *  cold files over SAF is slow, and the per-file cancellation check lets a superseded queue
     *  abandon its walk early. */
    suspend fun durations(context: Context, uris: List<String>, onUpdate: (LongArray) -> Unit) {
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
        val result = LongArray(uris.size) { i -> cached[uris[i]] ?: UNKNOWN_MS }
        onUpdate(result.copyOf())
        val missing = uris.indices.filter { result[it] == UNKNOWN_MS }
        for (batch in missing.chunked(RESOLVE_BATCH)) {
            for (i in batch) {
                coroutineContext.ensureActive()
                result[i] = resolve(context, uris[i]).also { store(db, uris[i], it) }
            }
            onUpdate(result.copyOf())
        }
    }

    /** Synchronous single-file cache lookup (no MediaMetadataRetriever fill): the cached duration in
     *  ms, or null when not cached yet or stored as unreadable (0). Cheap enough to call on the
     *  playback thread; used to give ConcatenatingMediaSource2 an accurate placeholder duration. */
    fun peek(context: Context, uri: String): Long? =
        AppDb.db(context).rawQuery("SELECT ms FROM durations WHERE uri = ? LIMIT 1", arrayOf(uri))
            .use { c -> if (c.moveToNext()) c.getLong(0).takeIf { it > 0L } else null }

    /** Drops the cached durations of [uris] — the shared cleanup for the cases where the rows
     *  would only sit as dirt: the book finished (PlayerService) or its files were deleted from
     *  storage (FolderCache.invalidateSubtree). A re-listen just re-resolves lazily. */
    fun remove(context: Context, uris: List<String>) {
        val db = AppDb.db(context)
        // SQLite caps bound parameters; delete in chunks.
        for (chunk in uris.chunked(500)) {
            val placeholders = chunk.joinToString(",") { "?" }
            db.delete("durations", "uri IN ($placeholders)", chunk.toTypedArray())
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
