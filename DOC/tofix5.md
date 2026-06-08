# Code Audit #5 — MyPlayer

Manual, line-by-line audit of all seven `.kt` files + `AndroidManifest.xml` (2026-06-08, post
`tofix4`). Every claim below was checked against the actual code, not pattern-matched. **The
codebase is in good shape: no crashes, no correctness bugs in the ReplayGain / cache / playback
logic.** The findings are minor — mostly main-thread I/O and small state desyncs. Each carries a
**Confidence** and **Severity** so you can ignore the noise.

For the record, things I verified and found *correct* (so they don't get re-flagged):
- ReplayGain transition logic is sound: `onMediaItemTransition` resets `currentTrackGainDb=null`
  and re-applies, so no stale boost/attenuation leaks between tracks. Untagged files play at 1.0.
- `parseDb` handles `"-6.48 dB"`, `"-6.48"`, `"-6.48dB"`, `"+3.21 dB"` — all parse; bad input → null.
- `FolderCache` locking is correct: shared read lock + per-(tree,parent) `keyLock` lets independent
  folders scan in parallel while `clear()`/`clearRoot()` stay exclusive. No double-scan, no dup rows.
- `collectAudio` is iterative (no stack overflow); "last Play wins" holds via `playbackLoadJob.cancel()`.
- `removeRoot` correctly stops playback only when the removed root is the one playing.
- No listener leak across `onStart`/`onStop` (fresh controller each cycle, released in `onStop`).

---

## Finding 1 — `enterRoot()` does a SAF provider query on the main thread
**Confidence: High. Severity: Medium. — FIXED.** `enterRoot` now shows the folder with a fallback
label immediately and resolves the real `rootNode` name on `Dispatchers.IO`, applying it only if the
user is still at that root's top folder.

**Problem:**
`MainActivity.enterRoot()` (MainActivity.kt:357) runs
`pathState.value = listOf(MusicScanner.rootNode(this, treeUri))` synchronously on the UI thread.
`rootNode()` → `queryName()` → `contentResolver.query(...)` (MusicScanner.kt:38-46) is a blocking
SAF round-trip to the document provider. For a slow/remote provider (USB OTG, network SAF, a
just-woken SD card) this blocks the main thread and can jank or ANR. Note `RootsList` already does
the *same* `rootNode` call off-thread via `withContext(Dispatchers.IO)` (MainActivity.kt:639) — so
this is an inconsistency, not a missing pattern.

**Solution Prompt:**
Move the `rootNode` resolution off the main thread. Either set the path immediately with a cheap
fallback `Node` (documentId from `DocumentsContract.getTreeDocumentId`, name from
`fallbackRootLabel`) and refine the display name from a `lifecycleScope.launch(Dispatchers.IO)`, or
launch the whole `enterRoot` body on IO and post the result back. Mirror the off-thread approach
already used in `RootsList`'s `LaunchedEffect`.

---

## Finding 2 — Settings/DB first-read happens on the main thread
**Confidence: High. Severity: Low.**

**Problem:**
`onCreate` reads `Settings.getThemeMode`, `isFollowEnabled`, `getRoots` (MainActivity.kt:152-154),
and the initial composition reads `isReplayGainEnabled`, `isLoopEnabled`, `isFollowEnabled`
(MainActivity.kt:182-184) — all on the main thread. On a cold first launch the first read per key
calls `AppDb.db()` → `getWritableDatabase`, which opens the file and may run `onCreate`/`onUpgrade`
(disk I/O), then `rawQuery`. The in-memory cache makes this a one-time cost per key, but it is still
synchronous disk I/O on the UI thread (StrictMode would flag it). Low impact because the DB is tiny
and local; noting it for completeness.

**Solution Prompt:**
Warm the `Settings` cache once on a background thread before/early in `onCreate` (a small
`prefetch(context)` that reads the handful of known keys on the `writer` executor or a one-shot IO
coroutine), then have the main-thread getters hit only the cache. Alternatively accept it as
negligible and document that `Settings`/`AppDb` first-touch is main-thread by design.

---

## Finding 3 — Now-playing title/path blanks on *every* "Up", not just at home
**Confidence: Medium. Severity: Low (UX). — FIXED.** `goUp` no longer bumps `clearTitleTick` on the
intra-tree branch; labels persist while a track plays and clear only via `goHome`.

**Problem:**
`goUp()` bumps `clearTitleTickState` on **both** branches — when dropping one level inside the tree
*and* when returning home (MainActivity.kt:373-382). The `NowPlaying` `LaunchedEffect(clearTitleTick)`
then clears `title`/`path` (MainActivity.kt:935-940). So navigating up one folder — while still in
the browser (not `atHome`) and still actively playing — wipes the track title and folder path. They
only reappear on the next `onMediaMetadataChanged` (i.e. the next track), while the seek bar and
times keep showing (their visibility doesn't depend on `clearTitleTick`). Result: an inconsistent
state where the bar is visible but the title is blank mid-track. The label-clearing was presumably
intended only for the home transition.

**Solution Prompt:**
Decide the intended behavior. If labels should persist while a track plays regardless of navigation,
only bump `clearTitleTick` in the `goHome()` path, not on intra-tree `goUp()`. If labels should
truly clear, also gate the seek bar on the same condition so the bar and title hide together. Either
way, make the bar's and title's visibility conditions consistent.

---

## Finding 4 — Shuffle state can desync from the player after process death
**Confidence: Medium. Severity: Low. — FIXED.** `onStart` reconciles on connect: a live queue's
`shuffleModeEnabled` wins, otherwise the UI's `shuffleState` is pushed to the controller.

**Problem:**
`shuffleState` is restored from the saved bundle (default `true`) in `restoreUiState`
(MainActivity.kt:322). After a full process death the recreated `PlayerService` builds a fresh
`ExoPlayer` whose `shuffleModeEnabled` defaults to `false` (onCreate sets `repeatMode` but never
`shuffleModeEnabled`, PlayerService.kt:54-55). Nothing re-syncs the player to the restored UI value
until the next `play`/toggle, so the switch can read "on" while the engine is "off". Self-heals on
the next `startQueue` (which sets `shuffleModeEnabled = shuffleState.value`), so it's only a transient
visual/behavioral mismatch on the rare process-death path.

**Solution Prompt:**
On controller connect in `onStart`, push the UI's `shuffleState`/loop intent to the controller
(`c.shuffleModeEnabled = shuffleState.value`) once the controller is live, or read the controller's
actual `shuffleModeEnabled` back into `shuffleState` so UI and engine agree from the first frame.

---

## Finding 5 — Unreadable "Play this folder" reports "Nothing to play here"
**Confidence: High. Severity: Very low (cosmetic). — FIXED.** `collectAudio` now rethrows the
`ScanException` when the *target* folder is unreadable (subfolders still skipped); `playFolder`
catches it and shows `folder_unreadable`.

**Problem:**
If the *target* folder itself can't be read, `collectAudio` catches the `ScanException` on the very
first stack entry and `continue`s, ending with an empty list (MusicScanner.kt:94-98). `playFolder`
then shows `R.string.nothing_to_play` (MainActivity.kt:454-456). Meanwhile browsing that same
unreadable folder shows `R.string.folder_unreadable` (MainActivity.kt:832-835). So the same root
cause surfaces two different messages depending on entry point. Harmless, just inconsistent.

**Solution Prompt:**
Optional: have `collectAudio` distinguish "root folder unreadable" (rethrow / signal) from
"some subfolder skipped" so `playFolder` can show `folder_unreadable` when the chosen folder can't be
read at all. Low priority — only matters when a permission is revoked while the folder is on screen.

---

## Nit — dead initializer in `PlayerService`
**Confidence: High. Severity: Trivial.**

`private var replayGainEnabled = false` (PlayerService.kt:37) is immediately overwritten by
`replayGainEnabled = Settings.isReplayGainEnabled(this)` in `onCreate` (line 41). Harmless; could
initialize once at the declaration via a lazy/`onCreate`-only assignment to avoid the throwaway value.

---

## Summary

| # | Finding | File | Severity |
|---|---------|------|----------|
| 1 | SAF query on main thread in `enterRoot` | MainActivity.kt | Medium |
| 2 | Settings/DB first-read on main thread | MainActivity / AppDb / Settings | Low |
| 3 | Title blanks on every Up navigation | MainActivity.kt | Low (UX) |
| 4 | Shuffle desync after process death | MainActivity / PlayerService | Low |
| 5 | "Nothing to play" vs "unreadable" message split | MusicScanner / MainActivity | Very low |
| — | Dead `replayGainEnabled = false` initializer | PlayerService.kt | Trivial |
