# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Minimal Android folder player (Kotlin + Jetpack Compose + Media3/ExoPlayer). The user remembers a
root music folder once; each session they pick a folder (the remembered root is the picker's start
location) and the app plays everything under it (recursively) in random order without repeats. No
equalizer, no internet, no media library — deliberately primitive.

## Build / install (release-only workflow)

Build config and scripts are shared with the sibling `../memlists` project. Use the numbered scripts:
- `10-MakeRelease.sh` — bumps `build_number.txt`, runs `./gradlew assembleRelease`, produces ABI
  splits + universal APK renamed to `myplayer-<version>+<code>-release-*.apk`.
- `11-EmulRELEASE.sh` — install on emulator (x86_64). `12-SamsRELEASE.sh` — install on device (arm64).
- `00-DebugWiFiConn.sh` — adb connect over Wi-Fi.

Conventions:
- Never hand-edit or bump `build_number.txt`; `10-MakeRelease.sh` owns it. Stage it if it shows as
  modified so the repo version stays in sync with the built artifact.
- Do not auto-build or auto-install; the user runs the scripts.
- Toolchain: AGP 9.1.1 (built-in Kotlin, no separate kotlin-android plugin), Kotlin 2.3.0,
  Gradle 9.3.1, JDK 17/21, compileSdk 36, minSdk 31. Versions live in `gradle/libs.versions.toml`.
- Release signing reads `/home/e/.my-safe/key.properties` (falls back to repo `key.properties`);
  both the keystore and `key.properties` are git-ignored. Unsigned if absent.

## Architecture

Single `:app` module, package `com.myplayer`. UI and playback are split across a Media3 session:

- `PlayerService` (`MediaSessionService`) owns the `ExoPlayer` + `MediaSession` — this is what gives
  background playback and shade controls. Playback semantics live here: `shuffleModeEnabled = true`
  + `REPEAT_MODE_ALL` *is* the "random, no repeats, reshuffle on loop" requirement. Do not
  reimplement shuffling manually.
- `MainActivity` is UI only; it drives the service through a `MediaController` (built in `onStart`,
  released in `onStop`).

Supporting files:
- `Prefs` — single source for persisted state (remembered root folder URI, ReplayGain toggle). Don't
  touch `SharedPreferences` elsewhere.
- `MusicScanner` — recursive SAF (`DocumentFile`) walk producing `MediaItem`s; mp3/flac only.
- `ReplayGain` + `GainAudioProcessor` — optional volume normalization (see below).

## Folder selection

Uses the system Storage Access Framework picker (`OpenDocumentTree`), launched with the remembered
root URI as its initial location. On result the activity takes a persistable permission, plays the
chosen folder recursively, and (first time only) saves it as the remembered root. Files are addressed
by `content://` tree URIs, never raw filesystem paths — the only reliable way under scoped storage.
There is intentionally no in-app folder browser; the SAF picker is the browser.

## ReplayGain

Optional, off by default, toggled from the UI. Reads `REPLAYGAIN_TRACK_GAIN` tags surfaced by
ExoPlayer's `Player.Listener.onMetadata` (FLAC `VorbisComment`, MP3 ID3 `TextInformationFrame`) —
no external tag-parsing library. `GainAudioProcessor` is a custom Media3 `AudioProcessor` inserted
via a `DefaultAudioSink` (float output disabled to keep a 16-bit PCM path) that multiplies samples
by the linear gain with hard-clip protection; gain `1.0` is transparent passthrough.

Key limitation: ReplayGain only affects files that already contain the tags. Untagged files play
unchanged. Computing loudness ourselves (analysis) is intentionally out of scope.

## Conventions

- All code identifiers/comments in English. UI strings live in `res/values/strings.xml`, read with
  `stringResource` — no hardcoded UI text. UI is English-only.
- Every commit must update `CHANGELOG.md` in the same commit. Newest entries go on top
  (prepend to `## Unreleased`), not appended at the bottom.
- Keep shared values/functions centralized (e.g. `Prefs`); no duplication across files.
