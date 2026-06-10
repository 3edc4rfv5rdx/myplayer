# Code & logic audit #6 — findings

Audit of MainActivity, PlayerService, Settings, FolderCache, MusicScanner, AppDb, ReplayGain,
AndroidManifest, strings.xml. Each finding is written as a self-contained prompt for an LLM.

---

## Finding 1 — `deleteBook` runs a recursive SAF delete on the main thread
**Confidence: High. Severity: Medium (UI freeze + ANR dialog; recoverable via "Wait", rare operation).**

**Problem:**
`MainActivity.deleteBook` (MainActivity.kt:707-724) is called directly from the delete dialog's
confirm `onClick` and executes everything synchronously on the main thread:
`DocumentsContract.deleteDocument` is a blocking binder call into the documents provider that
recursively deletes the whole folder — for a large audiobook (hundreds of files) this can take
seconds. It is followed by `FolderCache.invalidateSubtree` + `invalidate` (SQLite queries/deletes
under the write lock), also on the main thread. A slow SD card or MTP-backed provider makes an ANR
realistic; the UI freezes with the dialog just dismissed.

**Solution Prompt:**
Move the body of `deleteBook` into `lifecycleScope.launch { withContext(Dispatchers.IO) { ... } }`:
do `stopIfPlayingUnder(folder)` on the main thread first (it touches the controller), then perform
`deleteDocument`, `Settings.clearBook`, and both FolderCache invalidations on `Dispatchers.IO`, then
hop back to the main thread to set `errorState` on failure and bump `rescanTickState` on success.
While the delete is in flight, guard against double-fire (e.g. ignore a second confirm). Keep the
"last request wins" idiom consistent with `playbackLoadJob` if needed.

---

## Finding 2 — Lexicographic sort breaks numeric chapter/track order
**Confidence: High. Severity: Medium (core UX for audiobooks).**

**Problem:**
`MusicScanner.children` sorts folders and files with `sortBy { it.name.lowercase() }`
(MusicScanner.kt:77-78). Plain lexicographic order puts `Chapter 10.mp3` before `Chapter 2.mp3`,
and `CD10` before `CD2`. Books play in queue order (no shuffle), and the queue is built from these
sorted listings, so any book whose files are numbered without zero-padding plays chapters out of
order. The browser shows the same wrong order. `lowercase()` is also default-locale sensitive
(Turkish dotless-i), a minor secondary issue.

**Solution Prompt:**
Replace the sort with a natural-order comparator: split names into alternating digit/non-digit
runs, compare digit runs numerically (as `Long`, falling back to string compare on overflow) and
non-digit runs case-insensitively with `Locale.ROOT`. Apply it to both `folders` and `files` in
`MusicScanner.children`. Keep it as a single shared comparator (e.g. in `MusicScanner`). Note:
`FolderCache` persists listings in insertion order, so already-cached folders keep the old order
until invalidated — mention in CHANGELOG that "Rescan music" applies the new ordering to existing
caches (no DB schema change needed).

---

## Finding 3 — Stale `playingFolderId` after a failed/empty scan resumes the wrong folder
**Confidence: High. Severity: Medium-Low.**

**Problem:**
`playFolder` sets `playingFolderId = folder.documentId` *before* the scan runs
(MainActivity.kt:587); `playFile` does the same (MainActivity.kt:685). If the scan then fails
(`ScanException` → "Can't read this folder") or yields nothing ("Nothing to play here"),
`playingFolderId` keeps pointing at the folder that never started. Scenario: folder A is paused in
the queue; the user opens folder B and taps Play; the scan fails and the error shows; the user taps
the big Play button again. In `togglePlay`, `dir.documentId != playingFolderId` is now false, so it
falls through to `controller.play()` and silently resumes folder A's queue while the user stands in
folder B — and the "Can't read this folder" label stays on screen (the `error` slot has priority
over the title in `NowPlaying`) even though A is audibly playing.

**Solution Prompt:**
Assign `playingFolderId` only when a queue actually starts: capture the previous value, set the new
one just before (or inside) `startQueue` on the success path of `playFolder`/`playFile`, and leave
it untouched (or restore the previous value) when the scan throws or produces no playable items.
Verify `togglePlay`'s branches still behave: a failed Play in folder B followed by another tap
should retry folder B (`playFolder`), not resume folder A.

---

## Finding 4 — `playingAbookState` not reconciled on controller connect (stuck-locked shuffle after process death)
**Confidence: High. Severity: Low (narrow path).**

