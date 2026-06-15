package com.myplayer

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Rebuildable folder-listing cache (children/scanned) in its own `cache.db` file, kept separate
 *  from [AppDb] so it can be excluded from Android Auto Backup: the listings reference SAF document
 *  ids whose URI permissions don't survive a restore, and they re-scan on demand anyway.
 *  See [FolderCache]. */
object CacheDb {

    private var helper: Helper? = null

    @Synchronized
    fun db(context: Context): SQLiteDatabase {
        val h = helper ?: Helper(context.applicationContext).also { helper = it }
        return h.writableDatabase
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, "cache.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) = createCacheTables(db)

        /** The cache key is (tree_uri, parent_id): documentId alone can collide across roots/providers. */
        private fun createCacheTables(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE children(tree_uri TEXT, parent_id TEXT, doc_id TEXT, name TEXT, is_dir INTEGER)"
            )
            db.execSQL("CREATE INDEX idx_parent ON children(tree_uri, parent_id)")
            db.execSQL(
                "CREATE TABLE scanned(tree_uri TEXT, parent_id TEXT, PRIMARY KEY(tree_uri, parent_id))"
            )
        }

        // The whole cache is rebuildable, so any version change (or a downgrade) just resets it.
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS children")
            db.execSQL("DROP TABLE IF EXISTS scanned")
            createCacheTables(db)
        }

        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
            onUpgrade(db, oldVersion, newVersion)
    }
}
