# Changelog

## [Unreleased]

### Added
- Initial Android scaffold (Kotlin, Jetpack Compose, Media3/ExoPlayer).
- Folder selection via the system SAF picker, opened at the remembered root folder;
  chosen folder is played recursively. Root is remembered across launches.
- Random playback without repeats (ExoPlayer shuffle + repeat-all), reshuffle on loop.
- Background playback and shade controls via `MediaSessionService`.
- Optional ReplayGain volume normalization (toggle), reading `REPLAYGAIN_TRACK_GAIN`
  tags and applying gain through a custom `GainAudioProcessor`.
- Shared build setup with `../memlists`: AGP 9.1.1 / Kotlin 2.3.0 / Gradle 9.3.1,
  version catalog, release signing, ABI splits, APK rename, release/install scripts.
