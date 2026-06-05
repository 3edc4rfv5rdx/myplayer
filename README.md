# MyPlayer

Minimal Android folder player for mp3/flac. Add one or more music folders, browse them in-app, and
play any folder or track. Playback is shuffled by default — built for listening on the move
(bike/car/Bluetooth speaker), not for fiddling.

No equalizer, no internet, no media library — just folders and shuffle.

## Usage

- **Add folder** (system folder picker, one persistable permission per folder). You can add several
  music folders; the home screen lists them.
- Tap a folder in the list to browse it. Remove one with the **✕** (asks to confirm, then releases
  its permission). The home list itself is not playable — only the folders inside it.
- Browse subfolders in-app; the listing is cached per folder so it stays fast.
- **Tap a file** to select it, then press the big **Play** to start (selected track first, then the
  rest of the folder).
- **Play this folder** plays everything under the current folder (recursively).
- **Play** with nothing selected plays the current folder; the same paused folder resumes.
- **Shuffle** switch (main screen, on by default, not persisted): toggles the play order live. With
  shuffle on, starting a folder begins at a random track; off starts from the top.
- **Next** skips; the folder path of the playing track shows above its name.
- **Settings**: Rescan (refresh the cache), theme (System/Light/Dark), **Repeat all**, **ReplayGain**,
  **Follow playing track**, and an About with version/build.
- **Follow playing track** (Settings, on by default): on each track change the browser jumps to the
  playing file's folder and scrolls it into the middle of the list, highlighting it.

## Build

Release-only workflow. Requires Android SDK and JDK 17/21.

- `10-MakeRelease.sh` — bump build number and build signed APK splits.
- `11-EmulRELEASE.sh` / `12-SamsRELEASE.sh` — install on emulator / device.

## How it works

- **Folder access:** Storage Access Framework (`OpenDocumentTree`) with a persistable permission per
  root; folders are read via `DocumentsContract` and files addressed by `content://` tree URIs.
- **Cache & settings:** one SQLite database (`app.db`) — folder listings keyed by (root tree URI,
  parent) and cleared by Rescan, plus app settings (root list, theme, toggles).
- **Playback:** Media3/ExoPlayer in a `MediaSessionService` (background playback + shade controls).
  Order is controlled by Media3's shuffle mode (`shuffleModeEnabled`); when shuffle is on, starting a
  folder picks a random initial track. **Repeat all** maps to `REPEAT_MODE_ALL`.
- **ReplayGain:** optional rough loudness leveling from `REPLAYGAIN_TRACK_GAIN` tags (only tagged
  files) — attenuation via player volume, boost via `LoudnessEnhancer`. Not exact, just even-ish.
