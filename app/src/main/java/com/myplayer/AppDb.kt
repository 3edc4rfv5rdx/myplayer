package com.myplayer

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Database for the data worth keeping across reinstalls: app settings (SAF roots, toggles, theme,
 *  per-folder audiobook flags/positions/speeds) and the per-file duration cache. This file is
 *  included in Android Auto Backup. The rebuildable folder-listing cache lives in a separate
 *  [CacheDb] file so it can be excluded from backup. */
object AppDb {

    private var helper: Helper? = null

    @Synchronized
    fun db(context: Context): SQLiteDatabase {
        val h = helper ?: Helper(context.applicationContext).also { helper = it }
        return h.writableDatabase
    }

    /** [prefix] as a SQL `LIKE ? ESCAPE '\'` prefix pattern: wildcard and escape characters in the
     *  literal text escaped (content uris contain `%`), trailing `%` appended. */
    fun likePrefix(prefix: String): String =
        prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

    private class Helper(context: Context) : SQLiteOpenHelper(context, "app.db", null, 4) {
        override fun onCreate(db: SQLiteDatabase) {
            createDurationsTable(db)
            createSettingsTable(db)
        }

        /** Settings holds roots/toggles/theme; create-if-missing so it can never be dropped. */
        private fun createSettingsTable(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY, value TEXT)")
        }

        /** Per-file durations (see [DurationCache]); create-if-missing — expensive to refill, so
         *  it is kept across versions and backed up. */
        private fun createDurationsTable(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS durations(uri TEXT PRIMARY KEY, ms INTEGER)")
        }

        // `settings` and `durations` survive every version change. The listing-cache tables used to
        // live here too (v<=3); they now have their own CacheDb file, so drop the old copies when
        // migrating from the single-DB layout.
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS children")
            db.execSQL("DROP TABLE IF EXISTS scanned")
            createDurationsTable(db)
            createSettingsTable(db)
        }

        // A downgrade (older APK sideloaded over a newer one) would otherwise throw and crash on
        // first DB access; keep `settings`/`durations` and reconcile the schema the same way.
        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
            onUpgrade(db, oldVersion, newVersion)
    }
}
