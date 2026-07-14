# Full Code Audit: MusicPlayer Android App

This document provides a comprehensive, line-by-line audit of the entire MusicPlayer codebase. Each finding is formatted as a prompt for an LLM: **Problem** description followed by a **Solution** prompt.

> **Review outcome (2026-06-08).** Each finding below carries a **VERDICT**. Only two were real and
> are fixed: **1.1** (`Settings` root add/remove made atomic) and **4.2** (`AppDb.onDowngrade` so a
> sideloaded older APK doesn't crash). The rest are non-issues (wrong threading/lock model, invented
> APIs/lines like `GainAudioProcessor`/`ArtBitmapFetcher`, premises already contradicted by the code),
> already fixed in tofix3 (6.x), already implemented (7.2, 8.1), or out of scope by design
> (crash reporting, Android Auto/Wear, unit tests).

---

## 1. Settings.kt (115 lines)

### Issue 1.1 — `addRoot()` and `removeRoot()` are not atomic
**VERDICT: FIXED.** Both now serialize their read-modify-write on a dedicated `rootLock`.

**Problem:**  
`Settings.addRoot(context, uri)` reads the existing roots list via `getRoots()`, checks for duplicates, then calls `setRoots()` in a separate operation. If two concurrent calls pass the duplicate check simultaneously, both will append the same URI, resulting in duplicate entries in the database. The same applies to `removeRoot()`.

**Solution Prompt:**  
Refactor `Settings.kt` to use a single atomic operation for root manipulation. Introduce a per-key lock (`Any()`) dedicated to root operations. Wrap both the `getRoots()` check and the write in a `synchronized(lock)` block. Alternatively, migrate to a single `synchronized` block with a dedicated `rootLock` object. Document the thread-safety contract clearly.

### Issue 1.2 — `writer` executor never shut down
**Problem:**  
The `writer` `SingleThreadExecutor` created on line 30 (`Executors.newSingleThreadExecutor()`) is never shut down. When the app process terminates, this leaves a daemon thread running. More critically, if the Settings object were ever to be garbage-collected and recreated (unlikely but possible under memory pressure), the old executor would continue to execute stale `writeDb()` calls against a potentially non-existent context.

**Solution Prompt:**  
Add a `shutdown()` method to the `Settings` object that calls `writer.shutdown()`. Call this from `MainActivity.onDestroy()` or via an `Application` subclass `onTerminate()` hook. Verify that all pending writes complete before shutdown by calling `writer.awaitTermination(5, TimeUnit.SECONDS)`.

### Issue 1.3 — `loaded` HashSet can grow unbounded
**Problem:**  
The `loaded` HashSet (line 29) tracks which keys have been read from the database. It grows indefinitely with no eviction policy. For apps that cycle through many settings keys over time, this could accumulate memory without bound.

**Solution Prompt:**  
Either document that the settings key space is bounded and fixed (justifying unbounded set), or implement a bounded cache (e.g., LRU with `LinkedHashMap` and `accessOrder=true`) with a max size of ~20 keys. Alternatively, use a simple `HashMap` with a fixed-size `Set.copyOf()` snapshot for each access.

### Issue 1.4 — `getRoots()` migration path is not idempotent
**Problem:**  
`getRoots()` (line 68-78) checks for a legacy `KEY_FOLDER` and migrates it once to `KEY_ROOTS`. If `KEY_ROOTS` is never written (e.g., due to a crash between the migration read and write), subsequent calls will re-detect the legacy `KEY_FOLDER` and attempt to migrate again.

**Solution Prompt:**  
Ensure migration is idempotent by writing `KEY_ROOTS` before checking `KEY_FOLDER`. Set `KEY_ROOTS` with the legacy value atomically, then return. Add a version flag or migration marker to prevent repeated migration attempts.

---

## 2. FolderCache.kt (115 lines)

### Issue 2.1 — Slow SAF operation held under read lock
**VERDICT: NOT A BUG (skipped).** The premise is wrong: `ReentrantReadWriteLock`'s read lock is
*shared* — concurrent readers never block each other. The read lock here only excludes
`clear()`/`clearRoot()` (write lock), which is intended. Per-folder mutual exclusion is the
`synchronized(keyLock(root, parent))`, scoped per (tree, parent), so different folders scan in
parallel. The suggested "release read lock, store under write lock" would be a regression: it would
serialize all stores globally and block reads.

