# Changelog

## [Unreleased]

### Added
- Initial Android scaffold (Kotlin, Jetpack Compose, Media3/ExoPlayer).
- In-app folder browser over a SAF tree using DocumentsContract queries (subfolders + audio files).
- Tap a file to select (highlighted); the large center Play button plays the selection; Next on the right.
- "Play this folder": plays everything under it recursively in a pre-shuffled order (no repeats per cycle).
- Persistent folder-listing cache in SQLite (`cache.db`) with a Rescan action.
- Settings screen (filled Settings button): Change root, Rescan, and About with version + build number.
- App settings stored in an INI file (`settings.ini`): remembered root folder and ReplayGain toggle.
- Background playback and shade controls via `MediaSessionService`.
- Day/night theme; full uninstall (no backup, no fragile user data); playback errors surfaced on screen.
- Shared build setup with `../memlists`: AGP 9.1.1 / Kotlin 2.3.0 / Gradle 9.3.1,
  version catalog, release signing, ABI splits, APK rename, release/install scripts.

### Notes
- ReplayGain is temporarily inactive: the custom audio pipeline broke playback and was removed;
  `GainAudioProcessor`/`ReplayGain` are kept for a safer re-introduction later.
