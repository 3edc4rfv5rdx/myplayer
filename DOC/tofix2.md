# tofix2.md

The tasks below are written as prompts for an LLM implementer. Repository: Android/Kotlin/Compose
app `com.myplayer` (folder player on Media3/ExoPlayer). Each prompt is self-contained: read the
named files, make the focused change, and verify with the listed commands. Do not bump
`build_number.txt`, do not auto-build/install, and update `CHANGELOG.md` in the same commit as any
code change.

---

## Prompt 1: Remove dead ReplayGain code and centralize the gain math (no duplication)

`ReplayGain.kt` exports `fun toLinear(db)`, plus the private constants `MAX_LINEAR = 4f` and
`PREAMP_DB = 0f`, but nothing calls `toLinear` — the actual linear-gain computation lives separately
in `PlayerService.applyGain()` (`10f.pow(capped / 20f)`). So the dB→linear conversion and the
"+12 dB cap" are implemented twice, in two places, with two different representations of the cap:
`PlayerService` caps in dB (`db.coerceAtMost(12f)`), while `ReplayGain.MAX_LINEAR = 4f` caps the
linear value. This violates the project rule "extract shared functions into a single shared/common
file, no duplication," and the two caps can silently drift apart.

What to do:
- Pick one home for the gain math. Recommended: keep it in `ReplayGain` as the single source of
  truth (a named cap constant in dB, e.g. `MAX_BOOST_DB = 12f`, and a small helper that converts a
  capped dB value to a linear multiplier and/or a millibel boost).
- Make `PlayerService.applyGain()` call that helper instead of recomputing `10f.pow(...)` and
  hardcoding `12f` / `* 100`.
- Delete whatever is genuinely unused after consolidation (`toLinear`, `MAX_LINEAR`, `PREAMP_DB`)
  or wire it into `applyGain` so it is actually used. Do not leave dead public API.
- Keep behavior identical: attenuation (gain <= 0 dB) via `player.volume`, boost (gain > 0 dB) via
  `LoudnessEnhancer` in millibels (1 dB = 100 mB), passthrough when disabled or no tag.

Verification:
- `grep -rn "toLinear\|MAX_LINEAR\|PREAMP_DB\|pow" app/src/main/java` shows a single home for the math.
- `./gradlew :app:compileDebugKotlin`
- `./gradlew :app:lintDebug`
- Manual: a file with a negative track-gain tag attenuates; one with a positive tag boosts; an
  untagged file plays at unity.

---

## Prompt 2: Move persisted-settings reads off the main thread (ANR / StrictMode risk)

Several hot paths do synchronous SQLite reads on the UI/main thread through `Settings.get(...)`,
which runs `AppDb.db(context).rawQuery(...)`:
- `MainActivity.followPlayingTrack()` calls `Settings.isFollowEnabled(this)` on **every track
  transition** (`onMediaItemTransition`).
- `PlayerService.applyGain()` calls `Settings.isReplayGainEnabled(this)` on **every** metadata,
  track-transition, and audio-session callback.
- `MainActivity.onCreate` and Compose callbacks read theme/roots/loop/follow synchronously.

These are small single-row queries, but they are disk I/O on the main thread, fire repeatedly per
track, and would trip StrictMode / risk ANRs on slow storage. The two toggles read on every callback
(`replaygain`, `follow`) almost never change, so re-reading the DB each time is wasteful.

What to do:
- Cache the frequently read toggles in memory and read the DB only when needed:
  - In `PlayerService`, hold the ReplayGain-enabled flag in a field, initialize it in `onCreate`,
    and update it when the `CMD_REPLAYGAIN` custom command arrives (the UI already sends it on
    change via `sendReplayGainChanged()`). Have `applyGain()` read the field, not the DB.
  - In `MainActivity`, cache the follow flag (it already has setting callbacks via `onFollowChange`)
    and avoid a DB read per `onMediaItemTransition`.
- Do not change the persisted-state ownership: `Settings` remains the only writer/reader of the DB;
  this is just caching what `Settings` returns. Keep the on-disk value authoritative on startup.

Verification:
- `./gradlew :app:compileDebugKotlin`
- Enable StrictMode (or inspect logs) and confirm no disk-read violations on track transitions.
- Manual: toggling ReplayGain and Follow still takes effect immediately; values persist across a
  cold restart.

---

## Prompt 3: Clear the sticky connection error on successful reconnect

`MainActivity.errorState` is set to `"Connect: ..."` when building the `MediaController` fails in
`onStart`, but it is never cleared. After a transient connection failure the error string stays in
`NowPlaying` for the rest of the process lifetime even once a later `onStart` connects successfully,
because the success branch does not reset `errorState`.

What to do:
- On a successful controller connection in `onStart` (right after `controllerState.value = c`),
  clear `errorState.value = null`.
- Optionally also clear it when a new connection attempt begins, so a stale message from a previous
  failure doesn't linger during a retry.

Verification:
- `./gradlew :app:compileDebugKotlin`
- Manual: force a connect failure (or reason about the flow), then a successful reconnect, and
  confirm the now-playing area no longer shows the old `Connect:` text.

---

## Prompt 4: Persist the legacy single-folder migration instead of re-running it every read

`Settings.getRoots()` migrates the legacy `KEY_FOLDER` value into a one-element list when `KEY_ROOTS`
is absent, but it never writes the migrated list back to `KEY_ROOTS`. As a result the migration runs
on every `getRoots()` call (which happens on each launch and elsewhere), and the legacy key is never
retired. If the user never adds a second root via `addRoot` (the only path that writes `KEY_ROOTS`),
the app keeps depending on the legacy key indefinitely.

