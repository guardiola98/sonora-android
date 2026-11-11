# Sonora 0.5.0-beta01 validation

Session: 11 November 2026 (Europe/Madrid), Debian Linux, JDK 21, Gradle 8.13, SDK 36/build-tools 35.0.0. Runtime: API 35 x86_64 Google ATD emulator, 1 GiB RAM/two virtual cores. Dependency versions are unchanged; discovery uses the existing NewPipeExtractor/OkHttp/Room stack. Previous [0.4 validation](VALIDATION-0.4-beta01.md) is archived.

## Build and deterministic checks

- `assembleDebug`, `testDebugUnitTest`, `lintDebug`, `assembleRelease` and `assembleReleaseAndroidTest` passed on the corrected production code. Lint reports **0 errors, 22 warnings** (existing dependency/UI/style suggestions).
- **97 deterministic unit tests passed**, 2 opt-in live JVM tests skipped (99 total). Coverage includes actual SQLite/Room migration from the tracked v4 schema to v5 while retaining songs, podcast data and queue checkpoints; older migrations remain tested.
- New tests cover ranking, confidence/missing dimensions, Unicode normalization, versions and URL/recording deduplication, artist diversity, merged candidate evidence, exclusion of unrelated spoken videos, feedback/search-history/cache persistence, local fallback on external failure, pagination retry, query cancellation and clearing during a non-cancellable page fetch.
- Radio tests cover off/manual policies, stale responses after edits, paused restoration without requests, failure cooldowns, actual elapsed-listening signals versus seeking, and **100 simulated transitions** with incremental refill and bounded recent context. This is a deterministic session-policy test, not five hours of real streaming.
- Ranking 10,000 candidates passes its time bound on this host. It is not an Android frame-time or 50,000-track physical-device benchmark.

## Real Android verification

**13 Android tests passed** on the final signed production APK.

| Check | Passed | Time |
| --- | ---: | ---: |
| Live search, pages, radio, Home, metadata and autoplay policy | 1 | 29.033 s |
| Live RSS podcast discovery/playback/resume/background | 1 | 21.459 s |
| Library/import/PCM/session/Activity/live YouTube | 5 | 114.681 s |
| Four-tab navigation | 1 | 4.011 s |
| Mixed queue/subscription/progress seed | 1 | 1.528 s |
| Force-stop/reinstall mixed queue restoration | 1 | 1.76 s |
| Paused radio checkpoint seed | 1 | 0.116 s |
| Force-stop/reinstall radio restoration | 1 | 1.701 s |
| Automatic preparation from the new Search | 1 | 5.778 s |

The screen/controller preparation check measured **241 ms** from Play to `isPlaying` plus timeline progression after the actual visible search result was automatically prepared. Preparation did not play audio or write history. This one sample excludes preparation time and is not an acoustic measurement or a general latency guarantee. [Raw checks](measurements/discovery-0.5/android-checks.json), [startup sample](measurements/discovery-0.5/automatic-preparation.json).

The final live discovery check used a real public “Obrint Pas La flama” search: **17 → 35** displayed results after paging. Radio added **four** distinct music candidates from Obrint Pas, Lax’n’Busto and La Gossa Sorda; “Llença’t” played through Media3 and continued with the Activity backgrounded. Seven Home recommendations loaded. Autoplay off removed pending automatic entries and ended normally; a subsequent two-track manual queue remained unchanged, including after recommendation-feedback commands.

Public catalog probes returned 14 albums, 20 playlists and 20 channels; the selected album/playlist each loaded 11 real tracks. MusicBrainz accepted an **artist-only** Obrint Pas match, with four attributed artist-level genre values; multiple matching recordings did **not** become an invented recording/album. ListenBrainz returned eight similar artists. These are observed responses for this session, not guarantees about other queries or future availability. The initial radio already had multiple source credits before optional enrichment was enabled; the evidence does not claim ListenBrainz caused each queue addition. [Raw final result](measurements/discovery-0.5/final-device.json).

Real screenshots from this final run: [Home](screenshots/discovery-home.png), [Search](screenshots/discovery-search.png), [Radio queue](screenshots/discovery-radio.png). They were captured through AndroidX from the actual Compose Activity, without compositing or mock recommendations.

The signed release is installed in place without uninstalling; versionCode is 6 and the persistent certificate remains `01ad2a63fd92aea2cbff97812fe90e3adc46c108f3c110558be80ea89cc209f2`. Schema v5 is additive; exported library backup format v2 is unchanged. Queue JSON v2 accepts old array checkpoints and restores radio seeds/reasons/recent context paused.

## Findings and corrections

- The first live radio test proved search/playback/background/autoplay policy, but visual review showed a Saturday Night Live comedy clip in public watch-page “related” results. This is why source relatedness is not treated as reliable music metadata. The corrected engine requires positive musical evidence, rejects obvious spoken/comedy candidates and merges catalog corroboration before deduplication. A regression reproduces that exact title. [Initial evidence](measurements/discovery-0.5/before-music-evidence-gate.json) is retained rather than presenting that run as acceptable recommendation quality.
- Partially matched artist metadata could divide one artist into verified/unverified diversity buckets. Normalized display-credit buckets now cap those together; the actual artist-identity score still requires accepted IDs.
- Clearing search during pagination left its loading flag visible; cancellation now clears that state and a non-cancellable-response regression passes.
- Explicit recommendation feedback is prevented from deleting songs from a manual queue. Restarting a finished song also restarts its elapsed-listening observation.
- A device pagination assertion initially required every previous URL to remain visible, which conflicts with the intentional folding of alternate uploads. It now checks that song families remain represented while URL duplicates are absent; the raw collection continues to retain all versions.
- The existing automatic-preparation test first selected a provisional local search hit that was subsequently replaced in the ranked results. It now waits for the final remote-ranked publication before checking the visible track; the same production APK passes without a player/preparation change.
- An early migration test fixture assumed every historical schema entity had an `indices` array; the fixture reader now accepts its absence. Production migration SQL did not require destructive changes.

## Distribution and limits

The release tooling checks a clean committed source, non-debuggable APK, application ID, pinned signing certificate and exact source provenance. Publication includes APK, GPL source ZIP, license, notices and SHA-256. DEX/manifest/resources from the final clean build must match the installed/tested release. Private signing material is not committed or uploaded.

The push workflow builds/tests/lints and uploads a debug APK plus reports; official signing continues locally with the existing key. The optional device workflow now includes `live_discovery`. Staged restart tests require the documented seed → force-stop/reinstall → verify sequence and skip the opposite stage deliberately.

The emulator had host audio disabled: decoder/player progression and background execution are real, but there was no human listening test. Physical lock-screen/Bluetooth/OEM battery handling, unstable mobile networks, acoustic startup latency and multi-hour recommendation quality remain on the [phone checklist](MANUAL_TESTS.md). Public music/metadata services can return incomplete, irrelevant, restricted or unavailable content. No DRM/access workaround or fabricated musical label was added. Read the [algorithm and source limitations](DISCOVERY.md).