**Problem:**
`restoreUiState` restores `playingAbookState` from the saved bundle (MainActivity.kt:427), but the
connect callback in `onStart` reconciles only shuffle, speed, and `playingFolderKeyState` — never
`playingAbookState`. After process death while a book was the live queue, the activity state is
restored (`playingAbook = true`) but the service died with the process, so the new player has an
empty queue. Result: the shuffle switch is disabled (`enabled = !playingAbook`) with nothing
playing, until the user happens to leave a folder (`clearNowPlayingIfStopped` resets it) or starts
a new queue. The connect callback already computes `liveIsBook` (MainActivity.kt:373-375) but only
uses it for the shuffle decision.

**Solution Prompt:**
In the `onStart` connect callback, after computing `liveIsBook`, reconcile the flag:
`playingAbookState.value = c.mediaItemCount > 0 && liveIsBook && c.playbackState != Player.STATE_ENDED`.
The `STATE_ENDED` guard matters: a finished-but-still-loaded book queue saved `playingAbook = false`
in the bundle, and the reconcile must not flip it back to true (which would re-lock shuffle and
re-show the book progress for a dead queue).

---

## Finding 5 — Ended queue can be resurrected as an untracked book
**Confidence: Medium (exact Media3 play-in-ENDED semantics should be verified). Severity: Low.**

**Problem:**
When a book finishes, both `STATE_ENDED` handlers fire: the service clears the resume point and
nulls `bookFolderKey` (PlayerService.kt:111-116), the activity clears the now-playing UI and keys
(MainActivity.kt:353-359) — but the media items stay loaded. Two paths can restart that dead queue:
(a) the media-notification Play button — Media3's `Util.handlePlayButtonAction` seeks to the default
position when ended, restarting the book from file 1 with **no position tracking** (service
`bookFolderKey` is null), shuffle unlocked in the UI, and the old book speed still applied;
(b) the big Play button on the home screen (`dir == null` → the `mediaItemCount > 0 → play()` branch
in `togglePlay`, MainActivity.kt:564) — plain `player.play()` in `STATE_ENDED` likely does nothing
at all (stays ended), i.e. a dead button.

**Solution Prompt:**
First verify both behaviors on a device. Then: in `togglePlay`, treat an ended queue as no queue —
change the resume branch to `controller.mediaItemCount > 0 && controller.playbackState != Player.STATE_ENDED`
so a Play inside a folder restarts via `playFolder` (which re-installs book mode, speed, and
tracking correctly) and Play at home with an ended queue does nothing instead of half-working. For
the notification path, decide explicitly: either accept the untracked restart (document it), or
have the activity's `STATE_ENDED` handler also `clearMediaItems()` so the dead queue can't be
resurrected from the shade (note this also removes the notification for music that ended with
repeat off — a deliberate trade-off to state in CHANGELOG).

---

## Finding 6 — Deleting a folder doesn't stop playback when queue items (not the current track) live under it
**Confidence: High. Severity: Low.**

**Problem:**
`stopIfPlayingUnder` (MainActivity.kt:728-743) checks only `controller.currentMediaItem`. If the
user plays a *parent* folder recursively (queue spans several subfolders) and then deletes one
subfolder whose files are in the queue but not currently playing, the queue keeps dangling
`content://` URIs. When playback reaches one, ExoPlayer raises a source error and stops — surfaced
as a cryptic `onPlayerError` ("ERROR_CODE_IO_FILE_NOT_FOUND: ...") instead of anything actionable.

**Solution Prompt:**
In `stopIfPlayingUnder` (or a sibling helper called from `deleteBook`), when the current track is
*not* under the deleted folder, walk the queue (`controller.getMediaItemAt(i)` over
`mediaItemCount`) and remove items whose `EXTRA_PATH_IDS` contains `folder.documentId`
(`controller.removeMediaItem(i)`, iterating backwards). Keep the existing full-stop behavior when
the current track itself is under the folder. Cheap, no scan needed — the extras already carry the
ancestor chain.

---

## Finding 7 — `playFile` swallows `ScanException`, showing "Nothing to play here" for an unreadable folder
**Confidence: High. Severity: Very low (message consistency; same class as tofix5 Finding 5).**

**Problem:**
`playFile` loads the folder listing with
`runCatching { FolderCache.children(...) }.getOrDefault(emptyList())` (MainActivity.kt:688-690).
An unreadable folder becomes an empty list, `index !in files.indices` triggers, and the user sees
`R.string.nothing_to_play` — while the browser and `playFolder` show `R.string.folder_unreadable`
for the same root cause.

