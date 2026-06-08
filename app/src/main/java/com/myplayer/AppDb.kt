package com.myplayer

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Single SQLite database for both the folder-listing cache and app settings. */
object AppDb {

    private var helper: Helper? = null

    @Synchronized
    fun db(context: Context): SQLiteDatabase {
        val h = helper ?: Helper(context.applicationContext).also { helper = it }
        return h.writableDatabase
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, "app.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            createCacheTables(db)
            createSettingsTable(db)
        }

        /** Settings holds roots/toggles/theme; create-if-missing so it can never be dropped. */
        private fun createSettingsTable(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY, value TEXT)")
        }

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

        // Only the cache tables are versioned data; `settings` (roots/toggles/theme) must survive.
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS children")
            db.execSQL("DROP TABLE IF EXISTS scanned")
            createCacheTables(db)
            createSettingsTable(db)
        }

        // A downgrade (older APK sideloaded over a newer one) would otherwise throw and crash on
        // first DB access. The cache is rebuildable, so reset it the same way and keep `settings`.
        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
            onUpgrade(db, oldVersion, newVersion)
    }
}
