package com.myplayer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

enum class ThemeMode {
    System, Light, Dark;

    companion object {
        fun from(value: String?): ThemeMode = entries.firstOrNull { it.name == value } ?: System
    }
}

/** Accent colour applied to the Material `primary`/`onPrimary` roles. Values are packed ARGB longs;
 *  [Default] keeps the built-in (theme-dependent) Material lilac, signalled by zero sentinels. Each
 *  colour is a mid-tone that reads on both the light and dark backgrounds, with a matching foreground. */
enum class AccentColor(val primary: Long, val onPrimary: Long) {
    Default(0L, 0L),
    Yellow(0xFFFFC107, 0xFF000000),
    Teal(0xFF26A69A, 0xFF000000),
    Pink(0xFFEC407A, 0xFF000000),
    Green(0xFF66BB6A, 0xFF000000),
    Orange(0xFFFFA726, 0xFF000000),
    Blue(0xFF42A5F5, 0xFF000000),
    Beige(0xFFD2B48C, 0xFF000000);

    companion object {
        fun from(value: String?): AccentColor = entries.firstOrNull { it.name == value } ?: Default
    }
}

/** How music loudness is evened out. [Off] leaves it raw; [ReplayGain] applies per-track gain from
 *  file tags only (untagged files play unchanged); [AutoLevel] runs a real-time compressor/limiter on
 *  the audio session, so every track is leveled regardless of tags. The two are mutually exclusive. */
enum class VolumeNorm {
    Off, ReplayGain, AutoLevel;

    companion object {
        fun from(value: String?): VolumeNorm = entries.firstOrNull { it.name == value } ?: Off
    }
}

/** App settings stored in the `settings` table of [AppDb]. */
object Settings {
    private const val KEY_FOLDER = "folder_uri"
    private const val KEY_ROOTS = "roots"
    private const val KEY_REPLAYGAIN = "replaygain" // legacy boolean, migrated into KEY_VOLUME_NORM
    private const val KEY_VOLUME_NORM = "volume_norm"
    private const val KEY_SKIP_SILENCE = "skip_silence"
    private const val KEY_TRACK_GAP = "track_gap"
    private const val KEY_SEEK_STEP = "seek_step"
    private const val KEY_THEME = "theme"
    private const val KEY_ACCENT = "accent"
    private const val KEY_FOLLOW = "follow"
    private const val KEY_NOMEDIA = "nomedia"
    private const val KEY_REMAINING = "remaining"
    private const val KEY_BACKUP = "backup"
    private const val KEY_DEFAULT_SPEED = "default_speed"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_HISTORY = "history"
    private const val KEY_FAVORITES = "favorites"
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

    // Seek step (seconds) for the rewind/forward buttons; coarse options, current default 30.
    val SEEK_STEP_OPTIONS = listOf(10, 20, 30, 45, 60)
    const val SEEK_STEP_DEFAULT = 30

    // Inter-track gap (seconds) inserted as silent audio between music files; 0 = off (default).
    // Music-only: books never get a gap. While set, it supersedes skip-silence for music (which
    // would cut the generated silence away); books keep skip-silence regardless.
    val TRACK_GAP_OPTIONS = listOf(0, 1, 2, 3, 4, 5)
    const val TRACK_GAP_DEFAULT = 0

    // Upper bound for a believable track duration. A gapped queue reports PlayerService's 24h
    // placeholder until a track's real length is prepared; the UI treats anything at or above
    // this as "duration unknown" instead of showing (or seeking against) a bogus 24h scale.
    const val MAX_TRACK_DURATION_MS = 20L * 60 * 60 * 1000

    // Sleep-timer slider bounds and granularity (minutes), shared by the player and its dialog.
    const val SLEEP_MIN = 10f
    const val SLEEP_MAX = 60f
    const val SLEEP_STEP = 5f
    const val SLEEP_DEFAULT = 20f

    // How many recently played folders the history dialog keeps.
    const val HISTORY_MAX = 7

    // How many manually pinned folders the favorites dialog keeps (the dialog scrolls).
    const val FAVORITES_MAX = 33

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

    // Serializes the read-modify-write of the history list, like rootLock for roots.
    private val historyLock = Any()

    // Serializes the read-modify-write of the favorites list, like historyLock for history.
    private val favoritesLock = Any()

