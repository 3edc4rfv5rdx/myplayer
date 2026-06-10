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
- Browse subfolders in-app; the listing is cached per folder so it stays fast. Folders and files are
  shown in natural order (so `Chapter 2` comes before `Chapter 10`). 📁 marks a plain folder, 📖 a
  folder flagged as an audiobook, 🎵 a file.
- **Delete a folder**: long-press it in the listing and confirm — this permanently removes the folder
  and all its files from storage (and forgets any audiobook state). If it (or a track queued from it)
  is playing, playback is adjusted first.
- **Tap a file** to select it, then press the big **Play** to start (selected track first, then the
  rest of the folder).
- **Play this folder** plays everything under the current folder (recursively).
- **Play** with nothing selected plays the current folder; the same paused folder resumes.
- **Shuffle** switch (main screen, on by default, not persisted): toggles the play order live. With
  shuffle on, starting a folder begins at a random track; off starts from the top.
- **Next** skips to the next track. The playing track's name and its folder path show above the
  controls.
- **Playback bar** under the track name shows the elapsed time and the track duration at its edges;
  tap or drag it to seek within the track.
- The now-playing name, path, and bar stay visible while a track is playing as you browse folders;
  navigating up away from a stopped or paused track (or returning to the home list) clears them, and
  resuming brings them back.
- **Settings**: Rescan (refresh the cache), theme (System/Light/Dark), **ReplayGain**,
  **Follow playing track**, **Abook default speed** (the speed new audiobooks start at), and an About
  with version/build.
- **Follow playing track** (Settings, on by default): on each track change the browser jumps to the
  playing file's folder and scrolls it into the middle of the list, highlighting it.

## Audiobooks

- Open a folder and tick the **abook** checkbox to mark it as an audiobook (a per-folder, persisted
  flag). It shows as 📖 in its parent listing. The queue's mode is fixed when playback starts; while
  the folder is the live queue its checkbox is locked, so changes always apply to the next start.
- Book mode covers the **whole subtree**: anything played inside the book (a subfolder like CD2, or
  a tapped chapter file) plays the full book from its root — sequential, tracked, at the book's
  speed — jumping to that part. Subfolders show the inherited checkbox state, locked; the flag is
  edited on the book folder itself.
- A book plays **sequentially** — shuffle is locked off while a book is the live queue.
- It **remembers its position**: stopping and returning later (press Play in the same folder) resumes
  where you left off, rewound a few seconds for context.
- Each book keeps its **own playback speed**: the speed button is enabled whenever the browser is
  inside a book (playing or not) and edits that book's saved speed, with an audible live preview
  when that book is what's playing. New books start at the **Abook default speed** from Settings.
  Plain music always plays at 1.0× (the button stays disabled there).
- While a book plays, a **progress readout** shows the current file (N/M) and an approximate overall
  percent with a thin bar.

## Build

Release-only workflow. Requires Android SDK and JDK 17/21.

- `10-MakeRelease.sh` — bump build number and build signed APK splits.
- `11-EmulRELEASE.sh` / `12-SamsRELEASE.sh` — install on emulator / device.

## How it works

- **Folder access:** Storage Access Framework (`OpenDocumentTree`) with a persistable permission per
  root; folders are read via `DocumentsContract` and files addressed by `content://` tree URIs.
- **Cache & settings:** one SQLite database (`app.db`) — folder listings keyed by (root tree URI,
  parent) and cleared by Rescan, plus app settings (root list, theme, toggles, default speed, and
  per-folder audiobook state: mode, resume position, speed).
- **Playback:** Media3/ExoPlayer in a `MediaSessionService` (background playback + shade controls).
  Order is controlled by Media3's shuffle mode (`shuffleModeEnabled`); when shuffle is on, starting a
  folder picks a random initial track. Repeat is always off (a finished queue just ends; hardwired
  via the `Settings.REPEAT_ALL` flag, no UI). Audiobook folders also play with shuffle off; the
  service periodically saves the current file + offset as the book's resume point.
- **ReplayGain:** optional rough loudness leveling from `REPLAYGAIN_TRACK_GAIN` tags (only tagged
  files) — attenuation via player volume, boost via `LoudnessEnhancer`. Not exact, just even-ish.
