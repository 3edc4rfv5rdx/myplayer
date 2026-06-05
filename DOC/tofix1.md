# tofix1.md

The tasks below are written as prompts for an LLM implementer. Repository: Android/Kotlin/Compose app `com.myplayer`.

## Prompt 1: Fix Media3 UnstableApi lint errors

Make a focused fix for the `UnsafeOptInUsageError` lint errors in `PlayerService.kt` and `ReplayGain.kt`.

Context:
- `./gradlew :app:lintDebug` currently fails with 13 errors.
- All errors are related to Media3 unstable APIs:
  - `PlayerService.kt`: `onAudioSessionIdChanged`, `onMetadata`, and the `Metadata` parameter.
  - `ReplayGain.kt`: `Metadata`, `metadata.length()`, `metadata.get(i)`, `VorbisComment`, `TextInformationFrame`, and their fields.

What to do:
- Add explicit opt-in for `androidx.media3.common.util.UnstableApi` at the smallest reasonable scope.
- Do not disable lint globally and do not add a lint baseline.
- Preserve the current ReplayGain behavior.

Verification:
- `./gradlew :app:compileDebugKotlin`
- `./gradlew :app:lintDebug`

## Prompt 2: Close the exported MediaSessionService

Fix the security warning for `PlayerService` in `app/src/main/AndroidManifest.xml`.

Context:
- The manifest declares the service with `android:exported="true"` and an `androidx.media3.session.MediaSessionService` intent filter.
- Lint reports: `Exported service does not require permission`.
- This service is intended for this app's local playback/background session. Other apps should not be able to start or bind to it unless there is a deliberate reason.

What to do:
- Check the Media3 `MediaSessionService` contract for a local controller in this app.
- If external access is not needed, set `android:exported="false"` and verify that `MainActivity` can still connect through `SessionToken(ComponentName(...))`.
- If the service must remain exported, add an appropriate permission guard and document why.

Verification:
- `./gradlew :app:lintDebug`
- Run the app and verify that `MediaController` connection does not show `Connect: ...`.

## Prompt 3: Make the ReplayGain toggle truly live

Fix the mismatch in `PlayerService`: the comment says ReplayGain is toggled live, but the Settings switch only writes a DB flag and the service does not apply the change to the current track until the next metadata/session/track event.

Context:
- `MainActivity.kt` calls only `Settings.setReplayGainEnabled(...)` in `onReplayGainChange`.
- `PlayerService.applyGain()` runs on `onAudioSessionIdChanged`, `onMediaItemTransition`, and `onMetadata`.
- When ReplayGain is disabled during playback, `LoudnessEnhancer` and `player.volume` can remain active until the next track.

What to do:
- Add an explicit signal from the UI to the service when ReplayGain changes.
- Prefer a Media3 custom command/session callback or another clean local mechanism without polling.
- When enabling ReplayGain, apply the current `currentTrackGainDb`; when disabling it, immediately reset `player.volume = 1f` and `enhancer.enabled = false`.
- Keep the persisted setting.

Verification:
- Manual: start a track with a ReplayGain tag, toggle ReplayGain on/off during playback, and verify the volume/effect changes immediately.
- `./gradlew :app:compileDebugKotlin`
- `./gradlew :app:lintDebug`

## Prompt 4: Fix the folder cache key for multiple roots/providers

Fix `FolderCache` so it cannot mix cached folders from different SAF roots or document providers.

Context:
- `FolderCache` stores `children(parent_id, doc_id, name, is_dir)` and `scanned(parent_id)`.
- The cache key is currently only `parent.documentId`.
- With multiple roots or different document providers, identical `documentId` values can collide and the cache can return children from the wrong root.

What to do:
- Include `treeUri.toString()` or another stable root/provider id in the cache key.
- Update the schema in `AppDb`: bump the DB version and migrate, or safely recreate only the cache tables without losing `settings`.
- The `children` and `scanned` tables must distinguish `(tree_uri, parent_id)`.
- Update indexes and the `isScanned/read/store` methods.
- Do not lose settings during migration.