    // Parsed favorite keys, cached so the per-folder-row isFavorite() check doesn't re-parse the
    // whole favorites JSON on every (re)composition. Invalidated (null) whenever favorites change.
    @Volatile private var favoriteKeysCache: Set<String>? = null

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

    /** Removes [uri]; returns the updated list. Also forgets any history under that tree, so a
     *  later history tap can't open a folder whose root is gone. */
    fun removeRoot(context: Context, uri: String): List<String> = synchronized(rootLock) {
        val updated = getRoots(context).filter { it != uri }
        setRoots(context, updated)
        removeHistoryForTree(context, uri)
        removeFavoritesForTree(context, uri)
        return updated
    }

    /** Music loudness-normalization mode. Migrates the legacy on/off ReplayGain boolean on first read:
     *  the old "on" becomes [VolumeNorm.ReplayGain], "off"/absent becomes [VolumeNorm.Off]. */
    fun getVolumeNorm(context: Context): VolumeNorm {
        get(context, KEY_VOLUME_NORM)?.let { return VolumeNorm.from(it) }
        return if (get(context, KEY_REPLAYGAIN) == "true") VolumeNorm.ReplayGain else VolumeNorm.Off
    }
    fun setVolumeNorm(context: Context, mode: VolumeNorm) =
        set(context, KEY_VOLUME_NORM, mode.name)

    /** Skip silence: let ExoPlayer drop silent stretches so books/podcasts play tighter (default off). */
    fun isSkipSilenceEnabled(context: Context): Boolean = get(context, KEY_SKIP_SILENCE) == "true"
    fun setSkipSilenceEnabled(context: Context, enabled: Boolean) =
        set(context, KEY_SKIP_SILENCE, enabled.toString())

    /** Inter-track gap in seconds (0 = off); generated as audio silence, not a player pause.
     *  Applies to music queues only (see [PlayerService.GapMediaSourceFactory]). */
    fun getTrackGapSeconds(context: Context): Int =
        get(context, KEY_TRACK_GAP)?.toIntOrNull()?.takeIf { it in TRACK_GAP_OPTIONS }
            ?: TRACK_GAP_DEFAULT
    fun setTrackGapSeconds(context: Context, seconds: Int) =
        set(context, KEY_TRACK_GAP, seconds.toString())

    /** Rewind/forward seek step in seconds; falls back to [SEEK_STEP_DEFAULT] when unset or invalid. */
    fun getSeekStepSeconds(context: Context): Int =
        get(context, KEY_SEEK_STEP)?.toIntOrNull()?.takeIf { it in SEEK_STEP_OPTIONS }
            ?: SEEK_STEP_DEFAULT
    fun setSeekStepSeconds(context: Context, seconds: Int) =
        set(context, KEY_SEEK_STEP, seconds.toString())

    fun getThemeMode(context: Context): ThemeMode = ThemeMode.from(get(context, KEY_THEME))
    fun setThemeMode(context: Context, mode: ThemeMode) = set(context, KEY_THEME, mode.name)

    fun getAccentColor(context: Context): AccentColor = AccentColor.from(get(context, KEY_ACCENT))
    fun setAccentColor(context: Context, color: AccentColor) = set(context, KEY_ACCENT, color.name)

    /** Follow playback: jump the browser to the playing track's folder and center it (default on). */
    fun isFollowEnabled(context: Context): Boolean = get(context, KEY_FOLLOW) != "false"
    fun setFollowEnabled(context: Context, enabled: Boolean) =
        set(context, KEY_FOLLOW, enabled.toString())

    /** Drop a .nomedia file into each root so the system media scanner skips it, keeping books/music
     *  out of other players and the gallery. Our SAF-based playback is unaffected (default on). */
    fun isNomediaEnabled(context: Context): Boolean = get(context, KEY_NOMEDIA) != "false"
    fun setNomediaEnabled(context: Context, enabled: Boolean) =
        set(context, KEY_NOMEDIA, enabled.toString())

    /** Rightmost time readout: remaining (-mm:ss) when on, total when off (default off). */
    fun isRemainingTime(context: Context): Boolean = get(context, KEY_REMAINING) == "true"
    fun setRemainingTime(context: Context, enabled: Boolean) =
        set(context, KEY_REMAINING, enabled.toString())