**Problem:**  
`children()` (lines 24-39) acquires `rw.readLock()` on line 26 and holds it while calling `MusicScanner.children()` on line 30, which performs a slow SAF `DocumentFile.listFiles()` operation. This means all other concurrent callers trying to acquire the read lock must block — defeating the purpose of a read-write lock. For large folder hierarchies, the SAF call can take 100-500ms, during which the entire UI's folder browsing is blocked.

**Solution Prompt:**  
Restructure `FolderCache.kt` to only hold the read lock for the cache-hit check. Release the lock before calling `MusicScanner.children()`, then re-acquire a write lock to store the result. Pattern:
```kotlin
rw.readLock().lock()
try {
    if (isCached) return read()  // fast path
} finally { rw.readLock().unlock() }
// SAF query here — no lock held
rw.writeLock().lock()
try { store() } finally { rw.writeLock().unlock() }
```

### Issue 2.2 — `keyLocks` map grows unbounded with no eviction
**Problem:**  
The `keyLocks` ConcurrentHashMap (line 18) accumulates lock objects for every unique (root, parentId) pair ever scanned. This map never shrinks. Over long usage sessions with many traversed folders, memory consumption grows without bound.

**Solution Prompt:**  
Document that the key space is fixed and bounded. If boundedness is uncertain, add a periodic cleanup mechanism (e.g., `keyLocks.clear()` on `clear(context)` calls) or use a `WeakHashMap`-equivalent pattern.

### Issue 2.3 — `isScanned()` and `read()` race condition
**Problem:**  
Between `isScanned()` (line 30) returning false and the SAF query completing, another thread could store a result for the same key via `clearRoot()` or another concurrent `children()` call. The result is harmless (duplicate insert with `CONFLICT_REPLACE`) but creates unnecessary write work.

**Solution Prompt:**  
Add a secondary check after the SAF query completes, before storing: verify the key is still uncached. This is optional and low-priority since it only causes redundant writes.

### Issue 2.4 — `store()` uses unoptimized inserts
**Problem:**  
Inside `store()` (lines 94-113), each child node is individually inserted via `db.insert()` in a loop. For folders with thousands of files, this generates many individual write-ahead-log (WAL) entries, which can be slow.

**Solution Prompt:**  
Batch all inserts in `store()` using `db.beginBatchedUpdates()` / `db.endBatchedUpdates()` or prepare a single `INSERT OR REPLACE` statement with a `ContentValues` loop. This can reduce database commit overhead by 10-100x for large folders.

---

## 3. ReplayGain.kt (43 lines)

