package com.myplayer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.util.concurrent.Executors

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
    private const val KEY_FOLLOW = "follow"

    // In-memory cache so reads (after warm-up) and read-modify-write on roots stay off disk and
    // race-free; the single-thread writer persists changes in order, in the background.
    private val lock = Any()
    private val cache = HashMap<String, String?>()
    private val loaded = HashSet<String>()
    private val writer = Executors.newSingleThreadExecutor()

    private fun get(context: Context, key: String): String? {
        synchronized(lock) { if (key in loaded) return cache[key] }
        val value = readDb(context, key)
        synchronized(lock) {
            // First read wins; a concurrent set() may already have cached a newer value.
            if (key !in loaded) {
                cache[key] = value
                loaded.add(key)
            }
            return cache[key]
        }
    }

    private fun set(context: Context, key: String, value: String) {
        synchronized(lock) {
            cache[key] = value
            loaded.add(key)
        }
        val app = context.applicationContext
        writer.execute { writeDb(app, key, value) }
    }

    private fun readDb(context: Context, key: String): String? {
        AppDb.db(context)
            .rawQuery("SELECT value FROM settings WHERE key=?", arrayOf(key))
            .use { c -> return if (c.moveToFirst()) c.getString(0) else null }
    }

    private fun writeDb(context: Context, key: String, value: String) {
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
        if (legacy.isNullOrEmpty()) return emptyList()
        // Migrate the legacy single folder to KEY_ROOTS once, so later reads don't redo it.
        val migrated = listOf(legacy)
        setRoots(context, migrated)
        return migrated
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

    /** Follow playback: jump the browser to the playing track's folder and center it (default on). */
    fun isFollowEnabled(context: Context): Boolean = get(context, KEY_FOLLOW) != "false"
    fun setFollowEnabled(context: Context, enabled: Boolean) =
        set(context, KEY_FOLLOW, enabled.toString())
}
