# Sonora 0.3.0-beta01 validation

Session: 11 November 2026 (Europe/Madrid), Debian Linux, JDK 21, Gradle 8.13, SDK 36/build-tools 35.0.0. Android runtime: API 35 x86_64 emulator. Existing dependency versions remain unchanged; RSS uses the platform XML parser and existing OkHttp. Previous [0.2 validation](VALIDATION-0.2-beta01.md) is archived.

## Completed checks

- `assembleDebug`, `testDebugUnitTest`, `lintDebug`, `assembleRelease` and `assembleReleaseAndroidTest` passed. A clean build was also completed; stale local Kotlin incremental outputs were cleared during development.
- **49 deterministic unit tests passed**, 2 opt-in live JVM tests skipped. Android lint: **0 errors, 21 warnings**.
- The signed release was installed with `adb install -r` over the existing signed 0.2 installation (versionCode 3), without uninstalling. VersionCode is now 4; the signing certificate is unchanged.
- **9 Android tests passed across the invocations below**, all using the signed, non-debuggable, unminified release configuration.

| Device checks | Passed | Time |
| --- | ---: | ---: |
| Real podcast discovery, RSS, subscription, audio, resume, background and progress | 1 | 22.437 s |
| Takeout import, PCM/session controls, Activity reopening and live YouTube | 4 | Within the 114.114 s regression invocation |
| Playlist editing/drag, export, preview and restore (isolated retry) | 1 | 7.900 s |
| Navigation through all four tabs | 1 | 4.138 s |
| Mixed music/podcast queue and subscription/progress seed | 1 | 1.657 s |
| Force-stop, `adb install -r`, then paused queue/subscription/progress restoration | 1 | 1.781 s |

The initial regression invocation had one failure in AndroidX Test's screenshot capture (`ViewCapture.forceRedraw`, null Handler during a dialog transition), after successful playlist creation, drag and rename assertions. The other four tests passed. The full library test was then rerun in isolation on the same APK and passed, including export/restore; no production change was made to hide that failure. The staged queue test intentionally skips its opposite stage in each invocation.

## Podcast evidence

The device test searched Apple’s public directory for Science Friday, fetched its real RSS feed, loaded 500 playable episode records and publisher artwork, subscribed/unsubscribed/resubscribed, and played a current episode through the production resolver and Media3 service. It observed decoded playback progression, a transport notification, a seek to 30 seconds, 1.5× playback speed, continued progression with the Activity in the background, pause, queue clearing and resumption from saved progress.

It also checked exported subscription/progress metadata, persistent podcast queue encoding and mark-played/mark-unplayed, including a further 2.5-second observation to ensure a delayed checkpoint did not overwrite the explicit choice. A race found by this test was fixed by executing the operation as a custom session command in the playback service, before its ordered progress write. The real Science Friday feed measured 16,032,658 bytes; the initial 8 MiB bound correctly rejected it, and the final supported feed limit is 16 MiB.

The separate restart test retained a mixed queue at index 1, position 12,345 ms, a subscribed podcast and episode progress across actual process death and reinstall. Restoration remained paused, generated no fake listening history, and persisted subsequent queue clearing.

New unit coverage includes RSS metadata/audio parsing, HTML-to-text descriptions, GUID identity with changing enclosures, deduplication, durations, DTD/depth/size limits, invalid URLs, podcast stream-cache invalidation, cached subscription/progress preservation, backup round trips and merge timestamps, version-1 compatibility, and podcast queue references. Existing v1 and v2 migration tests exercise the complete upgrade path to Room schema v4.

The podcast screenshot in the README was captured from the real application and visually inspected. No podcast fixtures or demo catalog are seeded in production.

## Distribution and remaining acceptance

The public APK and matching source archive are on the `apk-releases` branch. Exact version, source commit, SHA-256 and certificate fingerprint are recorded alongside the binary in `releases/0.3.0-beta01/release.json`. Publication verifies a clean source commit and pinned certificate. After committing documentation, the final build's DEX, manifest and resources are compared against the tested APK before publishing. The signing key stays private and is never included in Git or artifacts.

GitHub Actions builds/tests/lints the pushed source and uploads a debug APK and reports. Official release signing is local; repository CI signing secrets remain optional and unconfigured. The workflow for emulator tests now has a separate `live_podcasts` opt-in.

Host audio was disabled. These checks establish real decoding, player progression and background behavior in an emulator, not human-audible sound, Bluetooth/physical lock-screen interaction or manufacturer battery behavior. Real-phone checks, document-provider permissions, small-screen/large-font accessibility and network interruption remain in [MANUAL_TESTS.md](MANUAL_TESTS.md). Public catalog/audio availability depends on publishers. Formats and limits are documented in [PODCASTS.md](PODCASTS.md).
