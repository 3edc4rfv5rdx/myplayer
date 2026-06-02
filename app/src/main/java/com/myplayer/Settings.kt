package com.myplayer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase

enum class ThemeMode {
    System, Light, Dark;

    companion object {
        fun from(value: String?): ThemeMode = entries.firstOrNull { it.name == value } ?: System
    }
}

/** App settings stored in the `settings` table of [AppDb]. */
object Settings {
    private const val KEY_FOLDER = "folder_uri"
    private const val KEY_REPLAYGAIN = "replaygain"
    private const val KEY_THEME = "theme"

    private fun get(context: Context, key: String): String? {
        AppDb.db(context)
            .rawQuery("SELECT value FROM settings WHERE key=?", arrayOf(key))
            .use { c -> return if (c.moveToFirst()) c.getString(0) else null }
    }

    private fun set(context: Context, key: String, value: String) {
        val cv = ContentValues()
        cv.put("key", key)
        cv.put("value", value)
        AppDb.db(context).insertWithOnConflict("settings", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getFolderUri(context: Context): String? = get(context, KEY_FOLDER)
    fun setFolderUri(context: Context, uri: String) = set(context, KEY_FOLDER, uri)

    fun isReplayGainEnabled(context: Context): Boolean = get(context, KEY_REPLAYGAIN) == "true"
    fun setReplayGainEnabled(context: Context, enabled: Boolean) =
        set(context, KEY_REPLAYGAIN, enabled.toString())

    fun getThemeMode(context: Context): ThemeMode = ThemeMode.from(get(context, KEY_THEME))
    fun setThemeMode(context: Context, mode: ThemeMode) = set(context, KEY_THEME, mode.name)
}
