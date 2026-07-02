# Code & logic audit #10 — findings

Audit of the features added in the last two weeks (builds ~184 → 228) that were not yet covered by
audits #8/#9: the **generated inter-track gap** (`GapMediaSourceFactory`, placeholder durations,
skip-silence exclusivity), the **UI language selection** (`AppLocalizer`, `assets/i18n.json`,
`lw`/`loc`), the **configurable seek step**, the **compact Settings dropdowns**, the effects-attach
fix (`applyNorm`, 46e729c), and the live ▶/● book-marker fix from audit #9 (b7c63e5). Files:
`PlayerService.kt`, `MainActivity.kt`, `Settings.kt`, `AppLocalizer.kt`, `DurationCache.kt`,
`assets/i18n.json`.

The i18n table was machine-checked: every `lw()`/`loc()` key used in code exists in `i18n.json`,
every key has both `ru` and `ua` translations, and no value carries trailing punctuation — no
findings there. Each finding below is a self-contained prompt.

---

## FIXED — Finding 1 — Live ▶/● book markers trust the global `playingDocId` without checking that the playing queue is this book
**Confidence: High (logic verified). Severity: Medium-low (misleading UI, no data damage).**

**Problem:**
`resumeFileIndex` (`MainActivity.kt:1778-1793`) prefers a "live" index derived from `playingDocId`
whenever the browsed folder is a book: `playingDocId?.let { p -> files.indexOfFirst { it.documentId
== p } }`. `playingDocId` is global — it is whatever file the player currently plays, from *any*
queue. `MusicScanner.collectAudio` (`MusicScanner.kt:123-154`) does **not** exclude book subfolders
from a recursive music walk, so playing an ancestor folder as shuffled music includes the book's
files. When such a music queue reaches a file inside a flagged book folder, browsing that book shows
▶ on the shuffled music track and ● "played" dots on every file before it — none of which reflects
the book's saved resume point. Worse, with Follow enabled (default on), `followPlayingTrack`
(`MainActivity.kt:704-726`) auto-navigates the browser into that folder, so the false markers appear
without the user doing anything. The stale saved resume point is hidden while this happens (nothing
is persisted wrongly — `bookFolderKey` is null for the music queue — it is purely display).

**Solution Prompt:**
Only use the live `playingDocId` path when the playing queue actually *is* the browsed book.
`MainActivity` already tracks `playingFolderKeyState` (the live queue's folder key, set in
`startQueue`) and `playingAbookState`. Pass the playing queue's book identity down to
`FolderBrowser` (e.g. a `playingBookKey: String?` that is non-null only when `playingAbook`), and in
the `resumeFileIndex` effect use `liveIndex` only when `playingBookKey == bookKey`; otherwise fall
back to the saved `Settings.getBookPos` read as now. Keep the effect keys in sync (replace the raw
`playingDocId` key with the guarded pair) so markers still advance while the book itself plays.