**Solution Prompt:**
Catch `ScanException` explicitly in `playFile` (mirror `playFolder`'s try/catch around the
`withContext(Dispatchers.IO)` block) and show `R.string.folder_unreadable`; keep the
`nothing_to_play` message for a genuinely out-of-range index.

---

## Finding 8 — Per-row `Settings.isAbook` DB reads during LazyColumn composition
**Confidence: High. Severity: Low (perf/jank, worst on first scroll).**

**Problem:**
`FolderBrowser` calls `Settings.isAbook(context, ...)` inside the `items` composition for every
folder row (MainActivity.kt:1084-1086). `Settings.get` runs a synchronous SQLite `rawQuery` on the
main thread for every key not yet in the in-memory cache — so first composition/scroll of a listing
with many subfolders issues one main-thread DB query per row, inside frame rendering. Each query is
sub-millisecond on a warm device, but it's unbounded work in composition and contrary to the
project's own "DB off the main thread" direction (tofix5 Finding 1/2).

**Solution Prompt:**
Resolve book flags alongside the listing load: in the `LaunchedEffect` that fills `contents`
(already on `Dispatchers.IO`), map `contents.first` to a `Map<String, Boolean>` (or
`Set<String>` of book documentIds) via `Settings.isAbook`, store it in a state next to `contents`,
and read the flag from that map in the row composable. No new Settings API needed; the per-key
cache then warms off-thread.

---

## Finding 9 — CLAUDE.md architecture docs have drifted from the code
**Confidence: High. Severity: Low (docs only, but actively misleading for future LLM sessions).**

**Problem:**
Project CLAUDE.md describes a previous generation of the app:
- "The user remembers a root music folder once… the SAF picker is the browser… There is
  intentionally no in-app folder browser" — the app now keeps an ordered *list* of roots
  (`Settings.getRoots`, home screen `RootsList`) and has a full in-app `FolderBrowser` backed by
  `FolderCache`; the SAF picker is only used to add a root.
- "ReplayGain + GainAudioProcessor — custom Media3 `AudioProcessor` inserted via a
  `DefaultAudioSink` (float output disabled…)" — `GainAudioProcessor` no longer exists; ReplayGain
  is applied via player volume (attenuation) + `LoudnessEnhancer` (boost) in `PlayerService`.
- No mention of major current features: audiobook mode (per-folder flag, resume positions,
  per-folder speed, book deletion), `FolderCache`, follow-playing, multiple roots.

**Solution Prompt:**
Rewrite the stale sections of CLAUDE.md to match the code: "What this is" (multiple roots, in-app
browser, audiobook mode), "Folder selection" (SAF picker only adds roots; browsing is in-app via
`FolderCache`/`MusicScanner`), "ReplayGain" (volume + LoudnessEnhancer, no audio-processor pipeline),
and the supporting-files list (add `FolderCache`, `AppDb`; describe `Settings` as roots/toggles/
per-folder book state). Keep it concise; don't document what git history already covers.

---

## Nit — `saveTick` keeps rescheduling every 10 s while idle
`PlayerService`'s `saveTick` re-posts itself unconditionally (PlayerService.kt:54-59), waking the
main looper every 10 s even when playback is paused/stopped for hours with the service alive. The
`isPlaying` check makes it a no-op, so this is battery-trivia, not a bug. Optional: start the tick
on `onIsPlayingChanged(true)` and remove callbacks on `false`, instead of a permanent heartbeat.

---

## Summary

| # | Finding | File | Severity |
|---|---------|------|----------|
| 1 | `deleteBook` recursive SAF delete on main thread | MainActivity.kt | Medium |
| 2 | Lexicographic sort breaks chapter order (needs natural sort) | MusicScanner.kt | Medium |
| 3 | Stale `playingFolderId` after failed scan resumes wrong folder | MainActivity.kt | Medium-Low |
| 4 | `playingAbookState` not reconciled on connect | MainActivity.kt | Low |
| 5 | Ended queue resurrected as untracked book (shade / home Play) | MainActivity / PlayerService | Low |
| 6 | Delete doesn't purge queued items under the deleted folder | MainActivity.kt | Low |
| 7 | `playFile` shows "nothing to play" for unreadable folder | MainActivity.kt | Very low |
| 8 | Per-row `isAbook` DB reads in composition | MainActivity.kt | Low |
| 9 | CLAUDE.md drifted from the code | CLAUDE.md | Low (docs) |
