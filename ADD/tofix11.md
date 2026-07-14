# Code and release-workflow audit #11 — actionable findings

Static audit of the current repository at commit `17a0c23`. The review covered the Android playback
and SAF flows, persistence/cache concurrency, Compose state and navigation, backup configuration,
and the build/tag/upload scripts. Previous audit files were checked first so resolved findings were
not raised again. `python3 tools/check-i18n.py` passes (`87` keys; `en`, `ru`, and `ua`). Per the
repository's release-only workflow instructions, no Gradle build or install was run.

Each finding below is intentionally written as a self-contained implementation prompt for an LLM.

---

## FIXED (1c2091a) — Finding 1 — Folder deletion loses its required SAF write grant after the transient picker permission expires

**Confidence: High. Severity: High (a documented core feature stops working after process/device restart).**

### Problem

In `MainActivity.kt`, the `OpenDocumentTree` result handler persists only
`Intent.FLAG_GRANT_READ_URI_PERMISSION` (`folderPicker`, around lines 261-269). Later,
`deleteBook` calls `DocumentsContract.deleteDocument` for a child of that tree (around lines
1158-1204). Deleting through a DocumentsProvider requires write access as well as provider support
for deletion. The picker may give the activity a transient write grant, so deletion can appear to
work during the picker session, but only the read grant is retained. After the transient grant is
gone (for example after a process or device restart), the delete call can fail with a security
exception; the `runCatching` turns that into the generic "Could not delete the folder" error. This
also makes the failure look like a provider/storage problem instead of a missing permission caused
by the app itself.

### Solution prompt

Fix MyPlayer's SAF permission lifecycle so its advertised in-app folder deletion remains available
after restart. In the `OpenDocumentTree` result callback in `MainActivity.kt`, persist both
`Intent.FLAG_GRANT_READ_URI_PERMISSION` and `Intent.FLAG_GRANT_WRITE_URI_PERMISSION`, and use the
same persisted flags when releasing a root. Handle `SecurityException`/unsupported grants without
crashing: do not add a root that cannot retain the permissions required by the chosen product
policy, and show a localized actionable error. Before exposing or performing Delete, also account
for provider capability (`DocumentsContract.Document.FLAG_SUPPORTS_DELETE`) rather than treating
every directory as deletable; this will require carrying document flags in `Node` or querying the
target deliberately off the main thread. Preserve read-only browsing if the product explicitly
chooses to support read-only providers, but then disable/hide Delete for those roots and explain why.
Add a manual regression test: add a writable tree, kill/relaunch the app (and ideally reboot), then
delete a child folder successfully; also verify a read-only/non-deletable provider cannot reach a
misleading enabled Delete action.

---

## FIXED (6987089) — Finding 2 — Release upload silently substitutes an APK from an older build

**Confidence: High. Severity: High (the GitHub release can be mislabeled and not match its tag/source).**

### Problem

`22-RelUpload.sh` first constructs the exact APK names for the newest tag, but if either file is
missing it falls back to the newest `*-arm64-v8a.apk` or `*-universal.apk` in the output directory
(around lines 106-117). The fallback restricts the ABI, but not the version or build number. If the
current tag was created without a matching build, or the current build failed/was cleaned while an
older APK remains, the script uploads that old binary under filenames containing the new tag's
version and build. The existence checks at lines 121-131 accept this substitution, so the script
reports success and publishes a release whose binaries do not correspond to its tag.

### Solution prompt

Make `22-RelUpload.sh` fail closed on release-artifact identity. Remove the cross-version "latest
APK" fallbacks: require exactly
`myplayer-${VERSION}+${BUILD}-release-arm64-v8a.apk` and
`myplayer-${VERSION}+${BUILD}-release-universal.apk` for the parsed tag. If compatibility with old
Gradle output names is genuinely needed, accept only an artifact whose embedded APK
`versionName`/`versionCode` are verified against `${VERSION}`/`${BUILD}` using `apkanalyzer`,
`aapt2 dump badging`, or another Android SDK tool before upload; never infer identity from mtime.
Print the expected paths and abort before creating or modifying a GitHub release when verification
fails. Optionally verify both exact-name artifacts too, which protects against manually renamed or
stale files. Add a shell-level regression scenario with only a previous build's APKs present and
assert that the script exits nonzero without calling `gh release create`, `delete-asset`, or
`upload`.

---

## FIXED (0569df4) — Finding 3 — The release/tag cleanliness checks ignore untracked source files

**Confidence: High. Severity: Medium-high (a shipped APK may not be reproducible from the tag).**