**FIXED:** `playingBookKey` (the live queue's folder key, null for music) is passed down to
`FolderBrowser`; the live index is used only when it equals the browsed `bookKey`, otherwise the
markers fall back to the saved resume point.

---

## FIXED — Finding 2 — With a gap set, queue install runs one synchronous SQLite query per track on the main thread
**Confidence: High (call path verified). Severity: Low-medium (main-thread I/O, scales with queue size).**

**Problem:**
`GapMediaSourceFactory.createMediaSource` (`PlayerService.kt:472-483`) calls
`DurationCache.peek(app, mediaItem.mediaId)` — a synchronous `rawQuery` on `app.db`
(`DurationCache.kt:58-60`) — for every media item. ExoPlayer materializes a `MediaSource` for
**every** item of the playlist inside `setMediaItems`, synchronously, on the application main
thread. So starting a big shuffled music queue (a root with thousands of files) with
`Gap between tracks` > 0 performs thousands of point queries plus `ConcatenatingMediaSource2`
builds on the main thread in one burst. Each query is an indexed PRIMARY-KEY lookup (fast when
warm), but a cold page cache or large `durations` table makes queue start visibly jankier, and it
is disk I/O on the main thread regardless. With gap = 0 the factory returns after one cached
`Settings` read, so the cost appears only when the feature is on.

**Solution Prompt:**
Avoid per-item main-thread queries when building a gapped queue. Options, pick one: (a) add a
small in-memory map cache in `DurationCache` (uri → ms, filled by `durations()`/`store()` and by a
single batched `SELECT ... WHERE uri IN (...)` the first time `peek` misses) so `createMediaSource`
hits memory; or (b) have `MainActivity` pre-resolve the cached durations for the queue's uris off
the main thread (it already has the item list before `setMediaItems`) and hand them to the service,
falling back to `GAP_PLACEHOLDER_MS` for misses. Keep `peek`'s semantics (null for missing/0 rows)
and don't add `MediaMetadataRetriever` work anywhere on this path.

**FIXED:** `DurationCache` gained an in-memory mirror (uri → ms, absent rows remembered as
`UNKNOWN_MS`): `preload()` batch-reads it off the main thread before a gapped queue is installed
(called from `playFolder`/`playFile`), `peek` serves from it (a miss falls back to one indexed
query and is remembered), `store`/`remove` keep it coherent, and `FolderCache.clear`/`clearRoot`
drop it via `clearMem()`.

---

## FIXED — Finding 3 — The inter-track gap also applies to audiobook queues and globally locks out Skip silence
**Confidence: High (behavior). Severity: Low (design/UX; decide intent).**

**Problem:**
`GapMediaSourceFactory` appends silence to **every** media item (`PlayerService.kt:474-481`),
including book queues, and `effectiveSkipSilenceEnabled` (`PlayerService.kt:381-382`) turns skip
silence off globally whenever any gap is set, mirrored by the locked switch in Settings
(`MainActivity.kt`, Skip silence row). Skip silence was documented as a books/podcasts feature
("so books/podcasts play tighter", `Settings.kt:236`), so a user who sets a 2s gap for music
silently loses skip-silence in every audiobook, and their books additionally acquire a 2s pause
between chapters that nobody asked for. The two features only genuinely conflict within the *same*
queue.

**Solution Prompt:**
Decide the intended scope. If the gap is a music-only feature (recommended): in
`GapMediaSourceFactory.createMediaSource`, return the plain source when the item was stamped as a
book — the flag is already on every item's metadata extras (`MusicScanner.EXTRA_IS_BOOK`), readable
via `mediaItem.mediaMetadata.extras`. Then make skip-silence effective per queue type: apply
`skipSilenceEnabled = Settings.isSkipSilenceEnabled(...)` when the active queue is a book
(`bookFolderKey != null`) and `... && gap == 0` when it is music, re-applying on `CMD_BOOK_MODE` as
well as `CMD_SKIP_SILENCE`/`CMD_TRACK_GAP`; unlock the Settings switch accordingly (the lock hint
would only describe music). If instead the gap is deliberately universal (a chapter pause), leave
the code and instead state that in the Settings subtitle and in `README.md`, so the skip-silence
lockout for books is documented rather than surprising.

**FIXED:** the gap is now music-only — `GapMediaSourceFactory` passes items stamped
`EXTRA_IS_BOOK` through untouched; `effectiveSkipSilenceEnabled` keeps skip-silence live for a
book queue regardless of the gap (re-applied on `CMD_BOOK_MODE`); the Settings switch is no
longer locked, its caption says "Books only while a gap is set" while a gap is active, and the
gap subtitle now reads "Silent pause between music files".

---

## FIXED — Finding 4 — Unknown persisted language code crashes the Settings screen (`languages.first {}`)
**Confidence: High (code path). Severity: Low (needs a stale/foreign stored value to trigger).**

**Problem:**
The Language dropdown resolves its label with `languages.first { it.code == code }`
(`MainActivity.kt:2765`), which throws `NoSuchElementException` when the persisted code has no
entry in `i18n.json`'s `_lang` block. `Settings.getLanguage` (`Settings.kt:279-281`) returns the
stored string unvalidated — unlike its siblings `getTrackGapSeconds`/`getSeekStepSeconds`, which
sanitize against their option lists. A code can go stale via a future edit of `i18n.json`
(renaming `ua` → `uk`, dropping a language), an Auto Backup restore from a newer app version, or a
hand-edited DB — and then merely opening Settings crashes the app, with no way to fix it from the
UI.

**Solution Prompt:**
Sanitize at the source, matching the pattern used by the other settings: make
`Settings.getLanguage` return the stored code only if `AppLocalizer.languageOptions()` contains it
(fall back to `AppLocalizer.DEFAULT_LANGUAGE` otherwise) — note `ensureLoaded` runs before the
first read in `MainActivity.onCreate`, so the options are available. Optionally also harden the
dropdown label with `firstOrNull { ... }?.labelKey ?: code` so the UI can never throw on a bad
value. Keep `lw()` behavior unchanged (unknown codes already fall back to English text).

**FIXED:** `Settings.getLanguage` now validates the stored code against
`AppLocalizer.languageOptions()` (falling back to `DEFAULT_LANGUAGE`; validation skipped only if
the options aren't loaded yet), and the dropdown label uses `firstOrNull { … } ?: code` as a
second guard.

---

## FIXED — Finding 5 — 100 dp `SettingDropdown` clips long Russian/Ukrainian labels (Theme: "Системная"/"Системна")
**Confidence: Medium-high (measured estimate, not run on device). Severity: Low (cosmetic, ru/ua only).**

**Problem:**
`SettingDropdown` fixes the button at `SETTING_DROPDOWN_WIDTH` = 100.dp (`MainActivity.kt:143,
3007`) and renders the selected label with `maxLines = 1, softWrap = false` and no overflow mode
(default `Clip`) at 18.sp (`MainActivity.kt:3011-3013`). After content padding (14+8 dp) and the
"▾" glyph, roughly 65 dp remain for the text. English labels fit, but the Russian/Ukrainian Theme
labels "Системная"/"Системна" (and "Светлая"/"Тёмная" borderline) are wider than that at 18.sp, so
the selected value renders clipped (center-aligned text clips on both edges). The dropdown *menu*
items are unconstrained and fine; only the collapsed button clips.

**Solution Prompt:**
Make the collapsed label fit. Prefer shortening the ru/ua translations used in the button — the
dropdown pattern elsewhere already uses compact forms ("Выкл", "Теги", "Авто"); e.g. translate the
Theme options in `i18n.json` as "Система"/"Светлая"/"Тёмная" (ua "Система"/"Світла"/"Темна") or
similar ≤7-character forms. Alternatively add `overflow = TextOverflow.Ellipsis` plus
`textAlign = TextAlign.Start` as a safety net, or derive the button width from the widest option
label. Verify the result in all three languages on device.

**FIXED:** `SETTING_DROPDOWN_WIDTH` raised to 118 dp, the "System" translations shortened to
"Система" (ru and ua), and the collapsed label got `overflow = Ellipsis` as a safety net for any
future over-long translation. Worth an on-device eyeball in ru/ua.

---

## FIXED — Finding 6 — In ReplayGain mode a disabled-but-attached LoudnessEnhancer persists across untagged/attenuated tracks
**Confidence: Medium (consistency argument; no repro). Severity: Low (only matters if the random-stop theory holds for LoudnessEnhancer too).**

**Problem:**
The random-stops fix (46e729c) is built on the diagnosis that "a disabled effect left attached to
the session still perturbs the audio path" (`PlayerService.kt:78-81`), and `applyNorm` accordingly
keeps *unused modes* fully detached. But within ReplayGain mode, `applyReplayGain`
(`PlayerService.kt:413-434`) creates the `LoudnessEnhancer` lazily on the first positive-gain track
and afterwards only flips `enhancer?.enabled = false` for untagged (`db == null`) or attenuated
(`db <= 0`) tracks — leaving the effect attached-but-disabled on the session, exactly the pattern
the fix eliminated for inactive modes. If that diagnosis is right, long ReplayGain sessions that
mix boosted and non-boosted tracks retain the same exposure the fix was meant to remove.

**Solution Prompt:**
Make ReplayGain's non-boost paths consistent with the attach-nothing principle: in
`applyReplayGain`, call `releaseEnhancer()` instead of `enhancer?.enabled = false` in both the
`db == null` and `db <= 0f` branches. `ensureEnhancer()` already recreates it lazily on the next
boosted track, so the only cost is an effect create/release per gain-sign change — negligible next
to track transitions. Alternatively, if disabled-but-attached is now believed harmless for
`LoudnessEnhancer` specifically, add a comment at `PlayerService.kt:413` saying why it is exempt
from the rule stated at `PlayerService.kt:78-81`, so the asymmetry reads as deliberate.

**FIXED:** both non-boost branches of `applyReplayGain` now call `releaseEnhancer()` instead of
`enabled = false`; the enhancer is recreated lazily by `ensureEnhancer` on the next boosted track
(one cheap create/release per gain-sign change).

---

## Finding 7 — The 24 h gap placeholder can surface in the UI as the track duration
**Confidence: Medium (window depends on prepare latency). Severity: Low (transient cosmetic).**

**Problem:**
With a gap set, a track whose duration is not yet in `DurationCache` gets
`GAP_PLACEHOLDER_MS` = 24 h as its `ConcatenatingMediaSource2` placeholder
(`PlayerService.kt:69,477`). Until the progressive source finishes preparing and the real duration
replaces it, `controller.duration` reports ~24 h; `NowPlaying` polls it every 500 ms and shows it
directly (`MainActivity.kt:2230-2231`, bar/total at `2288-2345`). On a slow SAF provider (cold
start, USB/SD storage) the first poll(s) can catch the placeholder: the total time briefly reads
"24:00:05", remaining "-23:59:xx", the progress bar sits at ~0, and a tap/scrub or "+30s" during
that window seeks against the bogus 24 h scale. Cached tracks (any previously played book, or any
track after the first gapped listen) show the correct value immediately.

**Solution Prompt:**
Keep the placeholder for the resume-clamp guarantee but stop displaying it: in `NowPlaying`,
treat an implausible duration as unknown — e.g. share the placeholder threshold from
`PlayerService` (or a `Settings` const) and use
`controller.duration.takeIf { it in 1 until PLACEHOLDER_THRESHOLD_MS }` when setting `durationMs`,
so the bar stays hidden (`showBar` already requires `durationMs > 0`) until the real duration
arrives; seek gestures are then inert against it too. A threshold like 12 h is safely above any
real audio file. Don't shrink `GAP_PLACEHOLDER_MS` itself — it must stay longer than any file so
book resume seeks are never clamped.

---

## Verified non-issues (checked, not worth fixing)

- **`favoriteKeysCache` is read/written without the favorites lock** (`Settings.kt:130,336-341`):
  all readers and invalidators run on the main thread (Compose rows, toggle handlers); the writer
  thread only persists. No real race; synchronizing would add noise.
- **`ua` as the Ukrainian code** (ISO would be `uk`): the code is a purely internal persisted key
  of `i18n.json`'s `_lang` block, never fed to Android locale APIs. Renaming now would need a
  migration for zero benefit.
- **`saveBookPosition` during the generated trailing silence** stores a position up to `gap` s past
  the file's real end; on resume the seek lands in (or clamps at) the silence and rolls over
  normally, and the 15 s resume rewind usually erases it. Bounded by 5 s; harmless.
- **`AppLocalizer` relies on `JSONObject` key order** for the picker order: AOSP's `org.json` is
  backed by `LinkedHashMap`, and minSdk 31 guarantees that implementation. Safe.
- **i18n completeness**: cross-checked code ↔ `i18n.json` both directions (including multi-line
  `lw(when/if …)` call sites); every used key exists, every key is used, `ru`/`ua` rows are
  complete, values carry no trailing punctuation.

## Summary

| # | Finding | File(s) | Severity |
|---|---------|---------|----------|
| 1 | FIXED — Live ▶/● book markers follow any queue's `playingDocId`, not just this book's | MainActivity.kt | Medium-low |
| 2 | FIXED — Gapped queue install: per-track synchronous DB peek on the main thread | PlayerService.kt / DurationCache.kt | Low-medium |
| 3 | FIXED — Gap applies to books and globally locks out Skip silence | PlayerService.kt / MainActivity.kt | Low (design) |
| 4 | FIXED — Unknown stored language code crashes Settings (`languages.first {}`) | MainActivity.kt / Settings.kt | Low |
| 5 | FIXED — 100 dp dropdown clips ru/ua Theme labels | MainActivity.kt / i18n.json | Low |
| 6 | FIXED — ReplayGain keeps a disabled LoudnessEnhancer attached (vs. the random-stops diagnosis) | PlayerService.kt | Low |
| 7 | 24 h gap placeholder briefly shown as track duration / seek scale | PlayerService.kt / MainActivity.kt | Low |
