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

    // In-memory mirror for [peek]/[preload]: uri -> stored ms (0 = unreadable) or [UNKNOWN_MS] for a
    // confirmed-absent row. Exists so building a gapped queue (one peek per item, on the main
    // thread inside setMediaItems) doesn't run one SQLite query per track. Guarded by itself.
    private val mem = HashMap<String, Long>()

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

    /** Pre-fills the in-memory mirror for [uris] with one batched read (absent rows are remembered
     *  too), so the [peek] burst that follows a gapped setMediaItems is pure memory lookups. Called
     *  off the main thread before a queue is installed; a no-op for already-known uris. */
    fun preload(context: Context, uris: List<String>) {
        val missing = synchronized(mem) { uris.filter { it !in mem } }
        if (missing.isEmpty()) return
        val db = AppDb.db(context)
        val found = HashMap<String, Long>(missing.size)
        // SQLite caps bound parameters; read the cache in chunks.
        for (chunk in missing.chunked(500)) {
            val placeholders = chunk.joinToString(",") { "?" }
            db.rawQuery(
                "SELECT uri, ms FROM durations WHERE uri IN ($placeholders)",
                chunk.toTypedArray()
            ).use { c -> while (c.moveToNext()) found[c.getString(0)] = c.getLong(1) }
        }
        synchronized(mem) { for (uri in missing) mem[uri] = found[uri] ?: UNKNOWN_MS }
    }

    /** Synchronous single-file cache lookup (no MediaMetadataRetriever fill): the cached duration in
     *  ms, or null when not cached yet or stored as unreadable (0). Served from the in-memory mirror
     *  (see [preload]) so the per-item burst at queue install stays off the DB; a miss falls back to
     *  one indexed query and is remembered. Used to give ConcatenatingMediaSource2 an accurate
     *  placeholder duration. */
    fun peek(context: Context, uri: String): Long? {
        synchronized(mem) { mem[uri] }?.let { return it.takeIf { ms -> ms > 0L } }
        val ms = AppDb.db(context)
            .rawQuery("SELECT ms FROM durations WHERE uri = ? LIMIT 1", arrayOf(uri))
            .use { c -> if (c.moveToNext()) c.getLong(0) else null }
        synchronized(mem) { mem[uri] = ms ?: UNKNOWN_MS }
        return ms?.takeIf { it > 0L }
    }

    /** Drops the cached durations of [uris] — the shared cleanup for the cases where the rows
     *  would only sit as dirt: the book finished (PlayerService) or its files were deleted from
     *  storage (FolderCache.invalidateSubtree). A re-listen just re-resolves lazily. */
    fun remove(context: Context, uris: List<String>) {
        synchronized(mem) { for (uri in uris) mem.remove(uri) }
        val db = AppDb.db(context)
        // SQLite caps bound parameters; delete in chunks.
        for (chunk in uris.chunked(500)) {
            val placeholders = chunk.joinToString(",") { "?" }
            db.delete("durations", "uri IN ($placeholders)", chunk.toTypedArray())
        }
    }

    /** Drops the whole in-memory mirror; called by [FolderCache] whenever it bulk-deletes duration
     *  rows (Rescan, root removal), so the mirror can't serve rows the table no longer has. */
    fun clearMem() = synchronized(mem) { mem.clear() }

    private fun resolve(context: Context, uri: String): Long =
        runCatching {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(context, Uri.parse(uri))
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
            }
        }.getOrDefault(0L)

    private fun store(db: SQLiteDatabase, uri: String, ms: Long) {
        synchronized(mem) { mem[uri] = ms }
        val cv = ContentValues()
        cv.put("uri", uri)
        cv.put("ms", ms)
        db.insertWithOnConflict("durations", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }
}
