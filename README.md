# MyPlayer

Minimal Android folder player for mp3/flac. Add one or more music folders, browse them in-app, and
play any folder or track. Music is shuffled by default — built for listening on the move
(bike/car/Bluetooth speaker), not for fiddling. Any folder can be flagged as an **audiobook**: it
then plays in order, remembers where you stopped, and keeps its own speed.

No equalizer, no internet, no media library — just folders, shuffle, and audiobooks.

## Usage

- **Add folder** (system folder picker, one persistable permission per folder). You can add several
  music folders; the home screen lists them.
- Tap a folder in the list to browse it. Remove one with the **✕** (asks to confirm, then releases
  its permission). The home list itself is not playable — only the folders inside it.
- **Exit** (✕, top-left of the home list) stops playback, tears down the player service and its
  notification, and closes the app.
- Browse subfolders in-app; listings are cached per folder. Folders and files are in natural order
  (`Chapter 2` before `Chapter 10`). 📁 folder, 📖 audiobook, 🎵 music file, 📄 track inside a book.
  The browser title reads **Music** or **AudioBook** for the folder's mode.
- **History**: the home list has a **History** button listing the last few folders you played (📖 for
  books, 🎵 for music); tapping one jumps the browser straight back to that folder.
- **Delete a folder**: long-press and confirm — permanently removes it and its files from storage
  (and forgets its audiobook state); playback is adjusted first if affected.
- **Tap a file** to select it, then press the big **Play** to start (selected track first, then the
  rest of the folder).
- **Play this folder** plays everything under the current folder (recursively).
- **Play** with nothing selected plays the current folder; the same paused folder resumes.
- **Shuffle** switch (main screen, on by default, not persisted): toggles the play order live. With
  shuffle on, starting a folder begins at a random track; off starts from the top.
- **Previous / Next** skip tracks, and **−30s / +30s** buttons step within the current track.
- **Playback bar** shows the elapsed time and, at the right edge, the track duration (or the
  remaining time, per the Track-time setting); tap or drag it to seek within the track.
- **Sleep timer** (⏳ in the top bar): minutes slider (10–60) or **Until end of track**; pauses
  playback (a book keeps its resume point), works with the screen off, not persisted.
- The now-playing name, path, and bar stay visible while playing as you browse; leaving a stopped or
  paused track (or going home) clears them, and resuming brings them back.
- **Settings**: Rescan (refresh the cache), theme (System/Light/Dark), **ReplayGain**,
  **Follow playing track**, **Track time** (rightmost readout shows total or remaining time),
  **Auto backup**, **Abook default speed** (the speed new audiobooks start at), and an About dialog
  (ℹ️ in the top bar) with version/build.
- **Follow playing track** (on by default): on each track change the browser jumps to the playing
  file's folder and centers it.
- **Auto backup** (off by default): includes settings and book progress in Android Auto Backup; the
  listing cache isn't backed up, and SAF permissions don't survive a restore (re-grant the roots).

## Audiobooks

- Open a folder and tick **abook** to mark it an audiobook (per-folder, persisted; shows as 📖). The
  mode is fixed at queue start, so while it's the live queue the checkbox is locked.
- Book mode covers the **whole subtree**: playing anything inside (a subfolder, a tapped chapter)
  plays the full book from its root — sequential, tracked, at the book's speed — jumping to that part.
  Subfolders show the inherited state, locked; the flag is edited on the book folder.
- A book plays **sequentially** (shuffle locked off while it's the live queue).
- A paused book resumes in place with Play, and **remembers its position**: even after the queue is
  gone (restart, switched book), re-entering and Play resumes where you left off, rewound a few sec.
- Each book keeps its **own playback speed**: editable whenever the browser is inside the book (slider
  with −/+), applied live when that book is playing. New books use the **Abook default speed**; plain
  music always plays at 1.0×.
- While a book plays, a **progress readout** shows the current file (N/M) and a **time-based** percent
  (elapsed / total or remaining), backed by a per-file duration cache that refines it in the
  background; until any duration is known it estimates by file count.

## Build

Release-only workflow. Requires Android SDK and JDK 17/21.

- `10-MakeRelease.sh` — bump build number and build signed APK splits.
- `11-EmulRELEASE.sh` / `12-SamsRELEASE.sh` — install on emulator / device.

## How it works

- **Folder access:** Storage Access Framework (`OpenDocumentTree`) with a persistable permission per
  root; folders are read via `DocumentsContract` and files addressed by `content://` tree URIs.
- **Cache & settings:** two SQLite databases. `app.db` holds app settings (root list, theme,
  toggles, default speed, per-folder audiobook state: mode, resume position, speed) plus the per-file
  duration cache — the data worth keeping, so it is what Auto Backup uploads when enabled. `cache.db`
  holds the rebuildable folder listings keyed by (root tree URI, parent), cleared by Rescan and
  excluded from backup.
- **Playback:** Media3/ExoPlayer in a `MediaSessionService` (background playback + shade controls).
  Order is controlled by Media3's shuffle mode (`shuffleModeEnabled`); when shuffle is on, starting a
  folder picks a random initial track. Repeat is always off (a finished queue just ends; hardwired
  via the `Settings.REPEAT_ALL` flag, no UI). Audiobook folders also play with shuffle off; the
  service periodically saves the current file + offset as the book's resume point.
- **Sleep timer:** lives in the service (fires with the screen off), driven by a custom session
  command; pauses at a deadline or on the next auto track transition. Not persisted.
- **ReplayGain:** optional rough loudness leveling from `REPLAYGAIN_TRACK_GAIN` tags (only tagged
  files) — attenuation via player volume, boost via `LoudnessEnhancer`. Not exact, just even-ish.
