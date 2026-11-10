# Beta 0.1 validation record

Date: **2026-11-10**. Environment: Debian Linux, OpenJDK 21, Android SDK 36, build-tools 35.0.0, Gradle 8.13. The missing SDK was installed under ignored `.tooling/`; Internet access and GitHub SSH authentication were available. The APK was installed on an Android 15 / API 35 x86_64 Google ATD emulator with KVM.

## Completed checks

| Check | Observed result |
|---|---|
| `./gradlew assembleDebug` | **PASS** — installable APK generated |
| `./gradlew testDebugUnitTest` | **PASS** — 9 deterministic tests passed; 1 opt-in live test skipped |
| `./gradlew lintDebug` | **PASS** — 0 errors, 20 warnings (newer dependency versions and base adaptive-icon monochrome warning; API 33 monochrome variant is provided) |
| `./gradlew assembleDebugAndroidTest` | **PASS** — instrumentation APK compiled |
| `apksigner verify --verbose` | **PASS** — APK Signature Scheme v2 signature valid |
| APK manifest inspection | `org.sonora.music`, `0.1.0-beta01`, version code 1, minSdk 26, targetSdk 36 |
| `SONORA_LIVE_TESTS=true ./gradlew testDebugUnitTest --tests '*LiveSourceTest'` | **PASS** — real search, stream extraction, successful HTTP response and 1,024 audio bytes read |
| Android `PlaybackDeviceTest`, with `liveYouTube=true` | **PASS** — both instrumentation tests passed (`OK (2 tests)`, 103.088 s) |
| Android `UiSmokeTest` | **PASS** — all three bottom-navigation screens rendered; nonblank screenshots captured and visually reviewed (`OK (1 test)`) |
| Repository hygiene | No SDK/cache/build outputs, signing keys or credentials staged; `git diff --cached --check` passed |

The final production-code build command combined `assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest` and finished with **BUILD SUCCESSFUL**. The separate screenshot test adds only instrumentation code; no production APK behavior changed after the playback run.

The deterministic tests verify Room data across database reopen, favorites/history independence, history clearing, cancellation of stale searches, error/retry state, cache expiry, cache invalidation, shared concurrent resolution and distinct stream identity. The live network probe is not treated as an audio playback test.

The Android playback test used a locally generated 20-second PCM fixture to verify decoder/position progression, seeking, next/previous queue navigation, pause and continued playback with the Activity in `CREATED` state. It also confirmed an active media transport notification. A separate live test searched for `Kevin MacLeod Carefree`, played an actual extracted YouTube stream until ExoPlayer reported playing with a position beyond two seconds, then confirmed position progression in the background.

An initial fixture test exposed an HTTP-only data source trying to open a file URL. This was corrected by composing Media3's `DefaultDataSource` with the HTTP transport and the source resolver. Both playback tests then passed on the rebuilt APK. An initial Android Lint API-27 theme attribute error was also fixed before the final successful build.

## Exact local APK

Path: `app/build/outputs/apk/debug/app-debug.apk`

```text
SHA-256  931b549234831b3cd952475ec9cfd34004c84188f611519e34df1faebf46bb20
```

CI builds may have a different debug signing key and therefore a different hash. Each downloadable artifact includes its own `SHA256SUMS` and exact source snapshot. Check the [Android beta workflow](https://github.com/guardiola98/sonora-android/actions/workflows/android.yml) for the current hosted build result.

## Boundaries of the evidence

- The emulator was headless with host audio disabled. The tests verify real extraction, decoding/player progress and background operation; **human audible output has not been checked on a physical phone**.
- A media notification was published, but physical lock-screen interaction, Bluetooth/headset controls, call interruptions, manufacturer battery policies and long-duration locked playback still need the [manual acceptance checklist](MANUAL_TESTS.md).
- Stream cache expiry/invalidation is unit-tested. A naturally expired YouTube signed URL and every type of upstream restriction have not been reproduced on a phone.
- API 26 is the compiled minimum; the automated device run used API 35. Physical API 26 and API 36 regression runs remain manual acceptance items.
- Upstream availability is time-dependent. A successful live test on this date is not a guarantee for every video, IP address or future source change.
- The queue is intentionally in-memory and does not survive process death/reboot. Favorites and history are persisted in Room.
