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

    private class Helper(context: Context) : SQLiteOpenHelper(context, "app.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE children(parent_id TEXT, doc_id TEXT, name TEXT, is_dir INTEGER)"
            )
            db.execSQL("CREATE INDEX idx_parent ON children(parent_id)")
            db.execSQL("CREATE TABLE scanned(parent_id TEXT PRIMARY KEY)")
            db.execSQL("CREATE TABLE settings(key TEXT PRIMARY KEY, value TEXT)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS children")
            db.execSQL("DROP TABLE IF EXISTS scanned")
            db.execSQL("DROP TABLE IF EXISTS settings")
            onCreate(db)
        }
    }
}
