# Code & logic audit #9 — findings

Audit of the features added since audit #8: the browser **home button** (`goHome`), the
**favorite-folder star** shown in listings (`isChildFavorite` per row) and red delete button,
the **played/resume markers** (▶/●) in book listings, the **marquee iteration cap**, and the
**history cap** bump (5 → 7). Files: `MainActivity.kt`, `Settings.kt`. Each finding is a
self-contained prompt.

Overall the new code is sound: home/up share `clearNowPlayingIfStopped` so playback survives
navigation, the resume index is computed off-thread with one cheap DB read, and favorite keys are
derived consistently (`Settings.bookKey(tree, docId)`) on every path. The findings are
minor/cosmetic.

---

## Finding 1 — `isChildFavorite` parses the whole favorites list on every folder-row composition
**Confidence: High. Severity: Low (efficiency; main thread, scales with favorites count × rows).**

**Problem:**
Every folder row computes its glyph with `isChildFavorite(folder)` (`MainActivity.kt:1863`), which
runs `Settings.isFavorite` → `getFavorites` → `readEntries`. `readEntries` does a fresh
`JSONArray(raw)` parse and rebuilds the full `List<HistoryEntry>` on **each** call
(`Settings.kt:330-331, 361-375`). `Settings.get` is memory-cached so there is no DB hit after the
first read, but the JSON parse is not cached — so for a folder with N subfolders the favorites JSON
(up to `FAVORITES_MAX` = 15 entries) is parsed N times per (re)composition, on the main thread, and
again on every scroll/highlight/marquee-driven recomposition. `pinned` in the long-press and delete
dialogs (`MainActivity.kt:1945, 1995`) parses it again per dialog open.

**Solution Prompt:**
Read the favorites once per listing and pass a cheap membership lookup down. In `FolderBrowser`,
hoist a `Set<String>` of favorite keys (or the parsed `List`) into a single value derived once per
favorites change, and have the per-row glyph test membership against that set instead of calling
`isChildFavorite` (which re-parses) per row. Alternatively, cache the parsed favorites in `Settings`
alongside the string cache and invalidate it in `addFavorite`/`removeFavorite`/`removeFavoritesFor*`.
Keep the existing keying so a pin/unpin still refreshes the visible stars.

---

## Finding 2 — ▶/● book-progress markers are a snapshot from folder entry; they don't track live playback
**Confidence: High. Severity: Low (cosmetic staleness, only inside the playing book's open folder).**

**Problem:**
`resumeFileIndex` is recomputed by a `LaunchedEffect(contents, bookKey, filesAreBook)`
(`MainActivity.kt:1766`) — it keys on the listing and the book identity, **not** on playback
position or `playingDocId`. The book's resume point is saved periodically while it plays (and on
pause), so if the user sits in the playing book's folder listing while playback advances across
files, the ▶ resume marker and the ● played-dots stay frozen at the position captured when the
folder was entered; they only refresh on re-navigating into the folder or a rescan. The markers can
therefore lag the actually-playing track.

**Solution Prompt:**
If live markers are wanted, add `playingDocId` (and/or a low-frequency resume-position tick) to the
`LaunchedEffect` keys so the resume index recomputes as playback crosses files — but keep it cheap
(still one off-thread `getBookPos` read, no recursive walk). If the snapshot-on-entry behavior is
intentional, leave a one-line comment at `MainActivity.kt:1758-1766` stating the markers reflect the
last-saved resume point at folder-entry time, not live playback, so it isn't mistaken for a bug.

---

## Finding 3 — Adding a 16th favorite silently drops the oldest, despite the "never auto-evicted" comment
**Confidence: High (behavior). Severity: Low (surprising for manually pinned items, no feedback).**

**Problem:**
`addFavorite` caps with `.take(FAVORITES_MAX)` (`Settings.kt:336`), so pinning a 16th favorite
silently evicts the oldest pin. The section comment says favorites are "pinned by hand (never
auto-evicted) and capped higher" (`Settings.kt:325`), and `toggleFavorite` always shows
`"Added to favorites"` (`MainActivity.kt:779`) even when the add pushed another pin out. For
hand-curated items, losing one with no warning is surprising, and the comment overstates the
guarantee.

**Solution Prompt:**
Decide and align comment + behavior. Either (a) when at the cap, refuse the new pin and toast
something like "Favorites full (15)" instead of silently evicting, keeping the "never auto-evicted"
promise true; or (b) keep the FIFO eviction but fix the comment at `Settings.kt:325` to say the
oldest pin is dropped at the cap, and consider surfacing that in the toast. Pick whichever matches
the intended UX; do not leave the comment claiming an invariant the code breaks.

---

## Finding 4 — Comment says played files are marked ✔ but the code renders ● — FIXED
**Confidence: High. Severity: Trivial (doc nit).**

**Problem:**
The marker comment states "Files before it are 'played' (✔)" (`MainActivity.kt:1762`), but the glyph
actually rendered for played files is `"●"` (`MainActivity.kt:1905`). Harmless, but misleading when
reading the code.

**Solution Prompt:**
Update the comment at `MainActivity.kt:1762` to use ● (or whatever glyph the code settles on) so the
comment matches the rendered marker.

**FIXED:** both marker comments now say ● (filled dot) instead of ✔.

---

## Finding 5 — Enlarged 30.sp resume ▶ inside a 20.sp single-line row may inflate/clip that row — FIXED
**Confidence: Low. Severity: Low (cosmetic, visual only).**

**Problem:**
The resume row builds an annotated string whose ▶ span is `RESUME_GLYPH_SIZE` = 30.sp
(`MainActivity.kt:155, 1912`) inside a `Text` whose base `fontSize` is `FONT_LIST` (20.sp) with
`maxLines = 1`. A glyph 1.5× the line's font size can raise that single row's line height
(making the resume row taller than its neighbours) or get vertically clipped, depending on line-height
behavior. Worth an eyeball check on device.

**Solution Prompt:**
Verify the resume row's height/alignment against adjacent rows on device. If it jumps or clips, either
constrain the row's line height explicitly, reduce `RESUME_GLYPH_SIZE`, or render the enlarged ▶ as a
leading element with a fixed slot rather than an inline oversized span.

**FIXED:** ▶ is now drawn via an inline placeholder that reserves only a normal line's height
(`FONT_LIST`), with the triangle drawn unbounded (start-aligned) so the row no longer stretches; the
glyph was enlarged to 34.sp so it reads bigger than the page/note emoji.

---

## Summary

| # | Finding | File(s) | Severity |
|---|---------|---------|----------|
| 1 | `isChildFavorite` re-parses favorites JSON per folder row | MainActivity.kt / Settings.kt | Low |
| 2 | ▶/● markers snapshot at folder entry, don't track live playback | MainActivity.kt | Low |
| 3 | 16th favorite silently evicts oldest vs "never auto-evicted" comment | Settings.kt | Low |
| 4 | Comment says ✔ for played files, code renders ● — FIXED | MainActivity.kt | Trivial |
| 5 | 30.sp resume ▶ in a 20.sp single-line row may inflate/clip the row — FIXED | MainActivity.kt | Low |
