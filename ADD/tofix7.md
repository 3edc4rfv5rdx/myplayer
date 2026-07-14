# Code & logic audit #7 — findings

Audit of current `MainActivity`, `PlayerService`, `Settings`, `FolderCache`, `MusicScanner`,
`DurationCache`, build configuration, manifest, strings, and the current logic docs. Each finding is
written as a self-contained prompt for an LLM.

Verification performed:
- `./gradlew :app:compileDebugKotlin` — passed. Kotlin reported a corrupted incremental-compilation
  cache (`EOFException`) and fell back to non-incremental compilation; the final build was
  successful.
- `./gradlew :app:lintDebug` — passed.

---

## Finding 1 — Service book mode is cleared/installed after player mutations
**Confidence: Medium. Severity: Low-Medium (narrow race, but affects audiobook resume integrity).**

**Problem:**
The service tracks audiobook resume state with its private `bookFolderKey` (`PlayerService.kt:52-54`,
`188-191`). The activity changes that state via the custom `CMD_BOOK_MODE` command
(`MainActivity.kt:780-786`), but the ordering around player mutations is loose:

- `stopAndClearQueue` pauses, clears media items, and stops the player, but never sends
  `CMD_BOOK_MODE` with an empty key (`MainActivity.kt:564-578`). The UI state is cleared, while the
  service can keep the previous `bookFolderKey` until another queue is installed or the service is
  destroyed.
- `startQueue` sets shuffle/repeat, installs media items, prepares, starts playback, applies speed,
  and only then sends the new book mode (`MainActivity.kt:759-772`).

Usually this works because `onMediaItemTransition(PLAYLIST_CHANGED)` deliberately does not save and
the periodic save is delayed. Still, there is an avoidable stale-key window: starting plain music
after a book, or switching books after a clear, briefly leaves the service believing the previous
book is active while the player is already moving to a new queue. A fast error/transition or future
listener change could save the wrong URI under the old book key.

**Solution Prompt:**
Make service book mode a precondition of queue mutation:

1. In `stopAndClearQueue` and `exitApp`, send `CMD_BOOK_MODE` with an empty key before or immediately
   after pausing, and before clearing media items.
2. In `startQueue`, send the target book mode before `setMediaItems`/`prepare`/`play`, not after
   playback starts. For a book, install the new key first; for music, clear the key first.
3. Keep the existing UI state assignments (`playingAbookState`, `playingFolderKeyState`) aligned
   with the command ordering.
4. Add a manual regression scenario: play a book, pause/clear by navigating away, start plain music,
   then inspect that no music URI is written into the old book's saved position.

If Media3 command availability is a concern before the session is fully connected, centralize the
ordering in one helper and make failure visible in `errorState` or a debug log.

---

## Finding 2 — Removing future queued book items can leave the service tracking stale book state
**Confidence: Medium. Severity: Low-Medium (edge case during delete while a recursive queue plays).**

**Problem:**
`stopIfPlayingUnder` handles folder deletion while a recursive queue is active. If the current track
is inside the deleted folder, it stops and clears the queue (`MainActivity.kt:881-897`). If the
current track is elsewhere, it walks the queue and removes future items under the deleted folder
with `controller.removeMediaItem(i)` (`MainActivity.kt:887-894`).

That second path mutates the live playlist but does not reconcile the service's book-mode state,
the activity's `playingFolderId`/`playingFolderKeyState`, or the saved resume point. This is mostly
fine for deleting a future CD/subfolder from a book, but there are awkward edge cases:

- if all remaining queued items except the current one are removed, the book can now end early and
  clear the resume point as if the whole book was completed;
- if the deleted folder contained the saved resume file for the book, `Settings.clearBook` is called
  only for the deleted folder's own key (`MainActivity.kt:860-862`), not for the enclosing book root;
- if a non-current deletion removes every item after the current item, the user gets no indication
  that the active queue was shortened.

**Solution Prompt:**
Make deletion-aware queue mutation explicit. When deleting a folder that is not the current track
but has queued items:

1. Remove matching media items as today, iterating backwards.
2. If the active queue is an audiobook and the deleted subtree belongs to the same book root, decide
   whether to preserve or clear the book root's resume point. At minimum, if the saved resume URI is
   under the deleted subtree, clear the enclosing book root's `Settings` position so the next book
   start cannot target missing content.
3. If the removal leaves no playable items, call the same full stop/clear path used when the current
   track is deleted.
4. Keep `playingFolderId`, `playingFolderKeyState`, `playingAbookState`, and the service book mode
   consistent with the resulting queue.

