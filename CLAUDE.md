# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Minimal Android folder player (Kotlin + Jetpack Compose + Media3/ExoPlayer). The user keeps an
ordered list of root folders (added via the SAF picker) and browses them in an in-app folder
browser; playing a folder plays everything under it (recursively). Music playback is shuffled by
default (toggle on the main screen) and never loops — repeat is hardwired off (the
`Settings.REPEAT_ALL` flag). Any folder can be flagged as an *audiobook*: it then plays
sequentially (no shuffle), remembers its resume
position, and has its own playback speed; books can also be deleted from storage in-app. No
equalizer, no internet, no media library — deliberately primitive.

## Build / install (release-only workflow)

Build config and scripts are shared with the sibling `../memlists` project. Use the numbered scripts:
- `10-MakeRelease.sh` — bumps `build_number.txt`, runs `./gradlew assembleRelease`, produces ABI
  splits + universal APK renamed to `myplayer-<version>+<code>-release-*.apk`.
- `11-EmulRELEASE.sh` — install on emulator (x86_64). `12-SamsRELEASE.sh` — install on device (arm64).
- `00-DebugWiFiConn.sh` — adb connect over Wi-Fi.

Conventions:
- Never hand-edit or bump `build_number.txt`; `10-MakeRelease.sh` owns it. Stage it if it shows as
  modified so the repo version stays in sync with the built artifact. Always check `build_number.txt`'s
  status before every commit and stage it if modified, so it never drifts out of sync.
- Do not auto-build or auto-install; the user runs the scripts.
- Toolchain: AGP 9.1.1 (built-in Kotlin, no separate kotlin-android plugin), Kotlin 2.3.0,
  Gradle 9.3.1, JDK 17/21, compileSdk 36, minSdk 31. Versions live in `gradle/libs.versions.toml`.
- Release signing reads `/home/e/.my-safe/key.properties` (falls back to repo `key.properties`);
  both the keystore and `key.properties` are git-ignored. Unsigned if absent.

## Architecture

Single `:app` module, package `com.myplayer`. UI and playback are split across a Media3 session:

- `PlayerService` (`MediaSessionService`) owns the `ExoPlayer` + `MediaSession` — this is what gives
  background playback and shade controls. Randomization uses ExoPlayer's built-in shuffle, so the
  main-screen toggle reorders the live queue without rebuilding it; `MainActivity` only sets the
  media items and the start track (random or selected-first). Shuffle is a non-persisted toggle (on
  by default); repeat is always `REPEAT_MODE_OFF` (compile-time `Settings.REPEAT_ALL`, no UI).
- `MainActivity` is UI only; it drives the service through a `MediaController` (built in `onStart`,
  released in `onStop`).

Supporting files:
- `Settings` — single source for persisted state: ordered roots list, toggles (ReplayGain, Repeat,
  Follow, theme), default speed, and per-folder book state (mode, resume position, speed), all in
  the `settings` table of `AppDb` (SQLite). Don't read/write persisted state anywhere else.
- `MusicScanner` — SAF walk via `DocumentsContract` producing `Node`s / `MediaItem`s (mp3/flac only),
  sorted in natural order; carries each item's ancestor path in extras for follow/delete logic.
- `FolderCache` — caches `MusicScanner` folder listings in `AppDb` so the browser and recursive
  "play this folder" walks don't re-query the provider every time; invalidated on rescan/delete.
- `DurationCache` — per-file durations in `AppDb` (lazy `MediaMetadataRetriever` fill), feeding the
  time-based book progress readout; cleared by Rescan.
- `ReplayGain` — optional volume normalization (see below).

## Folder selection

The system Storage Access Framework picker (`OpenDocumentTree`) is used **only to add a root** to the
list (the one place a persistable permission is taken). Day-to-day navigation is the in-app
`FolderBrowser`, backed by `FolderCache`/`MusicScanner`. Files are addressed by `content://` tree
URIs, never raw filesystem paths — the only reliable way under scoped storage.

## ReplayGain

Optional, off by default, toggled from the UI. Reads `REPLAYGAIN_TRACK_GAIN` tags surfaced by
ExoPlayer's `Player.Listener.onMetadata` (FLAC `VorbisComment`, MP3 ID3 `TextInformationFrame`) —
no external tag-parsing library. The gain is applied in `PlayerService` without touching the render
pipeline: attenuation (negative gain) via the player volume, boost (positive gain, capped) via an
Android `LoudnessEnhancer` audio effect. No custom `AudioProcessor`/`AudioSink`.

Key limitation: ReplayGain only affects files that already contain the tags. Untagged files play
unchanged. Computing loudness ourselves (analysis) is intentionally out of scope.

## Conventions

- All code identifiers/comments in English. UI strings are localized at runtime via `lw()`/`loc()`
  with the English text as the key and translations in `assets/i18n.json` (see `AppLocalizer`) —
  no hardcoded UI text. After changing any UI string or `i18n.json`, run `tools/check-i18n.py`;
  it must pass (nothing checks code ↔ json consistency at compile time).
- Every commit must update `CHANGELOG.md` in the same commit. Newest entries go on top
  (prepend to `## Unreleased`), not appended at the bottom.
- Keep shared values/functions centralized (e.g. `Settings`); no duplication across files.
- Buttons must have a filled background — no low-contrast "grey on grey"; use a solid `Button`, not outlined.
