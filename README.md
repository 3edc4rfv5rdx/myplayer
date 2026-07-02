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
- **Volume leveling** (music only): off, ReplayGain tags, or a real-time auto compressor.
- **Skip silence** and an optional **gap between tracks** (the gap is music-only; books keep their
  natural flow and skip silence).
- **Sleep timer**: minutes or until end of track; works with the screen off.
- **Settings**: rescan, theme and accent color, follow playing track, seek step, default book speed,
  auto backup, and UI language (English / Russian / Ukrainian; first run follows the device
  language).

## Audiobooks

- Tick **abook** on a folder to mark it an audiobook (per-folder, persisted). Book mode covers the
  whole subtree: playing anything inside plays the full book from its root, sequentially.
- A book **remembers its position** even after the queue is gone (restart, switched book) and resumes
  where you left off, rewound a few seconds.
- Each book keeps its **own playback speed**; new books use the default from Settings.
- A **progress readout** shows the current file and a time-based percent; the browser also marks the
  resume file and the files already played.

## Build

Release-only workflow. Requires Android SDK and JDK 17/21.

- `10-MakeRelease.sh` — bump build number and build signed APK splits.
- `11-EmulRELEASE.sh` / `12-SamsRELEASE.sh` — install on emulator / device.

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