Add a focused manual test scenario: play a book root with `CD1` and `CD2`, while a `CD1` track is
playing delete `CD2`, then press Next until the queue ends; verify resume and UI state are
intentional.

---

## Finding 3 — Removing a root leaves per-folder audiobook state behind
**Confidence: High. Severity: Low (stale state, surprising if the root is re-added).**

**Problem:**
`removeRoot` stops playback from that root, releases the persistable URI permission, removes the URI
from `Settings.roots`, and clears listing cache rows (`MainActivity.kt:612-625`). It does not remove
settings entries keyed by that root:

- `mode:<treeUri>\0<docId>`
- `pos:<treeUri>\0<docId>`
- `speed:<treeUri>\0<docId>`

Those keys remain in the single `settings` table forever. If the user later re-adds the same SAF
tree URI, old audiobook flags, resume positions, and speeds silently come back. That may be
convenient for accidental root removal, but it contradicts the "Remove folder" mental model and
leaves unbounded stale rows for roots that are never added again.

**Solution Prompt:**
Decide and implement one explicit policy for root removal:

- Preserve state intentionally: update README/confirmation text to say removing a root only removes
  it from the list and keeps audiobook progress for re-adding later.
- Or forget state: add a `Settings.clearRootState(treeUri: String)` method that deletes all
  `mode:`, `pos:`, and `speed:` rows whose key starts with the exact root prefix
  (`prefix + treeUri + "\0"`), call it from `removeRoot`, and include duration/cache cleanup as
  appropriate.

Do not use ad hoc string slicing in UI code; keep the cleanup inside `Settings` where the key format
is defined.

---

## Finding 4 — `Settings.clearBook` stores empty speed rows that mask future default speed changes
**Confidence: High. Severity: Low (subtle settings behavior).**

**Problem:**
`Settings.clearBook` writes an empty string for `KEY_SPEED_PREFIX + folderKey`
(`Settings.kt:191-195`). `getSpeed` handles this with:

```kotlin
get(context, KEY_SPEED_PREFIX + folderKey)?.toFloatOrNull() ?: getDefaultSpeed(context)
```

So runtime behavior is correct: an empty speed value falls back to the current default. But the row
still exists in the DB and the in-memory cache as a loaded key. This makes "cleared" and "never set"
look different internally, complicates any future migration/listing of customized book speeds, and
adds stale rows for deleted books/roots.

**Solution Prompt:**
Add delete semantics to `Settings` instead of representing deletion as an empty string. Implement a
private `remove(context, key)` that removes the key from the cache/loaded set (or sets it to null in
a well-defined way) and queues a `DELETE FROM settings WHERE key=?`. Use it for `clearBookPos`,
`clearBook`, and any root-state cleanup introduced for root removal. Preserve existing reads of old
empty-string rows for backward compatibility, but stop writing new empty rows.

---

## Finding 5 — The book-duration resolver can block a progress update on large cold books
**Confidence: Medium. Severity: Low (UI remains responsive, but progress stays approximate/late).**

**Problem:**
`NowPlaying` resolves durations for every file in the live book queue in one coroutine
(`MainActivity.kt:1436-1443`), and `DurationCache.durations` resolves missing entries sequentially
with `MediaMetadataRetriever` (`DurationCache.kt:22-38`). This runs on `Dispatchers.IO`, so it
should not freeze Compose, but a large cold SAF-backed book can keep `bookDurations` null for a long
time. Until the entire array is complete, the UI falls back to file-count progress, then jumps to
time-based progress when the last duration resolves.

**Solution Prompt:**
Make duration resolution incremental. Keep the existing persistent `durations` table, but expose a
flow/callback or chunked API that returns cached durations immediately, resolves missing files in
small batches, and updates the book progress as batches complete. Treat unknown durations as `0` or
exclude them from the time-based denominator until known; make the fallback behavior explicit so
the displayed percent does not jump wildly. Preserve cancellation on queue changes.

This is not a correctness blocker, but it improves the audiobook progress feature added after the
previous audit.

---

## Summary

| # | Finding | File(s) | Severity |
|---|---------|---------|----------|
| 1 | Service book mode is cleared/installed after player mutations | MainActivity.kt / PlayerService.kt | Low-Medium |
| 2 | Reconcile book state after deleting non-current queued items | MainActivity.kt / Settings.kt | Low-Medium |
| 3 | Root removal leaves audiobook state behind | MainActivity.kt / Settings.kt | Low |
| 4 | Cleared settings are stored as empty rows | Settings.kt | Low |
| 5 | Cold book duration resolution is all-or-nothing | MainActivity.kt / DurationCache.kt | Low |
