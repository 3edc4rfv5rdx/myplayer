# tofix3.md

The tasks below are written as prompts for an LLM implementer. Repository: Android/Kotlin/Compose
app `com.myplayer` (folder player on Media3/ExoPlayer). Each prompt is self-contained: read the
named files, make the focused change, and verify with the listed commands. Do not bump
`build_number.txt`, do not auto-build/install, and update `CHANGELOG.md` in the same commit as any
code change. These are new findings, distinct from `tofix1.md` and `tofix2.md`.

---

## Prompt 1: Make async playback loads lifecycle-safe and cancel stale ones

`MainActivity.playFolder()` and `playFile()` capture the `MediaController` into a local `val` and
then `lifecycleScope.launch { withContext(Dispatchers.IO) { ...scan... }; controller.setMediaItems(...) }`.
Two problems:
- **Stale-result race.** Nothing cancels a previous in-flight load. If the user taps folder A (slow
  recursive scan) then folder B (fast scan), B can finish first and start playing, then A finishes
  and overwrites B's queue. The last *tap* is not guaranteed to win — the last *scan to finish* does.
  `playingFolderId` is set synchronously before the launch, so it also ends up inconsistent with the
  queue that actually got installed.
- **Use after onStop/release.** `lifecycleScope` is cancelled at `ON_DESTROY`, not `ON_STOP`. The
  controller is released in `onStop()` via `MediaController.releaseFuture(...)`, but the coroutine
  holds the captured `controller` reference and may call `setMediaItems/prepare/play` on a released
  controller after the activity stopped.

What to do:
- Track the current load `Job` (one field) and cancel it at the start of `playFolder`/`playFile`, so
  a newer request supersedes an older one.
