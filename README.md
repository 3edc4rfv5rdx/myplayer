# MyPlayer

Minimal Android folder player for mp3/flac. Add one or more music folders, browse them in-app, and
play any folder or track. Music is shuffled by default — built for listening on the move
(bike/car/Bluetooth speaker), not for fiddling. Any folder can be flagged as an **audiobook**: it
then plays in order, remembers where you stopped, and keeps its own speed.

No playlists, no equalizer, no internet, no media library — just folders, shuffle, and audiobooks.

## Features

- **Multiple roots**: add several music folders via the system picker; the home screen lists them.
- **In-app browser**: navigate subfolders without the SAF picker; listings are cached and shown in
  natural order (`Chapter 2` before `Chapter 10`).
- **Shuffle** (on by default, not persisted): toggles the play order live; off plays from the top.
- **Recursive play**: playing a folder plays everything under it; tap a track to start from there.
- **History & favorites**: jump back to recently played folders, or pin folders for quick access.
- **Audiobooks**: per-folder mode that plays sequentially, remembers its position, and keeps its own
  speed — see below.
- **Delete from storage**: remove a folder and its files from the device directly in the browser.
- **Volume leveling** (music only): off, ReplayGain tags, or a real-time auto compressor.
- **Skip silence** and an optional **gap between tracks** (the gap is music-only; books keep their
  natural flow and skip silence).
- **Sleep timer**: minutes or until end of track; works with the screen off.
- **Settings**: rescan, UI language (English / Russian / Ukrainian; first run follows the device
  language), theme and accent colour, start volume (turn the system volume down when playback
  starts, optionally only on Bluetooth), volume leveling, skip silence, gap between tracks, follow
  playing track, hide from other apps (a `.nomedia` in each root), track time as total or
  remaining, seek step, default book speed, auto backup to the Google account, and the switch for
  the update check.

## Audiobooks

- Tick **abook** on a folder to mark it an audiobook (per-folder, persisted). Book mode covers the
  whole subtree: playing anything inside plays the full book from its root, sequentially.
- A book **remembers its position** even after the queue is gone (restart, switched book) and resumes
  where you left off, rewound a few seconds.
- Each book keeps its **own playback speed**; new books use the default from Settings.
- A **progress readout** shows the current file and a time-based percent; the browser also marks the
  resume file and the files already played.

## About and updates

The **i** button on the home screen's top bar opens About: the version, the build date, the GitHub
page, the mailbox, and a button that asks for a newer build there and then. Beyond that the app
asks GitHub for the newest release of this repository once per launched process, and
offers the split built for the device's own ABI — arm64 for a phone, armeabi-v7a for a 32-bit box
— out of the `latest.json` that `23-ToUpdate.sh` uploads beside the APKs. That is the only thing
the app sends over the network, and the Settings switch turns the launch check off; the About
button stays.

## Shared modules

Two folders beside this one are compiled into the app from source rather than pulled in as modules
or AARs — one copy of each serves every project here (the `sourceSets` block in
`app/build.gradle.kts` wires them):

- `../updater` — the update check, its configuration and its dialogs
- `../about` — the About dialog, drawn in code so the resource shrinker cannot drop its strings

## Build

Release-only workflow, driven by the numbered scripts at the repository root. Requires the Android
SDK and JDK 17/21.

| Script | What it does |
|---|---|
| `./00-MakeAll.sh` | release, both installs and the `OUT/` link in one run |
| `./10-MakeRelease.sh` | signed release with ABI splits, raising the build number |
| `./11-EmulRELEASE.sh`, `./12-SamsRELEASE.sh` | install the release on the emulator / the phone |
| `./02-DebugWiFiConn.sh` | connect `adb` to the phone over Wi-Fi |
| `./19-LinkOut.sh` | hard-link this build's APKs into `OUT/` |
| `./20-MakeTag.sh`, `./21-PushTag.sh` | the release tag and its push |
| `./22-RelUpload.sh` | the GitHub Release for that tag, with the APKs |
| `./23-ToUpdate.sh` | `latest.json` for that release, so the in-app updater can find it |
| `./99-CopyToAPKX.sh` | the arm64 APK under an `.apkx` name, for messengers that mangle `.apk` |

Release signing reads `~/.my-safe/key.properties`; the version and the build number live in
`build_number.txt`, and `10-MakeRelease.sh` raises the line itself when the changelog has an `N`
entry waiting under `Unreleased`. The release is split into arm64-v8a, armeabi-v7a and x86_64.

## How it works

- **Folder access:** Storage Access Framework with a persistable permission per root; files are
  addressed by `content://` tree URIs via `DocumentsContract`.
- **Renames:** a folder's identity is its storage path (SAF document id), so renaming or moving a
  folder on disk resets its audiobook state (flag, position, speed) and drops it from history and
  favorites. Known trade-off, not a bug.
- **Storage:** two SQLite databases — `app.db` for settings and per-folder book state (the data worth
  keeping, uploaded by Auto Backup when enabled), `cache.db` for rebuildable folder listings.
- **Playback:** Media3/ExoPlayer in a `MediaSessionService` for background playback and shade
  controls; order via Media3's shuffle mode, repeat hardwired off.
- **UI language:** English, Russian, or Ukrainian from a runtime translations file, switchable in
  Settings without reinstalling.