What to do:
- When `getRoots()` falls back to the legacy `KEY_FOLDER` and finds a value, write it once to
  `KEY_ROOTS` (and optionally clear `KEY_FOLDER`) so the migration is one-time and idempotent.
- Keep the return value identical; only add the persistence side effect.
- Watch for re-entrancy/recursion if you call `setRoots` from inside `getRoots`.

Verification:
- `./gradlew :app:compileDebugKotlin`
- Manual or unit reasoning: with only `KEY_FOLDER` set, the first `getRoots()` populates `KEY_ROOTS`;
  subsequent reads no longer touch the legacy key and the roots list is unchanged.

---

## Prompt 5: Clear the now-playing labels when the playing root is removed

`MainActivity.removeRoot()` stops playback when the removed root is the one currently playing
(`pause()` + `clearMediaItems()` + `stop()`, and it nulls `playingFolderId` / `playingDocIdState`).
But it does not clear the now-playing title/subtitle shown by `NowPlaying`. Those labels are driven
by `onMediaMetadataChanged`, and after `clearMediaItems()` no new metadata event resets them, so the
stale track title/path can remain on screen even though playback was stopped and its folder is gone.

What to do:
- When `removeRoot` stops the playing-this-root case, also trigger the existing label-clear path
  (`clearTitleTickState.value++`, the same mechanism used by `goHome`/`goUp`), so the now-playing
  title and path are cleared.
- Make sure the increment only happens in the branch that actually stopped playback, not on every
  removal.

Verification:
- `./gradlew :app:compileDebugKotlin`
- Manual: start playback from a root, then remove that root from the home list, and confirm the
  now-playing title/path clear immediately along with the stopped playback.

---

## Prompt 6: Harden the AppDb schema upgrade so `settings` always exists

`AppDb.Helper` is at version 2. `onCreate` creates the cache tables and the `settings` table, while
`onUpgrade` intentionally drops/recreates only the cache tables to preserve `settings`. This is
correct only if every prior installed version already had a `settings` table. There is no defensive
guarantee: if any historical schema reached a device without `settings`, `onUpgrade` would not create
it and the first `Settings.get()` would crash with "no such table: settings".

What to do:
- Make the `settings` table creation defensive and idempotent: use
  `CREATE TABLE IF NOT EXISTS settings(...)` and ensure `onUpgrade` (or a shared
  `ensureSchema(db)` helper called from both `onCreate` and `onUpgrade`) guarantees `settings`
  exists without dropping its data.
- Keep the existing behavior of rebuilding the cache tables on upgrade; only the `settings` table
  must be create-if-missing, never dropped.

Verification:
- `./gradlew :app:compileDebugKotlin`
- Reason through the upgrade path (and, if practical, test an install-over from an older build):
  cache is rebuilt, `settings` rows survive, and a fresh install still works.

---

## Prompt 7: Decide and document whether disabling Follow should also drop the playing-track highlight

In `MainActivity.followPlayingTrack()`, when `Settings.isFollowEnabled()` is false the function sets
`playingDocIdState.value = null` and returns early. This conflates two distinct things: "follow"
(auto-navigate the browser to the playing track's folder and center it) and "highlight" (mark the
currently playing file wherever it is already visible). With Follow off, the playing track is never
highlighted even when the user is already looking at its folder, which may be surprising — the
Settings label is "Follow playing", not "Highlight playing".

What to do (pick one and make it intentional):
- If the highlight should be independent of follow: always compute and set `playingDocIdState` from
  the item's `mediaId`, and gate only the navigation (`selectedRoot`/`treeUri`/`path` updates) on
  `isFollowEnabled`.
- If the conflation is intended: leave the behavior but add a short code comment (and consider the
  Settings string) clarifying that the toggle controls both navigation and highlight.

Verification:
- `./gradlew :app:compileDebugKotlin`
- Manual: with Follow off, browse to the folder of the playing track and confirm the resulting
  highlight behavior matches the documented decision.

---

## Prompt 8: Stop the boost from silently vanishing when LoudnessEnhancer is unavailable

In `PlayerService`, `enhancer` is created in `onAudioSessionIdChanged` inside a `try/catch` that
yields `null` on failure (device without the effect, or transient error). `applyGain()`'s boost
branch (`capped > 0`) only acts `enhancer?.let { ... }`; if the enhancer is null the positive gain is
dropped entirely and the track plays at unity with no indication. There is also no fallback and no
log, so a user with a ReplayGain-tagged-quiet library on such a device gets no normalization for
boosts while attenuation still works.

What to do:
- At minimum, make the failure observable (a single debug log when `LoudnessEnhancer` construction
  fails) so this is diagnosable rather than silent.
- Consider a graceful degradation decision: if no enhancer is available, document that positive
  ReplayGain is a no-op (boosts cannot exceed unity via `player.volume`), or clamp the whole feature
  to attenuation-only on such devices. Do not introduce clipping.
- Keep gain `<= 0 dB` (attenuation via `player.volume`) working regardless of enhancer availability.

Verification:
- `./gradlew :app:compileDebugKotlin`
- `./gradlew :app:lintDebug`
- Manual or reasoning: simulate enhancer creation failure and confirm the chosen behavior
  (logged no-op or attenuation-only) instead of a silent disappearance.
