# Sonora 0.4.0-beta01 validation

Session: 11 November 2026 (Europe/Madrid), Debian Linux, JDK 21, Gradle 8.13, SDK 36/build-tools 35.0.0. Runtime: API 35 x86_64 emulator with 1 GiB RAM and two virtual cores. Dependency versions remain unchanged. Previous [0.3 validation](VALIDATION-0.3-beta01.md) is archived.

## Build and deterministic checks

- `assembleDebug`, `testDebugUnitTest`, `lintDebug`, `assembleRelease` and `assembleReleaseAndroidTest` passed on the final production changes, with Kotlin incremental compilation disabled.
- **62 deterministic unit tests passed**, 2 opt-in live JVM tests skipped. Android lint: **0 errors, 22 warnings** (dependency updates and existing UI/icon suggestions, plus a preference-editor style suggestion).
- New coverage checks independent concurrent source requests, cancellation while playback joins preparation, invalidation during an in-flight request, immediate preparation after changing screens, cache expiry/capacity/late writes, exact network continuation, finite ranges, unknown lengths, seeking and ICY probe compatibility.
- The signed release was installed in place over the signed 0.3 installation without uninstalling. VersionCode is now 5; the pinned persistent certificate is unchanged. Room remains schema v4 and backup format v2.

## Actual Android playback checks

**11 Android tests passed** across these invocations, using the signed, non-debuggable release APK.

| Check | Passed | Time |
| --- | ---: | ---: |
| Automatic preparation from Search | 1 | 4.489 s |
| Real YouTube startup, continuation and seek | 1 | 36.464 s |
| Real RSS podcast playback/resume/background | 1 | 22.267 s |
| Library/import/PCM/session/Activity/live YouTube | 5 | 113.592 s |
| Four-tab navigation | 1 | 4.098 s |
| Mixed queue/subscription/progress seed | 1 | 1.589 s |
| Force-stop, in-place reinstall, paused restoration | 1 | 1.875 s |

The automatic-preparation check uses a real search in the visible Search screen, observes a completed RAM prefix without autoplay or an extra history entry, then starts playback through the production UI controller. Disabling preparation clears the shared cache. The benchmark separately plays past the prefix into network audio and seeks to 60 seconds.

The regression suite exercises real RSS discovery/playback/resume/background behavior, actual YouTube decoding, generated PCM/session controls, reopening the Activity, playlist editing, import/backup operations, four-tab navigation, and mixed queue/subscription/progress restoration after force-stop and an in-place reinstall. Queue restoration remains paused. The staged queue test intentionally skips its opposite stage.

## Startup measurements

| Mode | Published 0.3 samples (ms) | Median | Final 0.4 samples (ms) | Median |
| --- | --- | ---: | --- | ---: |
| Cold resolver | 2768, 2817, 2194 | 2768 ms | 2095, 2130, 2715 | 2130 ms |
| URL resolved, no audio prefix | 1273, 1258, 1289 | 1273 ms | 943, 949, 924 | 943 ms |
| URL and audio prefix prepared | Not available | — | 260, 191, 203 | 203 ms |

The independent screen/controller test measured **238 ms** after automatic preparation. Final prefix preparation took **1072–1561 ms** before the timed Play command. The preceding failed network run is preserved rather than folded into the successful-start table. [Raw baseline](measurements/startup-0.4/baseline-0.3.json), [final samples](measurements/startup-0.4/optimized-0.4.json), [screen result](measurements/startup-0.4/automatic-0.4.json), [partial HTTP-403 run](measurements/startup-0.4/network-rejection-0.4.json).

Baseline APK SHA-256: `4eb97368575caa32db92fe806dd1d2bba7ec911abcc0df0f7a3e8c39939b9362` (published 0.3).

The metric is Play-command to `isPlaying` plus at least 50 ms of timeline progression, not acoustic output latency. Each row uses three sequential trials on the same public track and emulator network. “Cold” clears Sonora's resolution/prefix caches, not all process/extractor/DNS caches. Prepared timing excludes the preceding network preparation. Detailed methodology and tradeoffs: [STARTUP.md](STARTUP.md).

## Failures found during development

- Integration checks identified that Media3 adds `Icy-MetaData: 1` to progressive requests, initially bypassing prefix eligibility. Preparation now fetches with the same probe header, allows that header when serving a prefix, and excludes interleaved radio metadata. The subsequent full build passed.
- The first automatic screen-preparation check failed because canceled work retained its retry cooldown when another screen requested the same track. Cancellation now clears pending attempts; a new deterministic regression and the actual screen test pass.
- One later benchmark run received HTTP 403 from the remote audio host during continued loading. The timed initial start succeeded, but the 12-second continuity assertion failed after the existing bounded recovery was triggered. The partial measurements are retained alongside successful samples; no production workaround, credentials or retry loop was added. An isolated repeat on the same APK passed all nine timing trials, continued playback and seeking; the separate live YouTube regression also passed.

## Distribution and limits

The release script verifies a clean committed source tree, application ID, non-debuggable APK and pinned certificate, then records exact provenance. The final publication build's DEX, manifest and resources are compared against the installed/tested release before publishing. The public `apk-releases` branch includes the APK, source ZIP, GPL license, attribution, SHA-256 and certificate fingerprint. Private signing material is excluded from Git and artifacts.

GitHub Actions builds, tests and lints pushed commits and uploads the debug APK/reports. Official signing remains local with the existing persistent key. The manually triggered device workflow now offers an independent `startup_benchmark` option.

Host audio was disabled: these tests establish real decoder/player progression in an emulator, not human-audible quality, physical lock-screen/Bluetooth behavior or manufacturer battery handling. One public song and a few samples do not establish universal latency. Cold extraction, network conditions, URL expiry and upstream restrictions can still delay or prevent playback. Slow-network startup stutter, distant podcast resumes, data use, cancellation and physical-device listening remain on the [manual checklist](MANUAL_TESTS.md).
