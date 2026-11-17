# Sonora 0.8.2-beta01 validation

VersionCode 14 keeps the existing signing certificate and Room schema v5. Public Spotify links and Spotify ZIP/JSON files now resolve and save in one flow, without selecting playlists, reviewing songs or confirming a second import preview. The best available YouTube candidate is selected automatically, including approximate/ambiguous matches and metadata without duration. All selected export files are imported; saved songs go to Favorites. Public YouTube Music links also save directly after **Import playlist**. Takeout files retain their existing destination preview.

## Automated checks

- 147 JVM/Robolectric tests: 145 passed, two unrelated opt-in probes skipped, no failures. Live public-import probes were enabled.
- Public Spotify returned 50 metadata entries. A three-song sample found three playable YouTube references and reached the save callback automatically in 607 ms, excluding the initial metadata request. Public YouTube Music loaded 200 tracks across two pages. These measurements describe this run, not guaranteed network performance or recording identity.
- The bounded-worker test processes 207 source entries using 203 distinct searches, with a peak of three simultaneous requests and preserved source order. Simulated service time is 2,520 ms versus 7,470 ms sequentially. Repeated songs across lists share one lookup, and imports continue beyond the old 200-search cutoff and past empty results.
- Regression cases cover approximate/ambiguous versions, missing durations, invalid references, partial search failures/timeouts, zero matches, empty lists, cancellation during loading/searching, duplicate starts, the final save transaction, source identity, favorites, order and duplicate prevention. Direct YouTube import/reimport is verified against Room.
- All five languages have 637 matching resources and 642 explicit translated calls. Formatting, legacy API 28 locale behavior and untranslated user content are checked.
- Android lint: no errors, 22 warnings. All three publishing guard tests pass.

## Device and upgrade checks

Seven release-APK checks passed on API 35 at the normal 1080 × 1920 / 420 dpi display: all five languages and activity recreation; clipboard/provider validation; automatic Spotify completion and reimport; Takeout reimport; player/queue shuffle; cache-only music/podcast decoding and seeking; and four-tab navigation. The language, clipboard/provider and automatic Spotify checks also passed at 320 × 570 dp (three checks).

The Spotify device fixture uses parsed export metadata with an already-resolved YouTube reference and an unavailable entry, then exercises the production presenter, real Room save and completion UI for both public-link and export entry points. It verifies that no intermediate selection/review/preview appears, that skipped entries are counted, and that reimport adds no duplicate. Live matching is checked separately by the JVM probe. Offline playback uses generated music/podcast fixtures; no new live audio download is claimed.

The pinned, non-debuggable APK updated the installed signed 0.8.1 using `adb install -r`, without uninstalling or clearing data. Before/after comparison found identical rows in all checked tables: 13 tracks, one playlist and entry, one queue checkpoint, two podcast feeds, 500 episodes, four progress records and five listening-preference records.

See [measurements](measurements/release-0.8.2/checks.json) and [normal/compact screenshots](screenshots/release-082/). Captures use AndroidX's app screenshot API because the ATD emulator disables normal display drawing. Final DEX, manifest and resource hashes are compared with the tested release before publication.

## Limits

Automatic matching is best effort and may choose another version of a recording. Entries with no result are skipped; failed searches are reported and can be retried by reimporting. When a nonempty source has no matches, no library write occurs and the app shows an error. Reimports merge canonical YouTube references; a different URL is a distinct library entry.

Spotify can expose only part of a public playlist. The link dialog and result retain this notice and the ZIP/JSON alternative. No Spotify audio, private account access or OAuth is used. Import runs while the app is open and is not a resumable background job. Device checks use the API 35 emulator and do not establish behavior on every physical device or font scale.