Verification:
- Add or simulate two roots with the same `documentId` and verify that listings do not mix.
- `./gradlew :app:compileDebugKotlin`

## Prompt 5: Remove fragile path reconstruction from documentId

Rewrite the follow/relative path logic that assumes SAF `documentId` values are always hierarchical and slash-separated.

Context:
- `MainActivity.followPlayingTrack()` builds a path with `fileDocId.removePrefix(treeId).trimStart('/').split('/')`.
- `MusicScanner.relativeDir()` also computes a path with string operations on `documentId`.
- This works for some providers, but SAF does not guarantee this structure for all providers.

What to do:
- Do not rely on `documentId` format as a filesystem path.
- During scanning/caching, preserve enough parent-child relation and display-path data for subtitle display and follow-playing navigation.
- Add metadata/extras to `MediaItem`, or introduce an internal model, so the app knows the root, parent folder, and display path of the current file.
- `followPlayingTrack` should find the current file's folder through saved parent/display-path data or cache, not by splitting `documentId`.
- If the path cannot be recovered for an unknown provider, fail gracefully: highlight the track when it is found in the current folder and avoid incorrect navigation.

Verification:
- ExternalStorageProvider must continue to work.
- For a provider with non-hierarchical IDs, the app must not crash and must not construct a fake path.

## Prompt 6: Make SAF errors non-fatal

Add proper error handling around SAF queries and permission loss.

Context:
- `MusicScanner.rootNode()`, `MusicScanner.children()`, and `FolderCache.children()` do not handle `SecurityException`, provider errors, or `query(...) == null`.
- On a cache miss, `FolderCache.children()` can cache an empty result as scanned if the provider temporarily returns no cursor.
- With revoked permission or an unavailable USB/cloud provider, the app can show an empty folder or crash.

What to do:
- Introduce a scan result with an error state, or a domain-level exception that the UI can show as a clear message.
- Do not mark a folder as `scanned` when the query failed or returned null because of access/provider failure.
- Show the error near the folder list and offer Rescan/remove-root style recovery without crashing.
- For `SecurityException` on a root, it is acceptable to mark the root as unavailable, but do not delete it automatically.

Verification:
- Simulate revoked SAF permission or an invalid URI.
- The folder must not be silently cached as empty.

## Prompt 7: Synchronize Activity state with the still-running Service

Fix behavior after `MainActivity` recreation or returning from the background.

Context:
- `playingFolderId` is stored only in `MainActivity` and is reset when the activity is recreated.
- `PlayerService` and its playlist continue running.
- `togglePlay()` decides resume vs restart with `dir.documentId != playingFolderId`; after recreation it can call `playFolder(dir)` again instead of `controller.play()`.

What to do:
- Store the current starting folder id in `MediaItem` metadata/extras, playlist metadata, service state, or saved instance state.
- When `MediaController` connects, restore `playingFolderId` from the current item/playlist.
- Pressing Play in the same folder after activity recreation should resume the existing playlist, not rebuild it.

Verification:
- Start playing a folder, stop/rotate/recreate the activity, then press Play in the same folder.
- Playback must not restart from a new random track.

## Prompt 8: Handle root removal during playback correctly

Fix `removeRoot(treeUri)` so removing a root does not leave an active playlist with content URIs after the app has just released the permission for that root.

Context:
- `removeRoot()` calls `releasePersistableUriPermission`, removes the root, and clears the cache.
- If a track from that root is playing, the service can keep a playlist with URIs that may no longer be readable.

What to do:
- Detect whether the current playlist/current item belongs to the removed root.
- If yes, stop playback, clear media items, reset `playingFolderId`, `playingDocIdState`, and selected/path state where appropriate.
- If the removed root is not the current playback root, leave playback alone.

Verification:
- Play a track from root A, then remove root A: playback should stop cleanly without provider permission errors.
- Play root A, remove root B: playback should continue.

## Prompt 9: Add backup/dataExtractionRules