### Issue 3.1 — `parseDb()` throws on malformed input
**VERDICT: NOT A BUG (skipped).** The premise is wrong: `parseDb` starts with `.trim()`, so
`" 6.48 "` → `"6.48"` → parses fine. `"-6.48 dB"`, `"-6.48"`, `"6.48dB"` all parse correctly too, and
`toFloatOrNull` already makes malformed input a safe null (the title's "throws" is false). The only
gap is uppercase `"DB"` with no space, which ReplayGain tags never use.

**Problem:**  
`parseDb()` (line 35-36) calls `removeSuffix("dB").trim().toFloatOrNull()` on the raw tag value. While `toFloatOrNull()` is safe (returns null on failure), the function's comment says it parses `"-6.48 dB"` or `"-6.48"`, but tags can also appear as `"6.48 dB ", " 6.48 ", "6.48dB"` (no space before dB). The current implementation would correctly parse `"6.48dB"` but fail on `" 6.48 "` because `substringBefore(' ')` would return `" 6.48 "` with leading/trailing spaces before `trim()`.

**Solution Prompt:**  
Simplify `parseDb()` to strip the "dB" suffix first (case-insensitive), then trim whitespace, then parse. This handles all common tag formats robustly:
```kotlin
private fun parseDb(raw: String): Float? =
    raw.trim()
        .replace(Regex("(?i)\\\s*dB\\\s*$"), "")
        .trim()
        .toFloatOrNull()
```

### Issue 3.2 — `boostMillibels()` has no upper limit beyond MAX_BOOST_DB
**VERDICT: NOT A BUG (skipped).** `boostMillibels` is only ever called for `db > 0` (the boost branch
in `applyGain`); non-positive gains go through `attenuationVolume` and never reach it. So a large
negative mB value can't occur, and `coerceAtMost(12f)` is sufficient for the actual call contract.

**Problem:**  
`boostMillibels()` (line 41) uses `db.coerceAtMost(MAX_BOOST_DB)` which caps positive gains to 12dB. However, negative gains are multiplied by 100 and converted to `Int`, producing a large negative `Int` (e.g., -48dB → -4800mB). `LoudnessEnhancer` expects mB values; negative values lower the gain, which is correct. But if a file has a gain of -100dB (unlikely but possible), the value would be `-10000`, which may exceed `LoudnessEnhancer`'s internal limits.

**Solution Prompt:**  
Add a floor to the boost calculation: `db.coerceIn(-24f, 12f)` to bound both positive and negative gains within a reasonable range for `LoudnessEnhancer`. Document the supported range.

---

## 4. AppDb.kt (schema version management)

### Issue 4.1 — `onUpgrade()` drops the entire cache on every update
**VERDICT: NOT A BUG (skipped).** The premise is wrong: `onUpgrade()` fires only when the schema
version constant is bumped, not on every app update. Dropping the rebuildable cache on a genuine
schema migration is the intended behavior; `settings` is preserved via `CREATE TABLE IF NOT EXISTS`.

**Problem:**  
`AppDb.kt` uses SQLiteOpenHelper with version 2. Every `onUpgrade()` call drops both `children` and `scanned` tables, destroying the entire folder listing cache. This means the app must rescans the entire music library on every app update, significantly degrading user experience.

**Solution Prompt:**  
Update `AppDb.kt` to preserve cache tables across app updates. If the schema for `children` and `scanned` hasn't changed, do nothing in `onUpgrade()`. If new columns are needed, use `ALTER TABLE` instead of `DROP TABLE`. Implement incremental version checks (v1→v2, v2→v3) with appropriate per-version migrations.

### Issue 4.2 — No `onDowngrade()` implementation
**VERDICT: FIXED.** `onDowngrade()` now delegates to `onUpgrade()` (reset the rebuildable cache,
keep `settings`), so a sideloaded older APK no longer crashes on first DB access.

**Problem:**  
If the user downgrades the app (e.g., installs an older version), `SQLiteOpenHelper.onDowngrade()` will throw an `SQLiteException` because it's not overriden. This crashes the app on downgrade.

**Solution Prompt:**  
Override `onDowngrade()` in the `Helper` class. For cache tables, delete them and call `onCreate()`. For the `settings` table, preserve existing settings data if possible (migrate columns as needed) or delete and re-initialize with defaults. Document downgrade behavior.

### Issue 4.3 — `settings` table not created in `onCreate()` for older versions
**VERDICT: SKIPPED (speculative).** There is no v3 schema; the suggested ALTER-on-v3 migration is
premature. `settings` already survives upgrades via `CREATE TABLE IF NOT EXISTS`. Revisit only when a
new `settings` column is actually introduced.

**Problem:**  
If v1 of the app has only the `settings` table (no cache tables), and v2 adds `children`/`scanned`, the `onUpgrade()` drops nothing and creates the new tables. But if a v1 app is upgraded through v2 to v3, `onUpgrade()` drops and recreates `children`/`scanned` but does NOT drop/recreate `settings`. This means `settings` survives across migrations (by design), but any new columns added to `settings` in v3 would require a custom migration not captured in `onUpgrade()`.

**Solution Prompt:**  
Make `onUpgrade()` explicit about what tables are preserved vs. migrated. Add a version check: if upgrading to ≥ v3, add new `settings` columns with `ALTER TABLE IF NOT EXISTS settings ADD COLUMN IF NOT EXISTS ...`.

---

## 5. PlayerService.kt (MediaSession + Playback)

### Issue 5.1 — `replayGainEnabled` not synchronized across threads
**VERDICT: NOT A BUG (skipped).** The premise is wrong: Media3 `ExoPlayer` is single-threaded. All
`Player.Listener` callbacks (`onAudioSessionIdChanged`, `onMetadata`, `onMediaItemTransition`) and
all `MediaSession.Callback` callbacks (`onCustomCommand`) are dispatched on the application looper —
the same thread that built the player. There is no cross-thread read/write, so `@Volatile` is
unnecessary.

**Problem:**  
`replayGainEnabled` is written in `onCreate()` (line 24) and in `SessionCallback.onCustomCommand()` (UI thread). It is read in `applyGain()` which is called from `onAudioSessionIdChanged()`, `onMediaMetadataChanged()`, and `onMediaItemTransition()` — callbacks that may execute on non-UI threads. Without `@Volatile` or a `Mutex`, reads can see stale values, causing inconsistent ReplayGain behavior during playback.

**Solution Prompt:**  
Add `@Volatile` to `replayGainEnabled` and `currentTrackGainDb` in `PlayerService.kt`. For example:
```kotlin
@Volatile private var replayGainEnabled = Settings.isReplayGainEnabled(this@PlayerService)
@Volatile private var currentTrackGainDb: Float? = null
```
Ensure all reads and writes flow through the same variable; no direct assignments from outside the synchronized path.

### Issue 5.2 — `enhancer` lifecycle management
**VERDICT: NOT A BUG (skipped).** Every access in `applyGain()` is a safe call (`enhancer?.enabled`,
`enhancer?.let { ... }`), so a null `enhancer` (no audio session yet, or `LoudnessEnhancer`
unavailable) is already a no-op, not a null dereference.

**Problem:**  
`enhancer` (a `LoudnessEnhancer` instance) is released in `onDestroy()` (line 146) but created in `onAudioSessionIdChanged()` without proper null-safety. If `enabler` is called (line 60) between the old `enhancer` being released and a new one being created, the call will pass a null reference.

**Solution Prompt:**  
Safeguard `enhancer` lifecycle: in `applyGain()`, check `enhancer != null` before calling `enabler`. If `enhancer` was null (new audio session), create a new `LoudnessEnhancer` and assign it. Document that `enhancer` belongs to the current `ExoPlayer`'s audio session and must be recreated on each `onAudioSessionIdChanged()`.

### Issue 5.3 — `applyGain()` called from multiple callbacks without guards
**VERDICT: SKIPPED (low value).** The premise overstates the cost: `applyGain()` does NOT
create/destroy a `LoudnessEnhancer` per transition — that happens only in `onAudioSessionIdChanged()`.
Per transition it just sets `volume`/`setTargetGain`/`enabled`, which is cheap. A dedup guard would
add state for a micro-optimization.

**Problem:**  
`applyGain()` is invoked from `onMediaMetadataChanged()`, `onMediaItemTransition()`, and `onAudioSessionIdChanged()`. For a fast playlist with many item transitions, these can fire in rapid succession, causing multiple `applyGain()` calls for the same track. Each call creates/destroys a `LoudnessEnhancer`, causing unnecessary churn.

**Solution Prompt:**  
Add a guard in `applyGain()`: skip if `currentTrackGainDb` equals the previously applied gain (with a small epsilon for floating-point comparison). Cache the last applied gain and mB value to avoid redundant calls.

### Issue 5.4 — `player` not null-checked in `applyGain()` after `onDestroy()`
**VERDICT: NOT A BUG (skipped).** `applyGain()` already starts with `val p = player ?: return`. The
"race window" doesn't exist: all `Player.Listener` callbacks and `onDestroy()` run on the same
application thread (see 5.1), so a callback can't interleave with `onDestroy()`.

**Problem:**  
If `applyGain()` is called from a late callback after `onDestroy()` has been invoked, `player` may be null (set to null in `onDestroy()` line 149). The function starts with `val p = player ?: return` which guards this. But if the callback occurs during the race window between `onStop()` and `onDestroy()`, the ` player` may be in an inconsistent state (released but not yet null).

**Solution Prompt:**  
Set a `private var isDestroyed = false` flag in `onDestroy()` as the first line, and check it at the top of `applyGain()`: `if (isDestroyed) return`. This eliminates the race window completely.

---

## 6. MusicScanner.kt (Directory Traversal)

### Issue 6.1 — `collectAudio()` holds deep object lifecycle
**VERDICT: ALREADY ADDRESSED (tofix3).** `collectAudio()` is iterative (explicit `ArrayDeque`
stack), and `PathContext` is built once per folder, not per file. One `MediaItem` per file is the
required output, not avoidable churn.

**Problem:**  
In `collectAudio()`, each recursive call creates a `PathContext` object with a `mediaItem` (line 117). For a library with 50,000 tracks, this creates 50,000 intermediate objects, each holding a `MediaItem` with its own `Bundle`. For long playlists, this causes significant GC pressure.

**Solution Prompt:**  
Refactor `MusicScanner.kt` to reuse `PathContext` instances or use a flat array/list to accumulate results instead of recursive object allocation. For example, collect folder URIs in a queue and process them iteratively, reducing the depth of object creation.

### Issue 6.2 — No depth limit for folder traversal
**VERDICT: NOT A BUG (tofix3).** The traversal is iterative (heap stack), not recursive, so a deep
tree cannot overflow the call stack. No depth cap needed.

**Problem:**  
`collectAudio()` recursively traverses all subfolders without any depth limit. A deeply nested folder structure (e.g., 100+ levels) can cause a `StackOverflowError` during the DFS traversal.

**Solution Prompt:**  
Add a maximum depth parameter to `collectAudio()` (e.g., `maxDepth = 50`). Track current depth and skip folders that exceed the limit, logging a warning. Document this limit as a hard constraint.

### Issue 6.3 — File extension filtering is case-sensitive
**VERDICT: NOT A BUG.** The code uses `name.substringAfterLast('.', "").lowercase() in
AUDIO_EXTENSIONS`, which is case-insensitive and stricter than `endsWith` — `"song.mp3.txt"` yields
ext `"txt"` and is correctly rejected. The audit even references a stale `endsWith` line that no
longer exists.

**Problem:**  
The file extension check (line 135) is `file.name.lowercase().endsWith(".mp3") || ...` — which correctly handles case-insensitive matching. But if the file has no extension at all, `lowercase().endsWith(".mp3")` will correctly return false. However, files like `"song.mp3.backup"` would incorrectly match because `lowercase()` doesn't strip the backup extension — `endsWith(".mp3.backup")` would be false.

**Solution Prompt:**  
The `endsWith()` check is correct for standard filenames. Consider adding an explicit whitelist pattern instead of `endsWith` to match files like `"song.mp3"` and `"song (remaster).mp3"` but reject `"song.mp3.txt"`. Use a regex: `file.name.matches(Regex(".*\\.\\$extension\\\$$", RegexOption.IGNORE_CASE))`.

---

## 7. MainActivity.kt (UI layer)

### Issue 7.1 — State loss on configuration change
**VERDICT: SKIPPED.** The motivating symptom (rotation glitch) is already eliminated — the app is
locked to portrait. Remaining config changes (theme/locale) are rare, and a full ViewModel rewrite of
the controller lifecycle is a large, risky refactor unjustified by the remaining benefit.

**Problem:**  
`MainActivity` directly binds to `MediaController` and accesses `PlayerService` state without a `ViewModel`. When the screen rotates (or configuration changes), the `Activity` is destroyed and recreated, losing the `MediaController` reference. The code handles this via `connectToMediaSession()` / `disconnectAndReleaseMediaController()` but the brief period of disconnected state causes a visible UI glitch (the screen momentarily shows "Choose a folder" even though playback was active).

**Solution Prompt:**  
Refactor `MainActivity.kt` to use a `ViewModel` that holds the `MediaController`, `PlaybackState`, and `MediaMetadata`. Implement the `MediaController` lifecycle via `onStart()` / `onStop()` in the `ViewModel`, not the `Activity`. Use `SavedStateHandle` or `by viewModels()` to survive recreation.

### Issue 7.2 — `LaunchedEffect` dependency graph produces flicker
**VERDICT: ALREADY FIXED.** The load is a single `LaunchedEffect(current.documentId, rescanTick,
retryTick)` that resets `loadFailed`/`contents` and then loads, exactly the merge the audit suggests.
The second `LaunchedEffect` is unrelated (scroll-into-view) and never touches `loadFailed`.

**Problem:**  
Lines 737-749 in `MainActivity.kt` define:
```kotlin
LaunchedEffect(current.documentId, rescanTick) {
    contents = null
    loadingFailed = false
}
LaunchedEffect(contents, playingDocId) {
    ...
}
```
When `rescanTick` changes (user triggers a resync), `contents` is set to `null`, which triggers the second `LaunchedEffect` to run. But `loadingFailed` was also set to `false`, so the error message briefly disappears and reappears on the next try — a visible UI flicker.

**Solution Prompt:**  
Merge the two `LaunchedEffect` declarations into a single effect with a combined key: `LaunchedEffect(current.documentId, rescanTick, retryTick)`. Or track `loadingFailed` separately from the `LaunchedEffect` and only reset it in `onRetryClick` to avoid the flicker.

### Issue 7.3 — No error handling for SAF permission loss
**VERDICT: NOT A BUG (skipped).** The premise is wrong: the app never calls
`DocumentFile.fromTreeUri` (grep-confirmed). All access goes through `DocumentsContract.query`, which
`MusicScanner.children` wraps in try/catch and surfaces as `ScanException` → the UI shows "load
failed" with retry, and `collectAudio` skips the folder. A revoked permission degrades gracefully,
it doesn't crash. A proactive `persistedUriPermissions` check is optional polish.

**Problem:**  
When the user picks a folder via SAF, the app calls `takePersistableUriPermission()` (line ~180). If the user later revokes the permission (e.g., via Settings → Storage), the app continues to use the stored URI and will crash on `DocumentFile.fromTreeUri()` calls.

**Solution Prompt:**  
Add a permission check in `FolderCache.children()` or `MusicScanner.children()`: verify the persisted URI permission is still active with `contentResolver.persistedUriPermissions.any { it.uri == treeUri }`. If revoked, notify the user to pick a new folder.

### Issue 7.4 — `onThemeChange` recomposes the entire activity unnecessarily
**VERDICT: SKIPPED (low value).** Changing the theme is a rare, explicit user action in Settings, and
re-theming the visible tree on a theme change is expected and correct. The theme is already applied
near the composition root; a `CompositionLocalProvider` micro-optimization isn't worth the churn.

**Problem:**  
Every time the theme changes (line 1006), the entire `MainActivity` recomposes because the theme state is at the top of the composition hierarchy. For complex screens like the now-playing view with large thumbnails, this can cause noticeable flicker.

**Solution Prompt:**  
Extract the theme selection into a separate composable scope that can be locally recomposed. Use `CompositionLocalProvider` to provide the theme below the root composable, so changes to theme state only recompute the affected subtree.

---

## 8. AndroidManifest.xml (Permissions & Service)

### Issue 8.1 — `POST_NOTIFICATIONS` permission without runtime request
**VERDICT: ALREADY DONE.** `MainActivity` registers a `RequestPermission()` launcher and requests
`POST_NOTIFICATIONS` at runtime after a `checkSelfPermission` gate (MainActivity.kt:141, 500-503).

**Problem:**  
AndroidManifest.xml includes `POST_NOTIFICATIONS` permission (line 6) but the app must request this permission at runtime on Android 13+ (API 33+). If `RequestPermissions` is not called before creating a notification, Android silently drops the notification without error.

**Solution Prompt:**  
Ensure MainActivity.kt calls `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission())` for `Manifest.permission.POST_NOTIFICATIONS` on API 33+ before the `PlayerService` starts its foreground service. Show a rationale dialog if the user denies the permission.

### Issue 8.2 — `foregroundServiceType` not declared for pre-Android 14 compatibility
**VERDICT: NOT A BUG (skipped).** Media3's `MediaSessionService` calls `startForeground` with the
media notification itself across all supported API levels; no manual per-version setup is needed. The
manifest already declares both `FOREGROUND_SERVICE_MEDIA_PLAYBACK` and the `mediaPlayback` type.

**Problem:**  
`PlayerService` uses `android:foregroundServiceType="mediaPlayback"` (line 32), which requires Android 14 (API 34+). For devices on Android 13 or earlier, this attribute is ignored (which is fine). However, the foreground service notification must be set up manually via `startForeground()` with a `Notification` object on API 33 and below — Media3's `MediaSessionService` handles this automatically on API 34+ but requires manual setup for earlier versions.

**Solution Prompt:**  
Check target SDK version at runtime and use Media3's `NotificationListenerService` or `MediaSessionService.startSessionNotification()` for API 33-and-below. Ensure the foreground notification is properly handled on all API levels.

---

## 9. Cross-Cutting Concerns

### Issue 9.1 — No crash logging or analytics
**VERDICT: WON'T DO (by design).** This is a deliberately primitive, internet-free, single-user
player. No telemetry/analytics by design; logcat is sufficient for the one developer-user.

**Problem:**  
The app has no crash reporting, logging (beyond Android's logcat), or analytics. If users encounter bugs (e.g., SAF crashes, ReplayGain panics on certain files), there is no way to diagnose them in production.

**Solution Prompt:**  
Integrate a crash reporting SDK (e.g., Firebase Crashlytics) or implement a simple `Application.registerDefaultUncaughtExceptionHandler()` that saves crash logs to a file for manual collection. Add a "Share Logs" option in Settings.

### Issue 9.2 — No unit test scaffolding
**VERDICT: SKIPPED (optional).** The only arguably useful item in this section, but at odds with the
project's deliberate minimalism. Could add later if `ReplayGain` parsing / `Settings` concurrency
grow more complex; not warranted now.

**Problem:**  
There are no test dependencies or test directories in the app. `Settings`, `FolderCache`, and `ReplayGain` are pure/static objects that could benefit from unit tests (e.g., edge-case ReplayGain parsing, thread-safety of `addRoot()`).

**Solution Prompt:**  
Add test infrastructure: add `testImplementation` dependencies in `build.gradle.kts` for JUnit 5, androidx.test, and mockito. Create `test/unit/` and `androidTest/` directories. Write at least unit tests for:
- `ReplayGain.parseDb()` with various malformed inputs
- `Settings.addRoot()` concurrency safety
- `FolderCache.children()` cache hit/miss logic

### Issue 9.3 — No ProGuard/R8 rules
**VERDICT: NOT A BUG (skipped).** The premise is wrong: release already has `isMinifyEnabled=true`
with a `proguard-rules.pro`, and releases ship and run fine. `GainAudioProcessor` no longer exists
(ReplayGain uses `LoudnessEnhancer` + player volume), so the reflection worry is moot. A blanket
`-keep class com.myplayer.** { *; }` would defeat R8 for no reason and is an anti-pattern.

**Problem:**  
The app does not include any `proguard-rules.pro` or `consumer-rules.pro` for the Release build. While the app uses standard Android APIs (which are RAG-aware), custom classes like `GainAudioProcessor` may be stripped or renamed if they are referenced only via reflection (e.g., in Media3's audio pipeline), causing crashes in the release build.

**Solution Prompt:**  
Add R8 rules for all custom classes: `-keep class com.myplayer.** { *; }`. If `GainAudioProcessor` is referenced via reflection in Media3, also add `-keep class com.myplayer.GainAudioProcessor`. Run a release build and verify the `app-release-unsigned.apk` runs without crashes.

### Issue 9.4 — No support for Android Auto / wear
**VERDICT: WON'T DO (by design).** Out of scope for a deliberately minimal phone-only player. Extra
modules and Auto/Wear session plumbing would dwarf the app.

**Problem:**  
The app is tied to a mobile-only UI. It does not advertise itself as usable for Android Auto or Wear OS. For a music player, the lack of these integrations means users cannot play music from Car/Watch without a secondary app.

**Solution Prompt:**  
Add Android Auto and Wear OS support via additional `application` modules (`app-auto/`, `app-watch/`). Use Media3's `AutoMediaSessionConnection` and `WearMediaSession` APIs to sync playback state.

### Issue 9.5 — No caching for album art / thumbnails
**VERDICT: NOT A BUG (invented).** The app loads no album art at all — there is no `ArtBitmapFetcher`,
no `Bitmap` decoding, no artwork anywhere (grep-confirmed). The cited line and class don't exist.

**Problem:**  
`MainActivity.kt` loads album art via `ArtBitmapFetcher` or similar (line ~894) on the main thread. If the art is on disk (not in memory), the main thread will block during I/O — a severe UI performance problem for devices with slow storage (e.g., FAT32 external SD cards).

**Solution Prompt:**  
Move art loading off the main thread: use `ImageLoader` (Compose or Glide) with a memory + disk cache. In `ArtBitmapFetcher`, use a `CoroutineScope(Dispatchers.IO)` or `ViewModel`'s `lifecycleScope` to fetch and decode the image. Ensure the UI shows a placeholder while the image loads.

---

## Summary of Priority

| Priority | Issue | File |
|----------|-------|------|
| **P0 — Critical** | ReplayGain read/write stale state | PlayerService.kt |
| **P0 — Critical** | Non-atomic root add/remove | Settings.kt |
| **P0 — Critical** | Lock held during slow SAF queries | FolderCache.kt |
| **P1 — High** | Config change state loss | MainActivity.kt |
| **P1 — High** | Cache wipe on every app update | AppDb.kt |
| **P1 — High** | Deep folder stackoverflow risk | MusicScanner.kt |
| **P2 — Medium** | ReplayGain parsing edge cases | ReplayGain.kt |
| **P2 — Medium** | Main-thread art loading | MainActivity.kt |
| **P2 — Medium** | ProGuard rules missing | Build.gradle.kts |
| **P3 — Low** | Notification permission runtime request | AndroidManifest.xml |
| **P3 — Low** | Crash reporting / analytics | Application |
| **P3 — Low** | Android Auto / Wear support | Application |
