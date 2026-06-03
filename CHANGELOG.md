# Changelog

Newest entries on top.

## Unreleased

- Shuffle switch (+ shuffle icon) on the main screen (left of Play, on by default, not persisted).
  Uses ExoPlayer's built-in shuffle, so toggling reorders the playing queue live; off plays in scan
  order.
- Settings: "Repeat all" toggle (persisted, on by default); off stops at the end of the
  folder/list instead of looping. Applies live to current playback.
- Settings: "Change root" button renamed to "Change music root".
- Now-playing: removed the "Nothing playing" placeholder (blank when idle) and the divider above
  the controls; path and title sit in their own fixed-height slots (title wraps to 2 lines) so a
  long name no longer shoves the list.
- Browser: Settings button replaced with a gear icon. Settings: "Rescan" renamed to "Rescan
  music"; tighter spacing between the Repeat and ReplayGain toggles.

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
