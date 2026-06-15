package com.myplayer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.DocumentsContract
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock

/** Persistent folder-listing cache in [AppDb]: one row per child, indexed by parent.
 *  Insertion order is preserved (MusicScanner returns sorted lists), read back via rowid. */
object FolderCache {

    // A recursive collect hits many folders; lock per (tree, parent) so independent folders scan
    // in parallel and a big "Play this folder" can't block UI loads of other (cached) folders.
    // The read/write lock keeps clear()/clearRoot() exclusive against all per-key scans.
    private val rw = ReentrantReadWriteLock()
    private val keyLocks = ConcurrentHashMap<String, Any>()

    private fun keyLock(root: String, parentId: String): Any =
        keyLocks.getOrPut("$root|$parentId") { Any() }

    /** Cached children of [parent] within [treeUri], scanning and persisting on a miss. */
    fun children(context: Context, treeUri: Uri, parent: Node): Pair<List<Node>, List<Node>> {
        val root = treeUri.toString()
        rw.readLock().lock()
        try {
            synchronized(keyLock(root, parent.documentId)) {
                val db = CacheDb.db(context)
                if (isScanned(db, root, parent.documentId)) {
                    return read(db, root, parent.documentId)
                }
                val result = MusicScanner.children(context, treeUri, parent)
                store(db, root, parent.documentId, result)
                return result
            }
        } finally {
            rw.readLock().unlock()
        }
    }

    /** Drops all cached listings (used by Rescan). The duration cache goes with them: an explicit
     *  rescan means files may have changed, and durations are re-resolved lazily anyway. */
    fun clear(context: Context) {
        rw.writeLock().lock()
        try {
            CacheDb.db(context).run {
                delete("children", null, null)
                delete("scanned", null, null)
            }
            AppDb.db(context).delete("durations", null, null)
        } finally {
            rw.writeLock().unlock()
        }
    }

    /** Drops only [treeUri]'s cached listings and file durations (used when a single root is
     *  removed — its content uris are unreachable once the permission is released). */
    fun clearRoot(context: Context, treeUri: Uri) {
        rw.writeLock().lock()
        try {
            val root = arrayOf(treeUri.toString())
            CacheDb.db(context).run {
                delete("children", "tree_uri=?", root)
                delete("scanned", "tree_uri=?", root)
            }
            // Duration rows are keyed by document uri, which embeds the tree uri as its prefix.
            AppDb.db(context).delete(
                "durations", "uri LIKE ? ESCAPE '\\'",
                arrayOf(AppDb.likePrefix(treeUri.toString() + "/document/"))
            )
        } finally {
            rw.writeLock().unlock()
        }
    }

    /** Drops the cached listing of a single folder so it is re-scanned next time (used after a child
     *  folder is deleted, to refresh the parent's listing). */
    fun invalidate(context: Context, treeUri: Uri, parentId: String) {
        rw.writeLock().lock()
        try {
            val db = CacheDb.db(context)
            val args = arrayOf(treeUri.toString(), parentId)
            db.delete("children", "tree_uri=? AND parent_id=?", args)
            db.delete("scanned", "tree_uri=? AND parent_id=?", args)
        } finally {
            rw.writeLock().unlock()
        }
    }

    /** Drops the cached listings of [folderId] and every folder beneath it, walking the cached tree
     *  to find descendants (used after a folder is deleted from storage, so no stale rows linger).
     *  The subtree files' cached durations go with them — the files no longer exist. Returns the
     *  subtree's file uris (as far as cached) so callers can clear other state keyed by them. */
    fun invalidateSubtree(context: Context, treeUri: Uri, folderId: String): List<String> {
        rw.writeLock().lock()
        try {
            val db = CacheDb.db(context)
            val root = treeUri.toString()
            val fileUris = ArrayList<String>()
            val pending = ArrayDeque<String>()
            pending.addLast(folderId)
            while (pending.isNotEmpty()) {
                val id = pending.removeFirst()
                // Queue cached subfolders (and note the files) before dropping this folder's listing.
                db.rawQuery(
                    "SELECT doc_id, is_dir FROM children WHERE tree_uri=? AND parent_id=?",
                    arrayOf(root, id)
                ).use { c ->
                    while (c.moveToNext()) {
                        if (c.getInt(1) == 1) pending.addLast(c.getString(0))
                        else fileUris.add(
                            DocumentsContract
                                .buildDocumentUriUsingTree(treeUri, c.getString(0)).toString()
                        )
                    }
                }
                val args = arrayOf(root, id)
                db.delete("children", "tree_uri=? AND parent_id=?", args)
                db.delete("scanned", "tree_uri=? AND parent_id=?", args)
            }
            DurationCache.remove(context, fileUris)
            return fileUris
        } finally {
            rw.writeLock().unlock()
        }
    }

    private fun isScanned(db: SQLiteDatabase, root: String, parentId: String): Boolean {
        db.rawQuery(
            "SELECT 1 FROM scanned WHERE tree_uri=? AND parent_id=? LIMIT 1",
            arrayOf(root, parentId)
        ).use {
            return it.moveToFirst()
        }
    }

    private fun read(db: SQLiteDatabase, root: String, parentId: String): Pair<List<Node>, List<Node>> {
        val folders = ArrayList<Node>()
        val files = ArrayList<Node>()
        db.rawQuery(
            "SELECT doc_id, name, is_dir FROM children WHERE tree_uri=? AND parent_id=? ORDER BY rowid",
            arrayOf(root, parentId)
        ).use { c ->
            while (c.moveToNext()) {
                val node = Node(c.getString(0), c.getString(1), c.getInt(2) == 1)
                if (node.isDir) folders.add(node) else files.add(node)
            }
        }
        return folders to files
    }

    private fun store(
        db: SQLiteDatabase, root: String, parentId: String, data: Pair<List<Node>, List<Node>>
    ) {
        db.beginTransaction()
        try {
            val cv = ContentValues()
            for (node in data.first + data.second) {
                cv.clear()
                cv.put("tree_uri", root)
                cv.put("parent_id", parentId)
                cv.put("doc_id", node.documentId)
                cv.put("name", node.name)
                cv.put("is_dir", if (node.isDir) 1 else 0)
                db.insert("children", null, cv)
            }
            cv.clear()
            cv.put("tree_uri", root)
            cv.put("parent_id", parentId)
            db.insertWithOnConflict("scanned", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