Fix the `DataExtractionRules` lint warning.

Context:
- The manifest has `android:allowBackup="false"`, but Android 12+ lint asks for `android:dataExtractionRules`.
- The app stores SAF roots/settings/cache in `app.db`; transferring this data without the corresponding permissions may be harmful or useless.

What to do:
- Add `res/xml/data_extraction_rules.xml` with an explicit cloud/device-transfer block if backups are not desired.
- Reference it from `<application android:dataExtractionRules="@xml/data_extraction_rules">`.
- If needed, add `fullBackupContent` for older APIs in a way that is consistent with the current `allowBackup=false`.

Verification:
- `./gradlew :app:lintDebug`

## Prompt 10: Fix release upload script selection and cleanup

Fix `22-RelUpload.sh`.

Context:
- If the expected arm64 APK is missing, `APK_ARM64=$(ls -t "$APK_DIR"/*.apk | head -1)` can select universal/x86_64 and upload it under the arm64 name.
- If the universal APK is missing, the script sets `APK_UNIVERSAL="$APK_ARM64"` and uploads the same file under two asset names.
- `CHANGELOG_FILE` is removed only at the very end; on failure, the temporary file is left behind.
- `SCRIPT_DIR="/home/e/PRJ/myplayer"` is hardcoded.

What to do:
- Resolve `SCRIPT_DIR` relative to the script location.
- For the arm64 fallback, search only `*-arm64-v8a.apk`; for universal, search only `*-universal.apk`.
- If a required asset is missing, fail fast with a clear list of available APKs; do not rename another ABI as the missing one.
- Add `trap 'rm -f "$CHANGELOG_FILE" "${CHANGELOG_FILE}.tmp"' EXIT`.

Verification:
- Run dry-run mode with the universal APK missing and verify that the script does not substitute another asset.

## Prompt 11: Fix the install-to-Samsung script

Fix `12-SamsRELEASE.sh`.

Context:
- `TEL=$(adb devices | awk ...)` can be empty.
- `adb -s $TEL install ...` without quotes becomes an invalid call when `TEL` is empty.
- The script does not distinguish 0, 1, or multiple matching physical devices.

What to do:
- Add `set -e`.
- If no physical devices are connected, print a clear error and `exit 1`.
- If multiple devices are connected, either require a serial argument or explicitly choose the first one with a warning.
- Always quote `"$TEL"`.

Verification:
- With no device connected, the script should fail with a clear message.
- With one device connected, it should run `adb -s "$TEL" install -r "$apk"`.

## Prompt 12: Update README to match actual behavior

Synchronize `README.md` with the current app.

Context:
- README talks about one root folder and "change root", but the code supports a list of roots with add/remove.
- README says "pre-shuffled playlist", while the code uses `controller.shuffleModeEnabled` and a random start index.
- README does not describe settings for Repeat all, Follow playing track, or the Shuffle switch.

What to do:
- Update Usage and How it works without marketing filler.
- Describe multiple music folders, add/remove root, Rescan, ReplayGain, Repeat all, Follow playing track, and the Shuffle switch.
- Clarify that playback order is controlled by Media3 shuffle mode; when shuffle is on, starting a folder chooses a random initial index.

Verification:
- README must match `MainActivity.kt` and `Settings.kt`.

## Prompt 13: Add minimal unit tests for pure logic

Add focused tests for logic that can be verified without an Android device.

Context:
- The project currently has no tests.
- There is pure logic in `ReplayGain.parseTrackGainDb()` / `toLinear()` and potentially in future helper functions for cache keys/path metadata.

What to do:
- Configure only the necessary `testImplementation` dependencies.
- Add unit tests for parsing ReplayGain values: `-6.48 dB`, `+3.0 dB`, raw values without suffix, and invalid strings.
- If helper functions for cache keys/path metadata are introduced by earlier fixes, cover them too.

Verification:
- `./gradlew :app:testDebugUnitTest`
- `./gradlew :app:lintDebug`
