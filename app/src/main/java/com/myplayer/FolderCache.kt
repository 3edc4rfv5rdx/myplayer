package com.myplayer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri

/** Persistent folder-listing cache in [AppDb]: one row per child, indexed by parent.
 *  Insertion order is preserved (MusicScanner returns sorted lists), read back via rowid. */
object FolderCache {

    /** Cached children of [parent] within [treeUri], scanning and persisting on a miss. */
    @Synchronized
    fun children(context: Context, treeUri: Uri, parent: Node): Pair<List<Node>, List<Node>> {
        val db = AppDb.db(context)
        val root = treeUri.toString()
        if (isScanned(db, root, parent.documentId)) {
            return read(db, root, parent.documentId)
        }
        val result = MusicScanner.children(context, treeUri, parent)
        store(db, root, parent.documentId, result)
        return result
    }

    /** Drops the cached listings (used by Rescan and when the root folder changes). */
    @Synchronized
    fun clear(context: Context) {
        val db = AppDb.db(context)
        db.delete("children", null, null)
        db.delete("scanned", null, null)
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