- After the `withContext(Dispatchers.IO)` scan returns, re-check liveness before touching the
  controller: read `controllerState.value` again (don't use a stale captured reference) and bail if
  it is null, and/or ensure the body only runs while the lifecycle is at least STARTED
  (e.g. `repeatOnLifecycle(Lifecycle.State.STARTED)` or a `lifecycle.currentState` check).
- Keep `playingFolderId` consistent with the queue that is actually installed (set/confirm it on the
  branch that wins, not unconditionally before the scan).

Verification:
- `./gradlew :app:compileDebugKotlin`
- Manual: rapidly tap two different folders' Play (one large, one small) and confirm the last tapped
  folder is what plays; background the app mid-scan and confirm no crash/log about a released
  controller.

---

## Prompt 2: Move persisted-setting *writes* off the main thread

`tofix2` moved the hot-path setting *reads* off the main thread, but `Settings.set(...)` still runs
synchronous SQLite writes on the UI thread:
- `MainActivity` settings callbacks (`onThemeChange`, `onLoopChange`, `onReplayGainChange`,
  `onFollowChange`) call `Settings.set...` directly inside `onCheckedChange`/click handlers.
- `Settings.getRoots()` now performs a **write** during the legacy migration, and `getRoots()` is
  called from `onCreate` on the main thread (a disk write during cold start).
- `playFolder`/`playFile` call `loopRepeatMode()` → `Settings.isLoopEnabled()` (a DB read) from
  inside the coroutine after it has resumed on the main thread.

These are all main-thread disk I/O and would trip StrictMode / risk jank.

What to do:
- Route `Settings` writes (and the migration write) onto a background dispatcher (e.g. a small
  `Settings.io` scope or pass a coroutine scope), keeping the in-memory UI state update synchronous
  so the UI stays responsive. Persisted values must still be durable across process death.
- In `playFolder`/`playFile`, compute `loopRepeatMode()` from a cached flag (or read it inside the
  existing `Dispatchers.IO` block) rather than reading the DB on the main thread.
- Keep `Settings` the single owner of persisted state; this is about *where* the I/O runs.

Verification:
- `./gradlew :app:compileDebugKotlin`
- With StrictMode enabled (or by inspection), confirm no disk-write violations from settings toggles
  or from cold start; toggles still persist across a force-stop + relaunch.

---

## Prompt 3: Narrow the FolderCache lock so a recursive scan can't block UI folder loads

`FolderCache` guards every method with `@Synchronized` on the singleton object. `MusicScanner.collect`
(used by "Play this folder") walks the tree by calling `FolderCache.children` for every subfolder,
each call taking the same global lock. While a large recursive collect is running on
`Dispatchers.IO`, a separate UI-driven `FolderBrowser` load (also `FolderCache.children` on IO) of an
*already cached* folder must wait on the same monitor, hurting browse responsiveness during a big
"Play this folder".

What to do:
- Reduce lock contention. Options, pick the simplest that is correct:
  - Lock per (treeUri, parentId) key instead of one global monitor (e.g. a striped/keyed lock), so
    independent folders don't serialize against each other.
  - Or make the common cached-read path lock-light: the SQLite access is already internally
    synchronized, so a fast `isScanned`+`read` may not need the coarse object lock that the
    scan+`store` write path needs.
- Preserve correctness: a miss must still scan-and-store atomically so two callers don't double-store
  the same folder; reads must never observe a partially stored folder.

Verification:
- `./gradlew :app:compileDebugKotlin`
- Manual: start "Play this folder" on a large tree and simultaneously browse other (cached) folders;
  navigation should stay responsive. Confirm no duplicate rows appear in the `children` table after
  concurrent access (cache results remain correct).

---

## Prompt 4: Give feedback when Play finds nothing (and don't drop the selection silently)

`playFolder()` does nothing when `items.isEmpty()`, and `playFile()` does nothing when the index is
out of range — both are silent no-ops. In `togglePlay()`, the file branch clears the selection
(`selectedIndexState.value = null`) *before* `playFile` has confirmed it can play, so on an
empty/unreadable folder the user loses their selection and gets no playback and no message. An
unreadable folder (revoked permission, provider error) surfaces nothing here either.

What to do:
- When a Play action yields no playable items (empty folder, all-unreadable subtree, or a
  `ScanException` during collect), show a brief, localized message to the user (reuse the existing
  error surface / a snackbar / the now-playing error slot) instead of a silent no-op.
- Only clear `selectedIndexState` once playback actually starts, so a failed `playFile` leaves the
  selection intact.
- Keep all user-facing strings in `res/values/strings.xml` (no hardcoded text).

Verification:
- `./gradlew :app:compileDebugKotlin`
- Manual: press "Play this folder" on an empty folder and on a normal folder; confirm a message
  appears in the empty case and the selection is preserved when a file play can't proceed.

---

## Prompt 5: Bound the recursive scan and trim per-item extras memory

`MusicScanner.collect()` recurses with plain stack recursion and no depth guard; a pathologically
deep SAF tree could overflow the stack. Separately, every `MediaItem` produced by `mediaItem()`
carries `EXTRA_PATH_IDS` and `EXTRA_PATH_NAMES` string arrays for its full ancestor chain, so a large
recursive "Play this folder" builds a queue where each of thousands of items duplicates its (often
long) path arrays in a `Bundle` — a real memory cost for big libraries.

What to do:
- Convert `collect` to an iterative walk (explicit work queue/stack) or add a sane depth cap so a
  hostile/deeply nested tree can't crash the app.
- Reduce the per-item extras footprint. Consider whether the full path arrays must live on *every*
  item or whether the consumers in `MainActivity` (follow/navigation, play-folder restore,
  root-removal checks) can be satisfied with a lighter representation (e.g. only what `followPlaying`
  and `playFolderTreeUriOf`/`playFolderIdOf` actually read), without reparsing documentIds as paths.
- Keep the provider-agnostic guarantee: no treating a SAF documentId as a slash-separated filesystem
  path.

Verification:
- `./gradlew :app:compileDebugKotlin`
- Manual: "Play this folder" on a large/deep library still enumerates and plays correctly; follow,
  play-folder restore, and root removal still work. No `StackOverflowError` on a deep tree.

---

## Prompt 6: Stop showing raw encoded URI segments before root names resolve

In `RootsList`, the initial `labeled` state maps each root to `it.lastPathSegment ?: it.toString()`,
then a `LaunchedEffect(roots)` asynchronously replaces those with the real display names from
`MusicScanner.rootNode(...)`. For SAF tree URIs, `lastPathSegment` is the URL-encoded tree documentId
(e.g. `primary:Music` percent-encoded), so on first paint — and on every roots change — the user can
briefly see encoded gibberish before the readable folder name appears.

What to do:
- Avoid showing the raw encoded segment as a transient label. Either decode a reasonable fallback
  (`Uri.decode(lastPathSegment)` and take the part after `:`) or render a neutral placeholder until
  the real name resolves, so the list never flashes percent-encoded text.
- Keep the async name resolution and the case-insensitive sort.

Verification:
- `./gradlew :app:compileDebugKotlin`
- Manual: open the roots home screen with one or more folders and confirm no encoded
  (`%3A`/`primary%3A...`) text appears before the readable names.
