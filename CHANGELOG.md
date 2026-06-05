# Changelog

Newest entries on top.

## Unreleased

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
