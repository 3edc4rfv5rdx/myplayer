# Changelog

Newest entries on top.

## Unreleased

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
