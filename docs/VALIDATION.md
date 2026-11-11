Current import follow-up: [0.7.1 Spotify export validation](SPOTIFY-EXPORT-VALIDATION.md). The evidence below retains its original version scope.

Latest development change: [temporary OAuth import validation](OAUTH-VALIDATION.md). The evidence below describes the published 0.6 release.

# Sonora 0.6.0-beta01 publication validation

Validated on 11 November 2026 with JDK 21, Gradle 8.13, SDK 36/build-tools 35.0.0 and an API 35 x86_64 emulator. This publishes the in-app updater and compact Now Playing changes already merged into main (`a42a3dc`), using the existing persistent release key. Previous [0.5 validation](VALIDATION-0.5-beta01.md) is archived.

## Build checks

- `assembleRelease`, `assembleDebug`, `testDebugUnitTest`, `lintDebug` and `assembleReleaseAndroidTest` passed locally.
- **101 deterministic unit tests passed**, with 2 opt-in live tests skipped (103 total). The new updater checks cover legacy/enriched manifests, version ordering, allowed download paths, digests, package/version identity and a single pinned signer.
- Lint: **0 errors, 27 warnings**, including dependency/UI/deprecation suggestions.
- The release verifier confirmed application ID `org.sonora.music`, version `0.6.0-beta01`, versionCode **7**, a non-debuggable APK and the unchanged certificate SHA-256 `01ad2a63fd92aea2cbff97812fe90e3adc46c108f3c110558be80ea89cc209f2`.

## Signed APK installation and runtime checks

The signed APK was installed with `adb install -r` over the emulator's signed **0.5.0-beta01** installation, without uninstalling or clearing application data. The production database remains Room schema v5 and exported backups remain format v2.

**Five Android checks passed** against this signed production APK: library editing/reordering/export/restore, Takeout import/reimport, closing and reopening the Activity, generated-PCM decoding/session/seek/queue/background playback, and navigation across all four screens. The live YouTube test was deliberately not enabled for this publication check. [Recorded result](measurements/release-0.6/android-checks.json).

The first invocation passed four checks but rejected an accessibility click in the library test, also seen in an earlier CI device run. The test now reacquires the current enabled clickable node and waits for an accepted action; an accepted click is never repeated. The isolated library test then passed on the same production APK (11.404 seconds). No application behavior was changed to accommodate the test.

The compact Now Playing test remains available in the debug device workflow, where its test-host Activity is provided. The preceding CI run for `a42a3dc` completed that test and captured its images before the separate library assertion failed; this is not claimed as a wholly green device workflow.

## Publication and remaining limits

Publication uses the existing build/verification/publish scripts, the persistent signing key, the exact committed source archive and the append-only `apk-releases` channel. The final clean build's DEX, manifest and resources are compared against the tested APK. `latest.json` includes the new version, checksum, download path, size and release notes. Private signing files remain ignored and are never distributed.

The emulator had host audio disabled, so playback checks establish decoder/player progression, not human-audible quality. Physical-device behavior and the complete in-app download → permission → Android installer → newer official release flow remain manual checks in [UPDATES.md](UPDATES.md). This publication does not claim those flows were exercised end to end. Existing discovery and live-stream evidence is retained in the archived 0.5 report.
