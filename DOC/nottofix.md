# Code & logic audit #9 — verified non-issues (not to fix)

Audit of the code added/changed since audit #8 (the sleep timer, opt-in Auto Backup, accent-color
picker, skip-silence, real-time `AutoLevel`, recent-folder history, and the `cache.db` split):
`PlayerService.kt`, `AutoLevel.kt`, `MyBackupAgent.kt`, `Settings.kt`, `AppDb.kt`, `CacheDb.kt`,
`FolderCache.kt`, `DurationCache.kt`, `MainActivity.kt`, the manifest and
`res/xml/data_extraction_rules.xml`.

Overall the code is in very good shape. All three findings from audit #8 are resolved (negative
remaining-time clamp, the +30s no-op before duration is known, and the `clearRootState` note). The
new code — service-owned sleep timer, mutually-exclusive volume-leveling paths, runtime-gated
backup, the split rebuildable cache — is carefully designed and well commented.

No real bugs were found. The two items below were investigated and are deliberately **not** worth
fixing; recorded here so a future audit doesn't re-raise them.

---

## Non-issue 1 — Sleep timer arms on `elapsedRealtime` but fires on the `Handler` (uptime) clock
**Confidence: High. Verdict: not a real bug — unreachable in practice.**

`armSleepMinutes` records the deadline as `SystemClock.elapsedRealtime() + delay` (used by the
dialog's countdown) but schedules the actual pause with `saveHandler.postDelayed(sleepRunnable,
delay)` (`PlayerService.kt:231-237`). `Handler.postDelayed` runs on the `uptimeMillis` clock, which
**stops** during deep sleep, whereas `elapsedRealtime` keeps counting. So the two clocks could
diverge and the pause could fire later than the countdown predicted.

Why it can't be observed: a fixed sleep timer exists to stop *active* playback. While audio plays,
the media foreground service holds the CPU awake, so `uptimeMillis` and `elapsedRealtime` advance
together — no divergence. The only way to make them diverge is to arm the timer, then manually pause
playback and let the device doze; but then the timer would be pausing already-paused playback, i.e.
a no-op anyway. There is no user-visible scenario where the timer fires at the wrong time.

**Decision:** leave as-is. Switching to an `AlarmManager`/`elapsedRealtime`-based wake-up would add a
permission and complexity for a case that can't happen.

---

## Non-issue 2 — `BackupManager.dataChanged()` is called right after the async flag write
**Confidence: High. Verdict: not a real bug — no race in practice.**

Toggling Auto Backup writes the flag via `Settings.setBackupEnabled` (which enqueues the write on the
background single-thread writer) and then immediately calls `BackupManager(this).dataChanged()`
(`MainActivity.kt:332-338`). The backup agent reads the flag straight from the DB in a **separate
process** with a cold `Settings` cache (`MyBackupAgent.onFullBackup`). In principle the enqueued
write could still be in flight when the agent reads.

Why it's safe: `dataChanged()` only *schedules* a backup pass; the framework batches and runs it much
later (typically hours, and never while the app is in the foreground). The one-row write completes in
milliseconds on the writer thread and is autocommitted/durable long before any backup actually runs,
so the agent always sees the intended flag value.

**Decision:** leave as-is. Forcing the write to flush synchronously before `dataChanged()` would add a
blocking disk wait on the UI thread to defend against a window that the framework's own scheduling
already closes.

---

## Summary

| # | Item | File(s) | Verdict |
|---|------|---------|---------|
| 1 | Sleep timer arm clock (elapsedRealtime) vs fire clock (uptime) | PlayerService.kt | Not a bug — unreachable while playing |
| 2 | `dataChanged()` right after the async backup-flag write | MainActivity.kt, MyBackupAgent.kt | Not a bug — no race given backup scheduling |