    /** Whether Android Auto Backup may upload settings + book progress (default off, so an uninstall
     *  wipes everything). The manifest enables backup; [MyBackupAgent] gates the actual upload on
     *  this flag, so the toggle takes effect at runtime. */
    fun isBackupEnabled(context: Context): Boolean = get(context, KEY_BACKUP) == "true"
    fun setBackupEnabled(context: Context, enabled: Boolean) =
        set(context, KEY_BACKUP, enabled.toString())

    /** Selected UI language code (see [AppLocalizer]). Nothing stored yet (first run) and a stored
     *  code no longer offered by `i18n.json` (edited file, restore from another version) both fall
     *  back to the device language when a translation exists, else English. Validation is skipped
     *  before [AppLocalizer] is loaded — in practice it always is (MainActivity.onCreate loads it
     *  before the first read). */
    fun getLanguage(context: Context): String {
        val stored = get(context, KEY_LANGUAGE) ?: return AppLocalizer.systemLanguage()
        val options = AppLocalizer.languageOptions()
        return if (options.isEmpty() || options.any { it.code == stored }) stored
        else AppLocalizer.systemLanguage()
    }
    fun setLanguage(context: Context, code: String) = set(context, KEY_LANGUAGE, code)

    /** A speed is usable only when finite and within [SPEED_MIN]..[SPEED_MAX]; a corrupt, manually
     *  edited, or foreign restored DB value (NaN, infinity, out of range) would otherwise crash
     *  formatSpeed's roundToInt, the in-range sliders, or Media3's playback parameters. Returns null
     *  so callers can fall back to their own default. */
    private fun validSpeed(raw: Float?): Float? =
        raw?.takeIf { it.isFinite() && it in SPEED_MIN..SPEED_MAX }

    /** Default playback speed applied to folders without a saved speed of their own. */
    fun getDefaultSpeed(context: Context): Float =
        validSpeed(get(context, KEY_DEFAULT_SPEED)?.toFloatOrNull()) ?: SPEED_DEFAULT
    fun setDefaultSpeed(context: Context, speed: Float) =
        set(context, KEY_DEFAULT_SPEED, (validSpeed(speed) ?: SPEED_DEFAULT).toString())

    // ---- Recently played folders (history) -------------------------------------------------------

    /** A folder the user played: the tree it lives under plus the full browser path to it
     *  ([ids]/[names] are parallel, root-first), and whether it played as a book. The path is kept
     *  whole so a history tap can restore the browser straight to this folder. */
    data class HistoryEntry(
        val treeUri: String,
        val ids: List<String>,
        val names: List<String>,
        val isBook: Boolean
    ) {
        /** Identity for de-duplication: the same folder is one entry regardless of how it was opened. */
        val key: String get() = "$treeUri|${ids.lastOrNull().orEmpty()}"
    }

    fun getHistory(context: Context): List<HistoryEntry> = readEntries(context, KEY_HISTORY)

    /** Records [entry] at the front (most-recent-first), drops any earlier hit on the same folder,
     *  and caps the list at [HISTORY_MAX]. */
    fun addHistory(context: Context, entry: HistoryEntry) = synchronized(historyLock) {
        val updated = (listOf(entry) + getHistory(context).filter { it.key != entry.key })
            .take(HISTORY_MAX)
        writeEntries(context, KEY_HISTORY, updated)
    }

    /** Drops the single history entry with this [HistoryEntry.key] (manual removal from the dialog,
     *  or pruning a folder that no longer exists on storage). */
    fun removeHistoryEntry(context: Context, key: String) = synchronized(historyLock) {
        writeEntries(context, KEY_HISTORY, getHistory(context).filter { it.key != key })
    }

    /** Drops every history entry under [treeUri] (used when a root is removed). */
    fun removeHistoryForTree(context: Context, treeUri: String) = synchronized(historyLock) {
        writeEntries(context, KEY_HISTORY, getHistory(context).filter { it.treeUri != treeUri })
    }

    /** Drops the history entry for [folderId] and any entry whose path runs through it (its
     *  descendants), used when a folder is deleted from storage. */
    fun removeHistoryForFolder(context: Context, treeUri: String, folderId: String) =
        synchronized(historyLock) {
            writeEntries(context, KEY_HISTORY, getHistory(context).filter {
                it.treeUri != treeUri || folderId !in it.ids
            })
        }

