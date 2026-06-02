# MyPlayer

Minimal Android folder player for mp3/flac. Grant access to a root music folder once, then browse it
in-app and play any folder or track. Playback is random without repeats — built for listening on the
move (bike/car/Bluetooth speaker), not for fiddling.

No equalizer, no internet, no media library — just folders and shuffle.

## Usage

- First launch: **Choose root folder** (system folder picker, one permission grant).
- Browse subfolders in-app; the listing is cached so it stays fast.
- **Tap a file** to select it, then press the big **Play** to start (selected track first, then the
  folder shuffled).
- **Play this folder** plays everything under the current folder (recursively), shuffled.
- **Play** with nothing selected plays the current folder; the same paused folder resumes.
- **Next** skips; the path of the playing track shows above its name.
- **Settings**: change root, Rescan (refresh the cache), theme (System/Light/Dark), ReplayGain toggle,
  and an About with version/build.

## Build

Release-only workflow. Requires Android SDK and JDK 17/21.

- `10-MakeRelease.sh` — bump build number and build signed APK splits.
- `11-EmulRELEASE.sh` / `12-SamsRELEASE.sh` — install on emulator / device.

## How it works

- **Folder access:** Storage Access Framework (`OpenDocumentTree`) with a persistable permission on
  the root; folders are read via `DocumentsContract` and files addressed by `content://` tree URIs.
- **Cache & settings:** one SQLite database (`app.db`) — folder listings (cleared by Rescan) and
  app settings (root folder, theme, ReplayGain).
- **Playback:** Media3/ExoPlayer in a `MediaSessionService` (background playback + shade controls);
  order comes from a pre-shuffled playlist with `REPEAT_MODE_ALL`.
- **ReplayGain:** optional rough loudness leveling from `REPLAYGAIN_TRACK_GAIN` tags (only tagged
  files) — attenuation via player volume, boost via `LoudnessEnhancer`. Not exact, just even-ish.
