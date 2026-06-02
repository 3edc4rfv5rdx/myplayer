package com.myplayer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri

private class CacheDb(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "cache.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE children(parent_id TEXT, doc_id TEXT, name TEXT, is_dir INTEGER)"
        )
        db.execSQL("CREATE INDEX idx_parent ON children(parent_id)")
        db.execSQL("CREATE TABLE scanned(parent_id TEXT PRIMARY KEY)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS children")
        db.execSQL("DROP TABLE IF EXISTS scanned")
        onCreate(db)
    }
}

/** Persistent folder-listing cache backed by SQLite: one row per child, indexed by parent.
 *  Insertion order is preserved (MusicScanner returns sorted lists), read back via rowid. */
object FolderCache {

    private var helper: CacheDb? = null

    @Synchronized
    private fun db(context: Context): SQLiteDatabase {
        val h = helper ?: CacheDb(context).also { helper = it }
        return h.writableDatabase
    }

    /** Cached children of [parent], scanning and persisting on a miss. */
    @Synchronized
    fun children(context: Context, treeUri: Uri, parent: Node): Pair<List<Node>, List<Node>> {
        val db = db(context)
        if (isScanned(db, parent.documentId)) {
            return read(db, parent.documentId)
        }
        val result = MusicScanner.children(context, treeUri, parent)
        store(db, parent.documentId, result)
        return result
    }

    /** Drops the whole cache (used by Rescan and when the root folder changes). */
    @Synchronized
    fun clear(context: Context) {
        val db = db(context)
        db.delete("children", null, null)
        db.delete("scanned", null, null)
    }

    private fun isScanned(db: SQLiteDatabase, parentId: String): Boolean {
        db.rawQuery("SELECT 1 FROM scanned WHERE parent_id=? LIMIT 1", arrayOf(parentId)).use {
            return it.moveToFirst()
        }
    }

    private fun read(db: SQLiteDatabase, parentId: String): Pair<List<Node>, List<Node>> {
        val folders = ArrayList<Node>()
        val files = ArrayList<Node>()
        db.rawQuery(
            "SELECT doc_id, name, is_dir FROM children WHERE parent_id=? ORDER BY rowid",
            arrayOf(parentId)
        ).use { c ->
            while (c.moveToNext()) {
                val node = Node(c.getString(0), c.getString(1), c.getInt(2) == 1)
                if (node.isDir) folders.add(node) else files.add(node)
            }
        }
        return folders to files
    }

    private fun store(db: SQLiteDatabase, parentId: String, data: Pair<List<Node>, List<Node>>) {
        db.beginTransaction()
        try {
            val cv = ContentValues()
            for (node in data.first + data.second) {
                cv.clear()
                cv.put("parent_id", parentId)
                cv.put("doc_id", node.documentId)
                cv.put("name", node.name)
                cv.put("is_dir", if (node.isDir) 1 else 0)
                db.insert("children", null, cv)
            }
            cv.clear()
            cv.put("parent_id", parentId)
            db.insertWithOnConflict("scanned", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
