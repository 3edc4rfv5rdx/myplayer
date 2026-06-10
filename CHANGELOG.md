# Changelog

Newest entries on top.

## Unreleased

- Settings: renamed the default-speed label to "Abook default speed" to make clear it applies to
  audiobook folders only.

- Fix: leaving a book's folder (navigating Up/Home) while the book is stopped now ends book mode
  instead of keeping the paused book as the live queue. The shuffle switch is freed for music again,
  and the book's saved position still lets re-entering its folder and pressing Play resume it. Also,
  a book that played to its end no longer leaks its forced shuffle-off into the shuffle preference
  when the app is reopened.

- Cleanup: removed two unnecessary safe calls in PlayerService (the player reference is non-null in
  that listener); no behavior change, just silences the build warnings.

- Fix: deleting a book folder now also drops the cached listings of that folder and everything under
  it, instead of leaving stale subtree rows behind until the next full Rescan.

- Fix: stopping playback by removing the playing root folder, or by deleting the folder the playing
  track lives in, now also clears the internal playing-folder key, so no stale per-folder speed key
  lingers after the queue is gone.

- Fix: a fully finished audiobook now correctly starts over next time. Previously, if the app was
  swiped away or killed after a book reached its end, the player re-saved the end position over the
  just-cleared resume point, so reopening the book jumped to its final seconds instead of restarting.

- Fix: the shuffle preference is no longer silently turned off after an activity recreation (e.g.
  screen rotation) that happens while a book plays. A book forces shuffle off internally; that value
  was being read back as the user's setting, so the next music after the book ended played unshuffled.

