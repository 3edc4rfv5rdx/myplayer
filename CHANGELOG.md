# Changelog

## [Unreleased]

### Added
- Initial Android scaffold (Kotlin, Jetpack Compose, Media3/ExoPlayer).
- In-app folder browser over a SAF tree using DocumentsContract queries (subfolders + audio files).
- Tap a file to select (highlighted); the large center Play button plays the selection; Next on the right.
- Selecting a file plays it first, then the rest of the folder shuffled (no repeats per cycle).
- "Play this folder": plays everything under it recursively, shuffled; the walk also fills the cache.
- Persistent folder-listing cache and settings unified in one SQLite database (`app.db`); Rescan clears the listing cache.
- Settings screen (filled Settings button): Change root, Rescan, theme switch (System/Light/Dark),
  ReplayGain toggle, and About with version + build number.
- Now-playing shows the track name plus its folder path (relative to root) on a separate line.
- Background playback and shade controls via `MediaSessionService`.
- Theme switch (System/Light/Dark); full uninstall (no backup, no fragile user data); playback errors surfaced on screen.
- Shared build setup with `../memlists`: AGP 9.1.1 / Kotlin 2.3.0 / Gradle 9.3.1,
  version catalog, release signing, ABI splits, APK rename, release/install scripts.

### Notes
- ReplayGain is temporarily inactive: the custom audio pipeline broke playback and was removed;
  `GainAudioProcessor`/`ReplayGain` are kept for a safer re-introduction later.
