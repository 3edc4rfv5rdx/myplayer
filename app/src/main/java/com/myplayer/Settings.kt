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
    private const val KEY_ROOTS = "roots"
    private const val KEY_REPLAYGAIN = "replaygain"
    private const val KEY_THEME = "theme"
    private const val KEY_LOOP = "loop"

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

    /** Ordered list of root folder tree URIs. Migrates a legacy single [KEY_FOLDER] on first read. */
    fun getRoots(context: Context): List<String> {
        get(context, KEY_ROOTS)?.let { stored ->
            return stored.split('\n').filter { it.isNotEmpty() }
        }
        val legacy = get(context, KEY_FOLDER)
        return if (legacy.isNullOrEmpty()) emptyList() else listOf(legacy)
    }

    private fun setRoots(context: Context, roots: List<String>) =
        set(context, KEY_ROOTS, roots.joinToString("\n"))

    /** Appends [uri] if not already present; returns the updated list. */
    fun addRoot(context: Context, uri: String): List<String> {
        val roots = getRoots(context)
        if (uri in roots) return roots
        val updated = roots + uri
        setRoots(context, updated)
        return updated
    }

    /** Removes [uri]; returns the updated list. */
    fun removeRoot(context: Context, uri: String): List<String> {
        val updated = getRoots(context).filter { it != uri }
        setRoots(context, updated)
        return updated
    }

    fun isReplayGainEnabled(context: Context): Boolean = get(context, KEY_REPLAYGAIN) == "true"
    fun setReplayGainEnabled(context: Context, enabled: Boolean) =
        set(context, KEY_REPLAYGAIN, enabled.toString())

    fun getThemeMode(context: Context): ThemeMode = ThemeMode.from(get(context, KEY_THEME))
    fun setThemeMode(context: Context, mode: ThemeMode) = set(context, KEY_THEME, mode.name)

    /** Loop the playlist when it ends (default on); off means stop after the last track. */
    fun isLoopEnabled(context: Context): Boolean = get(context, KEY_LOOP) != "false"
    fun setLoopEnabled(context: Context, enabled: Boolean) =
        set(context, KEY_LOOP, enabled.toString())
}