- Fix: the shuffle toggle and the audiobook checkbox no longer act on an unrelated live queue based
  on the folder shown in the browser. Shuffle is now locked precisely while a book is the queue
  actually playing (so wandering to another folder can't re-enable it and scramble the book), and
  marking a folder as a book is a pure persisted setting that takes effect on its next start instead
  of silently dropping shuffle on whatever is currently playing.

- Fix: plain music now always plays at 1.0 speed instead of inheriting the global default or the
  previously played book's speed; per-folder speed applies only in audiobook mode.

- Playback speed (per folder): a speed button by the playback controls shows the current rate
  (e.g. x1.0) and opens a 0.5–3.0 slider (0.05 steps) that applies live as you drag. The chosen speed
  is remembered per folder and re-applied when that folder plays. The button is an audiobook feature:
  it is active only while an audiobook plays, and stays disabled (grey) for plain music.
  - Settings has a "Default speed" button (opening the same dialog) used for folders without a speed
    of their own.
  - The bottom controls were regrouped into vertically-centred pairs (shuffle/abook on the left,
    next/speed on the right) so the gap inside each pair sits on the play button's mid-height; the
    hidden checkbox/speed slots keep their size so shuffle and next don't shift.
- Buttons are now filled (solid background) instead of outlined, so their enabled vs disabled state
  is obvious at a glance (the theme picker and speed button included).

- Audiobook mode: an "abook" checkbox under the shuffle toggle marks the open folder as a book
  (remembered per folder). A book plays sequentially with shuffle disabled and looping off, and its
  position is remembered — the current file and offset are saved as you listen (on pause, on file
  change, periodically, and in the background) and restored on the next open, rewound 15s for
  context. Finishing a book clears its resume point so it starts over.
  - While a book plays, the now-playing area shows its progress: "File N/M" on the left with the
    percent on the right, over a thin bar.
  - Book folders are marked with a 📖 icon (instead of 📁) in the browser listing.
  - Delete a folder and all its files from storage by long-pressing it in the browser: a dialog
    asks to tick "Confirm" before the Delete button enables. Playback from that folder is stopped
    first, and its remembered book state is cleared.
  - Dialog buttons now have a filled (colored) background.
  - The abook checkbox sits below the shuffle toggle without shifting it.
  - Fixed the saved position being lost on exit: clearing the queue no longer counts as the book
    finishing, and the final position write is flushed to disk before the service is torn down.
  - Reaching the end of a book clears the now-playing labels, bar, and progress, and resets the
    saved position (no longer re-saved by the pause that accompanies the end).

- Audit (tofix5) fixes:
  - Entering a root folder no longer blocks the main thread on a SAF name query: the folder shows
    instantly with a fallback label and its real display name resolves in the background.
  - The now-playing name, path, and playback bar now behave as one: while a track is playing they
    stay visible as you browse folders; navigating "Up" away from a stopped or paused track clears
    all three together (resuming brings them back), and they also clear when the playing folder is
    removed or you reach the roots screen.
  - The shuffle toggle and the player can no longer disagree after the app process is recreated: on
    reconnect a live queue's shuffle state wins, otherwise the toggle's value is pushed to the player.
  - "Play this folder" on a folder whose storage is unavailable / permission was revoked now shows
    the "can't read this folder" message instead of the misleading "nothing to play here".
  - Internal: documented the `PlayerService.replayGainEnabled` default; no behavior change.

## v0.3.20260608+62

- The selected/playing row in the file list now has rounded corners, matching the roots list.
- The app icon's play triangle has its sharp corners shaved off.
- Now-playing shows a thin playback progress bar under the track name, with the elapsed time and the
  track duration at its edges. Tap or drag the bar to seek within the track.
- Exit button on the roots screen (top-left): stops playback, tears down the player service, and
  closes the app.

## v0.3.20260608+58

- Audit (tofix4) fixes:
  - `Settings.addRoot`/`removeRoot` now serialize their read-modify-write on a dedicated lock, so
    concurrent add/remove can no longer lose an update (the cache lock only made each get/set atomic,
    not the compound operation).
  - `AppDb` now handles a database downgrade (an older APK installed over a newer one): it resets the
    rebuildable cache tables and keeps `settings`, instead of crashing on first DB access.

## v0.3.20260605+57

- The app is now locked to portrait orientation; the screen no longer rotates.

- Audit (tofix3) fixes:
  - Playback loads are now lifecycle-safe and race-free: a newer Play cancels the previous scan (the
    last tap wins, not the last scan to finish), and the controller is re-checked after scanning so
    a load can't touch a controller released in onStop.
  - Persisted-setting writes moved off the main thread: `Settings` keeps an in-memory cache and a
    single-thread background writer, so toggles and the legacy migration no longer block the UI, and
    read-modify-write on the roots list stays race-free.
  - `FolderCache` locks per (tree, parent) instead of globally, so a large "Play this folder" scan no
    longer blocks UI loads of other cached folders; Rescan/root-removal stay exclusive.
  - Play now reports "Nothing to play here" on an empty/unreadable folder instead of doing nothing,
    and a file selection is cleared only once playback actually starts.
  - The recursive collect is now iterative (no stack overflow on deep trees) and computes each
    folder's path id/name arrays once per folder instead of once per file.
  - The roots list no longer briefly flashes the raw `primary:`-style tree id before names resolve.

- Audit (tofix2) fixes:
  - ReplayGain: removed dead `toLinear`/`MAX_LINEAR`/`PREAMP_DB`; the dB→volume/millibel math and
    the +12 dB cap now live solely in `ReplayGain` and are reused by the player (no duplication).
  - Performance: persisted toggles are no longer read from SQLite on hot paths — `PlayerService`
    caches the ReplayGain flag and `MainActivity` caches the Follow flag, so per-track / per-metadata
    callbacks no longer touch the DB on the main thread.
  - The now-playing connection error now clears on a successful reconnect instead of lingering.
  - The legacy single-folder setting is migrated to the roots list once and persisted, instead of
    being re-migrated on every read.
  - Removing the root that is currently playing now also clears the now-playing title/path.
  - `settings` table is created with `IF NOT EXISTS` (also on upgrade) so it can never be dropped.
  - The playing track is now highlighted regardless of the Follow setting; Follow gates only the
    auto-navigation to its folder.
  - A diagnostic is logged when `LoudnessEnhancer` is unavailable (ReplayGain boost becomes a no-op).

## v0.3.20260605+54

- The roots home list now highlights the selected folder: tapping a folder both opens it and marks
  its row selected, and the highlight stays after navigating back to the list. It follows the
  playing track's root and survives screen rotation.
- The Play button's triangle now has rounded corners.

- Docs: README updated to match the app — multiple music folders with add/remove instead of a single
  changeable root, Media3 shuffle mode with a random start index (not a pre-shuffled playlist), and
  the Repeat all / Follow playing track / Shuffle controls.
- Release tooling: `12-SamsRELEASE.sh` now runs under `set -e`, fails with a clear message when no
  physical device is connected (instead of a broken `adb -s` call), warns and uses the first when
  several are connected, accepts an explicit serial argument, and quotes the device serial.
- Release tooling: `22-RelUpload.sh` now resolves its own directory instead of a hardcoded path,
  falls back to the matching ABI only (`*-arm64-v8a.apk` / `*-universal.apk`) so a missing asset
  fails fast with the list of available APKs instead of uploading another ABI under the wrong name,
  and cleans up its temp changelog via an EXIT trap even on failure.
- Build: added `data_extraction_rules.xml` (referenced from the manifest) that excludes everything
  from both cloud backup and device-to-device transfer, consistent with `allowBackup=false`, since
  the app's SAF roots/settings/cache are useless without the matching persisted URI permissions.
  Clears the Android 12+ DataExtractionRules lint warning.
- Security: the playback service is no longer exported. It only hosts this app's local media
  session, and the in-app MediaController connects in-process via SessionToken(ComponentName),
  so other apps no longer need to be able to start or bind to it.
- Build: opt into Media3's `@UnstableApi` explicitly on the few methods that use it
  (`onAudioSessionIdChanged`, `onMetadata`, `parseTrackGainDb`), clearing the
  `UnsafeOptInUsageError` lint errors without a global suppression or a lint baseline.
- SAF read failures (revoked permission, unavailable USB/cloud provider, null cursor) are no longer
  treated as an empty folder. The scanner now raises a distinct error instead of returning empty, so
  a transient failure is never cached as a scanned-but-empty folder. The browser shows a clear
  message with a Retry button near the list, and "Play this folder" skips unreadable subfolders
  instead of aborting the whole playlist.
- The ReplayGain toggle now takes effect on the current track immediately instead of only from the
  next track/metadata event. The Settings switch sends a custom session command to the player
  service, which re-applies the gain at once (resetting volume and the loudness effect when off).
- Removing a root folder while it is playing now stops playback and clears the queue first, so the
  app doesn't keep playing content URIs whose permission was just released. Removing a different
  root leaves the current playback alone. Removal now clears only that root's cached listings
  instead of the whole cache.
- Activity recreation (rotation, returning from background) no longer drops you back to the roots
  home screen: the browser location (current root, folder path, selection, screen, shuffle) is now
  saved and restored. The starting folder id is also recovered from the live playing item's extras
  on reconnect, so pressing Play in the same folder resumes the running playlist instead of
  rebuilding it from a new random track.
- Navigation no longer treats a SAF document id as a slash-separated path. Each playable track now
  carries its root tree URI and ancestor folder chain (ids + names) in its MediaItem extras,
  captured during scanning. "Follow playing track" and the subtitle path use that data instead of
  splitting the document id, so providers with non-hierarchical ids work and never get a fake path;
  if the data is missing the track is only highlighted where found, without wrong navigation.
- Folder cache is now keyed by (root tree URI, parent document id) instead of document id alone,
  so multiple roots or document providers with colliding document ids can no longer return each
  other's contents. The cache database is bumped to version 2; on upgrade only the cache tables
  are recreated — saved roots, settings and theme are preserved (a previous bug dropped them).
- Removing a root folder now asks for confirmation first.
- The roots list is sorted alphabetically by folder name.
- Follow playing track (toggle in Settings, on by default): on each track change the browser
  jumps to the playing file's folder and scrolls it to the middle of the list, highlighting it.
  Does not pull you away from the roots home screen.

## v0.3.20260604+42

- Multi-root: the app now keeps a list of music folders instead of a single root. The home
  screen lists them on a distinct background; tap a folder to browse it, and "Play this folder"
  plays a whole root recursively. Add folders with the "Add folder" button and remove them with
  the ✕ on each row (releasing its permission). The roots list itself is not playable — only the
  folders inside it. A previously remembered single root is migrated into the list automatically.
- Replaced text labels with icons on the transport/navigation buttons: Play/Pause (the Play
  triangle carries a cut-out note, matching the launcher icon), Next, and the folder Up button.

## v0.2.20260603+36

- New launcher icon: teal circle on white with a black play-triangle carrying a teal eighth-note
  (generated by `make_icon.py`; preview in `icon-preview.png`).
- Browser: Settings button replaced with a gear icon. Settings: "Rescan" renamed to "Rescan
  music"; tighter spacing between the Repeat and ReplayGain toggles.
- Now-playing: removed the "Nothing playing" placeholder (blank when idle) and the divider above
  the controls; path and title sit in their own fixed-height slots (title wraps to 2 lines) so a
  long name no longer shoves the list.
- Settings: "Change root" button renamed to "Change music root".
- Settings: "Repeat all" toggle (persisted, on by default); off stops at the end of the
  folder/list instead of looping. Applies live to current playback.
- Shuffle switch (+ shuffle icon) on the main screen (left of Play, on by default, not persisted).
  Uses ExoPlayer's built-in shuffle, so toggling reorders the playing queue live; off plays in scan
  order.

## v0.1.20260602+24

- License: GPLv3. README build section trimmed (dropped sibling-project and signing-path notes).
- Release tagging/upload scripts (`20-MakeTag`, `21-PushTag`, `22-RelUpload`) ported from the
  sibling project; changelog heading switched to `## Unreleased` to match the shared scripts.
- App launcher icon.
- Browser header: "Play this folder" on the left, "Up" on the right.
- ReplayGain (toggle): rough loudness leveling via player volume (attenuate) + LoudnessEnhancer
  (boost), without touching the render pipeline.
- Play with no selection plays the current folder (random); a different folder starts fresh,
  the same paused folder resumes.
- Now-playing shows the folder path (relative to root) above the track name.
- Settings and folder-listing cache unified in one SQLite database (`app.db`).
- Settings screen: Change root, Rescan, theme switch (System/Light/Dark), ReplayGain toggle,
  and About with version + build number.
- Selecting a file plays it first, then the rest of the folder shuffled (no repeats per cycle).
- "Play this folder": plays everything under it recursively, shuffled; the walk also fills the cache.
- Persistent folder-listing cache with a Rescan action.
- In-app folder browser over a SAF tree (DocumentsContract): subfolders + audio files,
  tap a file to select (highlighted), large center Play, Next on the right.
- Background playback and shade controls via `MediaSessionService`.
- Day/night theme switch; full uninstall (no backup, no fragile user data); playback errors on screen.
- Initial Android scaffold (Kotlin, Jetpack Compose, Media3/ExoPlayer); shared build setup with
  `../memlists`: AGP 9.1.1 / Kotlin 2.3.0 / Gradle 9.3.1, version catalog, release signing,
  ABI splits, APK rename, release/install scripts.
