# Code & logic audit #8 — findings

Audit of the code changed/added over the past week (the v0.3 → v0.5 UI rework and the
audiobook/history work): `MainActivity.kt`, `Settings.kt`, `PlayerService.kt`, `FolderCache.kt`,
`DurationCache.kt`, `MusicScanner.kt`, `AppDb.kt`. Each finding is written as a self-contained
prompt for an LLM.

Overall the code is in good shape: persistence (in-memory cache + single-thread writer, key
deletion instead of empty rows), concurrency (rw-locks, chunked SQLite queries, `ensureActive`
cancellation), and lifecycle/book-mode handling are carefully designed and well commented. The
findings below are minor/edge-case.

---

## Finding 1 — "Remaining time" readout can render negative near track end
**Confidence: High. Severity: Low (cosmetic, transient, only with the Remaining-time setting on).**

**Problem:**
The playback-bar right-edge readout computes the remaining time as a raw subtraction
(`MainActivity.kt:1715`):

```kotlin
if (remainingTime) "-${formatTime(durationMs - leftMs)}"
else formatTime(durationMs)
```

`leftMs` is `positionMs` (the 500 ms-polled `controller.currentPosition`, coerced `>= 0`) when not
scrubbing. At the very end of a track `currentPosition` can momentarily equal or slightly exceed
`duration`, making `durationMs - leftMs` zero or negative. `formatTime` does no clamping
(`MainActivity.kt:1124-1130`): for a negative ms it produces malformed output such as `0:-1`, so the
readout briefly shows e.g. `-0:-1`. It is a single poll tick at most and self-corrects, but it is
visible.

**Solution Prompt:**
Clamp the remaining-time difference so it can never go negative before formatting. In the playback
bar (`MainActivity.kt:1714-1718`) wrap the subtraction with `.coerceAtLeast(0L)`. Optionally harden
`formatTime` itself to coerce its argument to `>= 0` so no caller can ever render a negative
timestamp. Verify the book-progress `timeText` path (`MainActivity.kt:1753-1755`), which already
coerces `playedMs` into `0..totalMs`, stays correct.

---

## Finding 2 — Fast-forward (+30s) seeks to 0 before the track duration is known
**Confidence: High. Severity: Low (edge case; brief window after a track loads).**

**Problem:**
The +30s button clamps its target against the polled duration (`MainActivity.kt:1925-1930`):

```kotlin
val target = (positionMs + SEEK_STEP_MS).coerceAtMost(durationMs)
controller?.seekTo(target)
positionMs = target
```

`durationMs` is `controller.duration.takeIf { it > 0L } ?: 0L` (`MainActivity.kt:1604`). In the brief
window after a track is loaded but before its duration is reported, `durationMs` is `0`, so
`coerceAtMost(0)` makes `target = 0` and the button jumps playback to the start of the track instead
of being the intended no-op. The rewind (−30s) button is unaffected because it only uses
`coerceAtLeast(0L)`.

**Solution Prompt:**
Guard the fast-forward action so it does nothing while the duration is unknown. In the +30s
`onClick` (`MainActivity.kt:1925-1930`), only seek when `durationMs > 0`; otherwise return without
touching the player. Alternatively, clamp against `controller.duration` read live inside the handler
rather than the polled `durationMs` snapshot. Keep the immediate `positionMs = target` feedback only
on the path that actually seeks.

---

## Finding 3 — Theoretical read-during-clear window in `Settings.clearRootState`
**Confidence: Low. Severity: Very Low (not reachable through the current UI).**

**Problem:**
`clearRootState` nulls all cached keys under the tree prefix while holding `lock`, then enqueues the
DB `DELETE` on the background writer (`Settings.kt:299-315`). A key under that tree that is not yet
in the in-memory cache is only removed by the asynchronous DB delete. If some other thread called
`get()` for such a key after the cache pass but before the writer's delete ran, it would read the
still-present row from the DB and re-cache the stale value, which would then survive until process
restart (the row is gone from disk but lingers in memory).

In practice this is unreachable: `clearRootState` is only called from `removeRoot`
(`MainActivity.kt:703`), after which the removed tree's book keys are never read again (the browser
cannot navigate into a removed root). Documented as a note, not an active bug.

**Solution Prompt:**
If you want to close the window regardless of caller behavior, perform the DB delete and the cache
invalidation under the same ordering guarantee: either run the `DELETE` synchronously under `lock`,
or have the writer task re-null the affected cache keys after the delete completes so any value
re-cached in the meantime is dropped. Add the `loaded` marker for the cleared keys so a later `get()`
returns the cached `null` without re-reading the DB. Only do this if the invariant is considered
worth the extra complexity; otherwise leave a comment documenting that `clearRootState` relies on no
concurrent reads of the removed tree's keys.

---

## Summary

| # | Finding | File(s) | Severity |
|---|---------|---------|----------|
| 1 | "Remaining time" readout can render negative near track end | MainActivity.kt | Low |
| 2 | Fast-forward (+30s) seeks to 0 before duration is known | MainActivity.kt | Low |
| 3 | Theoretical read-during-clear window in `clearRootState` | Settings.kt | Very Low |
