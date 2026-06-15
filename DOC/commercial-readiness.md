# Commercial-readiness gaps (non-feature)

What this project still lacks to count as a "mature commercial app" — limited to engineering
hygiene, data safety, compliance, and quality bars. Concrete user-facing features (sleep timer,
Bluetooth/Auto, widgets, etc.) are tracked separately and intentionally excluded here.

Ordered by priority.

## 1. Engineering hygiene (biggest gap)
- **No tests at all.** The audited logic (book resume, composite keys, races) needs unit tests for
  `Settings`, `MusicScanner.naturalCompare`, `DurationCache`, plus a few instrumented tests covering
  SAF navigation and playback wiring.
- **No CI.** Build + lint + tests should run on every commit; today everything rests on the manual
  `10-MakeRelease.sh`.
- **No crash/ANR visibility.** A third-party SDK conflicts with the offline ethos, but Play Console
  Vitals provides crash/ANR reporting with no code and no network.

## 2. User-data durability
- Audiobook positions and settings live in a single SQLite DB; a reinstall loses everything. For an
  audiobook-centric app this is the most expensive gap for the user. Add Android Auto Backup or an
  explicit export/import.

## 3. Store / legal
- Privacy policy and Play Data Safety declaration (even "we collect nothing" must be declared),
  content rating, target SDK kept current to Play requirements, Play App Signing. Without these the
  app cannot be published.

## 4. Accessibility & localization
- `contentDescription`/TalkBack support is partial; needs a real pass with TalkBack and large fonts.
- Currently English-only by design; commercial apps usually ship multiple languages.

## What deliberately stays out (not a gap)
- No equalizer, no media library, no internet. These are intentional exclusions and are correct.
  "Commercial" does not mean "feature-bloated."