### Problem

`20-MakeTag.sh` and `21-PushTag.sh` declare the working tree clean when both `git diff --quiet` and
`git diff --cached --quiet` succeed (respectively around lines 6-14 and 11-19). Neither command
reports untracked files. A newly created but untracked Kotlin/resource/manifest file under `app/src`
can be included by Gradle in the APK while the scripts still say "Working tree is clean" and tag or
push a commit that omits that file. Untracked release notes or configuration can cause similar
source/tag drift. This is especially dangerous because the release workflow builds before tagging.

### Solution prompt

Harden the release workflow's cleanliness guard in both `20-MakeTag.sh` and `21-PushTag.sh` (and
preferably centralize it in one sourced helper). Use `git status --porcelain` or an equivalent check
that rejects staged changes, unstaged changes, and untracked files. Do not ignore all untracked
files globally; build outputs should already be covered by `.gitignore`, and an unexpected
untracked file must stop a release. Print the offending status lines so the user can correct them.
Also consider running the same guard before `10-MakeRelease.sh` builds, because checking only at tag
time cannot prove an already-built APK came from the clean commit. Add regression checks for an
untracked `app/src/main/java/.../Temp.kt`, an unstaged tracked change, a staged change, and a truly
clean tree.

---

## FIXED (24a5369) — Finding 4 — FolderBrowser carries list/data state into a different folder and does not fully identify a folder by its tree

**Confidence: High for stale scroll position; Medium-high for cross-tree data collision. Severity: Medium-low (wrong navigation position; potentially wrong listing across providers).**

### Problem

`FolderBrowser` creates one unkeyed `rememberLazyListState()` (around line 1769), so descending into
or opening a different folder reuses the previous folder's scroll index/offset. A user who scrolls
far down one directory can open another directory already positioned far from its first item. The
listing effect does not reset the scroll unless the new listing happens to contain the currently
playing file.

The surrounding local data is keyed only by `current.documentId`: `contents`, `loadFailed`,
`retryTick`, `bookIds`, `resumeFileIndex`, and `rowDurations` use bare document IDs, and the loading
`LaunchedEffect` also omits `treeUri` (around lines 1770-1802 and 1844-1845). Elsewhere the project
correctly treats `(treeUri, documentId)` as the identity because document IDs can collide across
providers/roots. Switching via Home/History to another tree with the same document ID can therefore
reuse the old tree's remembered listing/status, and the load effect may not rerun at all.

### Solution prompt

Scope every folder-specific Compose state in `FolderBrowser` to a stable folder identity containing
both `treeUri.toString()` and `current.documentId`. Include that identity in the listing
`LaunchedEffect` and in all relevant `remember` keys (`contents`, errors/retry, child-book IDs,
resume marker, row durations, pending folder actions if applicable). Give each folder a fresh or
explicitly restored `LazyListState`; for the current UX, reset to item 0 when folder identity changes,
then let the existing follow-playing-track effect center a live item when appropriate. Avoid two
effects racing such that the reset overrides the follow scroll. Test (1) scroll deep in folder A and
open folder B, which must start at the top, and (2) switch between two tree URIs whose current nodes
have the same document ID, which must show the correct provider's contents and state.

---

## FIXED (b8624d1) — Finding 5 — Playing a selected file never records its folder in History

**Confidence: High. Severity: Low-medium (a visible feature is incomplete for a common playback path).**

### Problem

The successful `playFolder` path calls `Settings.addHistory` before starting its queue (around lines
993-1004). The successful `playFile` path builds and starts the queue but never calls
`Settings.addHistory` (around lines 1136-1152). Therefore, tapping/selecting a track and pressing
Play does not add that folder to History, even though playback did start from it. This affects both
plain music and explicit chapter jumps inside an audiobook, and contradicts History's description
as recently played folders. Users who normally start playback by selecting a song can see an empty
or stale History list.

### Solution prompt

Make History recording consistent across all successful playback entry points. In `playFile`, after
the scan/index validation succeeds and immediately before (or alongside) `startQueue`, call
`Settings.addHistory` with the current tree and full browser path, matching `playFolder`'s shape and
using `isBook = (bookPath != null)`. Record the folder the user launched from (`path`), not merely the
outer book root, so reopening History returns to the same browsed location. Do not add an entry when
the controller disappeared, the scan failed, the index became invalid, or the queue is empty.
Consider extracting a shared helper so `playFolder` and `playFile` cannot drift again. Test direct
track play for plain music, a track inside an inherited audiobook subtree, duplicate de-duplication,
and failed loads.

