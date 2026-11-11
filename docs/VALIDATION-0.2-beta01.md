# Sonora 0.2.0-beta01 validation

Session: 11 November 2026 (Europe/Madrid), Debian Linux, JDK 21, Gradle 8.13, SDK 36/build-tools 35.0.0. No dependency version was changed. Earlier [beta01](VALIDATION-beta01.md) and [0.1-beta02](VALIDATION-0.1-beta02.md) evidence is archived.

## Completed checks

- Debug assembly: passed.
- Unit suite: 40 deterministic tests passed, 2 opt-in live tests skipped.
- Android lint: passed, no errors.
- `assembleRelease` and `assembleReleaseAndroidTest`: passed. The distributed release is signed, non-debuggable, and deliberately unminified for this beta.
- Release device suite: **5 passed**, 116.207 s (library editing/drag/export/restore, Takeout import, PCM/session controls, Activity reopening and live YouTube playback).
- Release UI navigation: **1 passed**, 3.708 s.
- Release queue seed: **1 passed**, 1.741 s; the verifier stage is intentionally skipped in that invocation.
- Actual `am force-stop`, followed by `adb install -r` of the same signed release, then queue verification: **1 passed**, 1.921 s; the seed stage is intentionally skipped in that invocation.
- The restored queue retained index 1 and position 12,345 ms, remained paused, did not create listening history and persisted subsequent clearing.
- APK signature/certificate/application ID/non-debuggable manifest verified by `tools/verify-release.py`.
- Playlist editor screenshot captured from the actual UI and visually reviewed. Its song titles explicitly identify synthetic test fixtures.

New unit coverage includes full JSON backup round trips, empty playlists, repeat-restore idempotence, history/favorite preservation, rollback on failure, invalid/oversized input, local playlist editing and order, concurrent additions, v2→v3 migration, canonical queue checkpoints, latest-position writes and durable queue clearing. The existing v1 migration test now verifies the full v1→v2→v3 path.

Remaining physical-device acceptance is listed in [MANUAL_TESTS.md](MANUAL_TESTS.md). Headless emulator progress/decoder tests do not establish human-audible output, Bluetooth/lock-screen interaction or manufacturer battery behavior. The actual Android document picker and provider permissions still require manual testing; automated file tests exercise its ContentResolver callback.

Signed publication uses the persistent local key, with a tracked public fingerprint. GitHub Actions signing secrets are not configured by this session; see [RELEASING.md](RELEASING.md). The official APK/source are published over SSH on `apk-releases`, separately from ephemeral debug artifacts.

## Published binary and source

The exact APK hash and source commit are recorded with the binary in `releases/0.2.0-beta01/release.json` and `SHA256SUMS` on the [apk-releases branch](https://github.com/guardiola98/sonora-android/tree/apk-releases). The publication helper verifies a clean source checkout and recorded build provenance. The tested executable DEX, manifest and resources are compared again after the final commit/build, so documentation/version-control packaging metadata cannot silently replace tested code. The private signing key is not in either branch or artifact.

The live test actually searched YouTube, extracted audio and observed ExoPlayer playing beyond two seconds, then progressing in the background. The PCM test verified seeking, queue navigation, pause and a transport notification. Host audio remained disabled; physical audible playback is still a manual acceptance item.

A minified instrumentation attempt failed in AndroidJUnitRunner because shared tracing classes had been removed. The published beta disables shrinking and obfuscation; all eight device checks above passed on that signed configuration. This is a release-size/diagnostics tradeoff, not a debug build.
