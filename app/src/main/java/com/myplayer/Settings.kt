package com.myplayer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    private const val KEY_FOLLOW = "follow"
    private const val KEY_REMAINING = "remaining"
    private const val KEY_DEFAULT_SPEED = "default_speed"
    // Per-folder book state lives under these prefixes, keyed by a stable (treeUri, docId) folder key.
    private const val KEY_MODE_PREFIX = "mode:"
    private const val KEY_POS_PREFIX = "pos:"
    private const val KEY_SPEED_PREFIX = "speed:"

    // Global repeat-all: deliberately hardwired off — playback never loops, a finished queue ends.
    // Books must stay off regardless (resume tracking relies on reaching STATE_ENDED); this flag
    // only exists so the music behavior could be flipped back in one place.
    const val REPEAT_ALL = false

    // Resume rewind: how far a resumed book steps back for context, and the minimum break length
    // before the rewind applies — a book reopened moments later resumes exactly, so quick
    // out-and-back cycles don't eat another 15s each time.
    const val RESUME_REWIND_MS = 15_000L
    const val RESUME_REWIND_MIN_PAUSE_MS = 2 * 60_000L

    // Playback-speed bounds and granularity, shared by the player and settings UI.
    const val SPEED_MIN = 0.5f
    const val SPEED_MAX = 3.0f
    const val SPEED_STEP = 0.05f
    const val SPEED_DEFAULT = 1.0f

    // In-memory cache so reads (after warm-up) and read-modify-write on roots stay off disk and
    // race-free; the single-thread writer persists changes in order, in the background.
    private val lock = Any()
    private val cache = HashMap<String, String?>()
    private val loaded = HashSet<String>()
    private val writer = Executors.newSingleThreadExecutor()

    // Serializes the read-modify-write of the roots list so concurrent add/remove can't lose an
    // update (the cache `lock` only makes each get/set atomic, not the compound operation). Held
    // around get/set only, both of which work off the in-memory cache, so no disk I/O runs under it.
    private val rootLock = Any()

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

    /** Deletes [key] entirely — clearing means "never set", not an empty-string row left behind.
     *  Reads of legacy empty rows written by older versions still fall back the same way. */
    private fun remove(context: Context, key: String) {
        synchronized(lock) {
            cache[key] = null
            loaded.add(key)
        }
        val app = context.applicationContext
        writer.execute { deleteDb(app, key) }
    }

    /** Blocks until every queued write has hit disk. Called before teardown so a just-saved value
     *  (e.g. a book position on exit) isn't lost if the process dies right after. The single-thread
     *  writer runs in FIFO order, so an empty task completing means all prior writes are done. */
    fun flush() {
        val done = CountDownLatch(1)
        writer.execute { done.countDown() }
        // Bounded so teardown can never hang on a stuck writer; one-row writes finish in ms.
        done.await(2, TimeUnit.SECONDS)
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

    private fun deleteDb(context: Context, key: String) {
        AppDb.db(context).delete("settings", "key=?", arrayOf(key))
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
    fun addRoot(context: Context, uri: String): List<String> = synchronized(rootLock) {
        val roots = getRoots(context)
        if (uri in roots) return roots
        val updated = roots + uri
        setRoots(context, updated)
        return updated
    }

    /** Removes [uri]; returns the updated list. */
    fun removeRoot(context: Context, uri: String): List<String> = synchronized(rootLock) {
        val updated = getRoots(context).filter { it != uri }
        setRoots(context, updated)
        return updated
    }

    fun isReplayGainEnabled(context: Context): Boolean = get(context, KEY_REPLAYGAIN) == "true"
    fun setReplayGainEnabled(context: Context, enabled: Boolean) =
        set(context, KEY_REPLAYGAIN, enabled.toString())

    fun getThemeMode(context: Context): ThemeMode = ThemeMode.from(get(context, KEY_THEME))
    fun setThemeMode(context: Context, mode: ThemeMode) = set(context, KEY_THEME, mode.name)

    /** Follow playback: jump the browser to the playing track's folder and center it (default on). */
    fun isFollowEnabled(context: Context): Boolean = get(context, KEY_FOLLOW) != "false"
    fun setFollowEnabled(context: Context, enabled: Boolean) =
        set(context, KEY_FOLLOW, enabled.toString())

    /** Rightmost time readout: remaining (-mm:ss) when on, total when off (default off). */
    fun isRemainingTime(context: Context): Boolean = get(context, KEY_REMAINING) == "true"
    fun setRemainingTime(context: Context, enabled: Boolean) =
        set(context, KEY_REMAINING, enabled.toString())

    /** Default playback speed applied to folders without a saved speed of their own. */
    fun getDefaultSpeed(context: Context): Float =
        get(context, KEY_DEFAULT_SPEED)?.toFloatOrNull() ?: SPEED_DEFAULT
    fun setDefaultSpeed(context: Context, speed: Float) =
        set(context, KEY_DEFAULT_SPEED, speed.toString())

    // ---- Audiobook mode (per folder) -------------------------------------------------------------

    /** Builds the stable per-folder key. A documentId alone can collide across trees, so include both. */
    fun bookKey(treeUri: String, docId: String): String = "$treeUri|$docId"

    /** Audiobook mode for the folder: play sequentially and remember position (default off). */
    fun isAbook(context: Context, folderKey: String): Boolean =
        get(context, KEY_MODE_PREFIX + folderKey) == "true"
    fun setAbook(context: Context, folderKey: String, enabled: Boolean) =
        set(context, KEY_MODE_PREFIX + folderKey, enabled.toString())

    /** Saved book position: the current file uri, offset, and when it was saved ([savedAtMs] is
     *  null for rows written before timestamps existed — treated as an old save). */
    data class BookPos(val fileUri: String, val ms: Long, val savedAtMs: Long?)

    /** Saved book position, or null when nothing is stored. */
    fun getBookPos(context: Context, folderKey: String): BookPos? {
        val raw = get(context, KEY_POS_PREFIX + folderKey)
        if (raw.isNullOrEmpty()) return null
        // The uri is percent-encoded (no literal '|'), so the numeric tail splits off safely.
        val parts = raw.split('|')
        if (parts.size < 2) return null
        val ms = parts[1].toLongOrNull() ?: return null
        return BookPos(parts[0], ms, parts.getOrNull(2)?.toLongOrNull())
    }
    fun setBookPos(context: Context, folderKey: String, fileUri: String, ms: Long) =
        set(context, KEY_POS_PREFIX + folderKey, "$fileUri|$ms|${System.currentTimeMillis()}")
    fun clearBookPos(context: Context, folderKey: String) =
        remove(context, KEY_POS_PREFIX + folderKey)

    /** Per-folder playback speed; falls back to the global default when the folder has none set. */
    fun getSpeed(context: Context, folderKey: String): Float =
        get(context, KEY_SPEED_PREFIX + folderKey)?.toFloatOrNull() ?: getDefaultSpeed(context)
    fun setSpeed(context: Context, folderKey: String, speed: Float) =
        set(context, KEY_SPEED_PREFIX + folderKey, speed.toString())

    /** Forgets all persisted state for a folder (used when deleting a book). */
    fun clearBook(context: Context, folderKey: String) {
        remove(context, KEY_MODE_PREFIX + folderKey)
        remove(context, KEY_POS_PREFIX + folderKey)
        remove(context, KEY_SPEED_PREFIX + folderKey)
    }

    /** Forgets every per-folder book state row under [treeUri] (modes, positions, speeds).
     *  Removing a root forgets its books too; re-adding the same tree later starts clean. */
    fun clearRootState(context: Context, treeUri: String) {
        // bookKey is "<treeUri>|<docId>", so the per-tree prefix ends at the separator.
        val keyPrefixes =
            listOf(KEY_MODE_PREFIX, KEY_POS_PREFIX, KEY_SPEED_PREFIX).map { it + treeUri + '|' }
        synchronized(lock) {
            for (key in cache.keys) {
                if (keyPrefixes.any { key.startsWith(it) }) cache[key] = null
            }
        }
        val app = context.applicationContext
        writer.execute {
            val db = AppDb.db(app)
            for (prefix in keyPrefixes) {
                db.delete("settings", "key LIKE ? ESCAPE '\\'", arrayOf(AppDb.likePrefix(prefix)))
            }
        }
    }
}