---

## FIXED (8798a24) — Finding 6 — Persisted playback speeds are accepted without finiteness or range validation

**Confidence: High. Severity: Low (requires corrupt, manually edited, or future/foreign restored data; can crash the UI/play path).**

### Problem

`Settings.getDefaultSpeed` and `Settings.getSpeed` accept any string that `toFloatOrNull()` parses
(around lines 298-302 and 450-454). That includes out-of-range values and special values such as
`NaN`/infinity. The rest of the app assumes every speed is finite and within
`SPEED_MIN..SPEED_MAX`: `formatSpeed` calls `roundToInt`, sliders require an in-range value, and
Media3 playback parameters require a valid positive speed. A malformed restored DB row can thus
crash while rendering Settings/a book speed or when starting a book, leaving no UI path to repair
the value. The language, track-gap, and seek-step getters already demonstrate the desired
source-level validation pattern.

### Solution prompt

Sanitize playback speed at the `Settings` boundary. Add one shared parser/normalizer that accepts a
stored float only when `isFinite()` and within `SPEED_MIN..SPEED_MAX` (optionally snap it to
`SPEED_STEP` if persisted values are meant to follow the UI grid). Make `getDefaultSpeed` fall back
to `SPEED_DEFAULT`; make per-book `getSpeed` fall back to the sanitized default. Also clamp or reject
values in `setDefaultSpeed` and `setSpeed` so non-UI callers cannot persist invalid state. Keep a
defensive clamp at the MediaController boundary only if useful, but do not rely on it because the
same bad value is rendered before playback. Add focused unit tests for `NaN`, positive/negative
infinity, zero/negative, just-outside bounds, malformed text, exact bounds, and valid stepped values.

---

## FIXED (4ef8b7f) — Finding 7 — DurationCache.preload can overwrite a concurrently resolved in-memory duration with UNKNOWN

**Confidence: High for the race; severity: Low (loses the accurate placeholder until memory is cleared, while the DB remains correct).**

### Problem

`DurationCache.preload` computes a `missing` URI list under `mem`'s lock, releases the lock for its
SQLite query, then writes every result (including `UNKNOWN_MS` for absent rows) back under the lock
(around lines 63-76). Concurrently, `DurationCache.durations` can resolve one of those URIs and
`store` its real value in both the DB and `mem` (around lines 37-57 and 119-124). This interleaving is
possible:

1. `preload` marks URI X missing and queries before X exists in the DB;
2. `store` writes X's resolved duration to the DB and `mem`;
3. `preload` writes its stale `UNKNOWN_MS` result over the real value in `mem`.

`peek` treats a present `UNKNOWN_MS` entry as an immediate null and does not re-query the DB, so a
later gapped queue uses the 24-hour fallback even though the database already contains the exact
duration. The UI filters that placeholder, limiting the impact, but the memory mirror no longer
matches its documented authoritative cache behavior until `store` runs again or `clearMem` is
called.

### Solution prompt

Make `DurationCache.preload` merge query results without overwriting values installed after its
initial missing-set snapshot. Under the final `synchronized(mem)` block, use a compare-and-set style
rule: populate a URI only if it is still absent, or preserve any current non-`UNKNOWN_MS` value.
Review the reverse interleavings with `remove`/`clearMem` too; if generation invalidation is needed,
capture a generation before the query and discard stale results after a clear. Keep all SQLite and
metadata work off the main thread and preserve `peek`'s no-query fast path after preload. Add a
deterministic concurrency test with latches that pauses preload after its DB read, stores a real
duration, resumes preload, and asserts that both `peek` and the memory mirror still return the real
duration.

---

## Summary

| # | Finding | Main files | Severity |
|---|---|---|---|
| 1 | Persisted SAF grant is read-only although deletion needs write access | `MainActivity.kt`, `MusicScanner.kt` | High |
| 2 | Upload script can publish an older APK under the newest tag | `22-RelUpload.sh` | High |
| 3 | Tag/push cleanliness checks ignore untracked files | `20-MakeTag.sh`, `21-PushTag.sh` | Medium-high |
| 4 | FolderBrowser retains scroll/data state and omits tree URI from identity | `MainActivity.kt` | Medium-low |
| 5 | Direct track playback does not update History | `MainActivity.kt` | Low-medium |
| 6 | Persisted speeds are not validated for finiteness/range | `Settings.kt`, `MainActivity.kt` | Low |
| 7 | Duration preload can overwrite a concurrent resolved cache value | `DurationCache.kt` | Low |
