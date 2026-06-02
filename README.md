# MyPlayer

Minimal Android folder player for mp3/flac. Pick a folder via the system picker; the app plays
every track found in it (including subfolders) in random order without repeats. The chosen root is
remembered, so the picker opens there next time.

No equalizer, no internet, no media library — just a folder and shuffle.

## Build

Release-only workflow (shared with `../memlists`). Requires Android SDK and JDK 17/21.

- `10-MakeRelease.sh` — bump build number and build signed APK splits.
- `11-EmulRELEASE.sh` / `12-SamsRELEASE.sh` — install on emulator / device.

Release signing reads `/home/e/.my-safe/key.properties`; without it the build is unsigned.

## How it works

- **Folder access:** Storage Access Framework (`OpenDocumentTree`) with a persistable URI permission,
  opened at the remembered root. Files are addressed by `content://` tree URIs.
- **Playback:** Media3/ExoPlayer with `shuffleModeEnabled` + `REPEAT_MODE_ALL`, in a
  `MediaSessionService` for background playback and shade controls.
- **ReplayGain:** optional toggle; reads `REPLAYGAIN_TRACK_GAIN` tags (only files that have them).