    // ---- Favorites (manually pinned folders) -----------------------------------------------------
    // Same shape and navigation as history, but pinned by hand and capped high enough (FAVORITES_MAX,
    // the dialog scrolls) that the oldest-out eviction is not hit in practice.

    fun getFavorites(context: Context): List<HistoryEntry> = readEntries(context, KEY_FAVORITES)

    /** Whether the folder with this [HistoryEntry.key] is pinned. Reads the cached key set
     *  (rebuilt once after each change) so it is cheap to call per list row. */
    fun isFavorite(context: Context, key: String): Boolean {
        favoriteKeysCache?.let { return key in it }
        return getFavorites(context).map { it.key }.toSet()
            .also { favoriteKeysCache = it }
            .let { key in it }
    }

    /** Pins [entry] at the front, de-duped by folder, capped at [FAVORITES_MAX]. */
    fun addFavorite(context: Context, entry: HistoryEntry) = synchronized(favoritesLock) {
        val updated = (listOf(entry) + getFavorites(context).filter { it.key != entry.key })
            .take(FAVORITES_MAX)
        writeEntries(context, KEY_FAVORITES, updated)
        favoriteKeysCache = null
    }

    /** Unpins the folder with this [HistoryEntry.key]. */
    fun removeFavorite(context: Context, key: String) = synchronized(favoritesLock) {
        writeEntries(context, KEY_FAVORITES, getFavorites(context).filter { it.key != key })
        favoriteKeysCache = null
    }

    /** Drops every favorite under [treeUri] (used when a root is removed). */
    fun removeFavoritesForTree(context: Context, treeUri: String) = synchronized(favoritesLock) {
        writeEntries(context, KEY_FAVORITES, getFavorites(context).filter { it.treeUri != treeUri })
        favoriteKeysCache = null
    }

    /** Drops the favorite for [folderId] and any entry whose path runs through it (used when a
     *  folder is deleted from storage). */
    fun removeFavoriteForFolder(context: Context, treeUri: String, folderId: String) =
        synchronized(favoritesLock) {
            writeEntries(context, KEY_FAVORITES, getFavorites(context).filter {
                it.treeUri != treeUri || folderId !in it.ids
            })
            favoriteKeysCache = null
        }

    // ---- Shared folder-entry (history/favorites) JSON --------------------------------------------

    private fun readEntries(context: Context, key: String): List<HistoryEntry> {
        val raw = get(context, key) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val ids = o.getJSONArray("ids").let { a -> List(a.length()) { a.getString(it) } }
                val names = o.getJSONArray("names").let { a -> List(a.length()) { a.getString(it) } }
                if (ids.isEmpty() || ids.size != names.size) return@mapNotNull null
                HistoryEntry(o.getString("tree"), ids, names, o.optBoolean("book", false))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeEntries(context: Context, key: String, entries: List<HistoryEntry>) {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(
                JSONObject()
                    .put("tree", e.treeUri)
                    .put("ids", JSONArray(e.ids))
                    .put("names", JSONArray(e.names))
                    .put("book", e.isBook)
            )
        }
        set(context, key, arr.toString())
    }

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
        validSpeed(get(context, KEY_SPEED_PREFIX + folderKey)?.toFloatOrNull())
            ?: getDefaultSpeed(context)
    fun setSpeed(context: Context, folderKey: String, speed: Float) =
        set(context, KEY_SPEED_PREFIX + folderKey, (validSpeed(speed) ?: SPEED_DEFAULT).toString())

    /** Forgets all persisted state for a folder (used when deleting a book). */
    fun clearBook(context: Context, folderKey: String) {
        remove(context, KEY_MODE_PREFIX + folderKey)
        remove(context, KEY_POS_PREFIX + folderKey)
        remove(context, KEY_SPEED_PREFIX + folderKey)
    }

    /** Forgets every per-folder book state row under [treeUri] (modes, positions, speeds).
     *  Removing a root forgets its books too; re-adding the same tree later starts clean.
     *
     *  Relies on no concurrent reads of the removed tree's keys: the cache pass nulls only the keys
     *  already loaded, while the DB delete runs on the background writer. A get() of a not-yet-cached
     *  key under this tree between the two could re-cache a stale row from disk. Safe in practice —
     *  the only caller is removeRoot, after which the browser can't reach the removed root's folders. */
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
