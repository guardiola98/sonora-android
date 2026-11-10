# Beta02 validation record

Date: **2026-11-10 (UTC)**. Version **0.1.0-beta02**, version code **2**. Environment: Debian Linux, OpenJDK 21, Android SDK 36/build-tools 35.0.0, Gradle 8.13; API 35 x86_64 Google ATD emulator with KVM. SDK/cache/emulator files are ignored under `.tooling/`. No dependency was added or changed; see [pinned dependency decisions](DEPENDENCIES.md). The earlier beta01 evidence is archived in [VALIDATION-beta01.md](VALIDATION-beta01.md).

## Completed checks

| Check | Observed result |
|---|---|
| `./gradlew assembleDebug` | **PASS** — debug APK generated and installed |
| `./gradlew testDebugUnitTest` | **PASS** — 27 deterministic tests, 2 opt-in live tests skipped |
| `./gradlew lintDebug` | **PASS** — 0 errors, 20 existing warnings |
| `./gradlew assembleDebugAndroidTest` | **PASS** — instrumentation APK compiled |
| APK signature and manifest | **PASS** — v2 signature; version code 2, minSdk 26, targetSdk 36 |
| `SONORA_LIVE_IMPORT=true ./gradlew testDebugUnitTest --tests '*LiveImportTest'` | **PASS** — 200 unique tracks across 2 pages from a real public playlist |
| Final APK instrumentation | **PASS** — ImportDeviceTest + PlaybackDeviceTest, `OK (4 tests)`, 110.853 s |
| Final UI screenshots | **PASS** — UiSmokeTest, `OK (1 test)`, 4.106 s; import preview visually reviewed |

The combined final build command was `assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest` and completed successfully. The live playlist used `PLMC9KNkIncKtPzgY-5rmhvj7fax8fdxoj`; its contents may change. This test checks extractor pagination and metadata, not playback.

New deterministic tests cover BOM/quoted/multiline/semicolon CSV, URL validation, old Takeout metadata, likes suggestions, duplicate reporting, oversized/invalid ZIP/CSV, optional headers, Room v1→v2 migration, additive ordering, existing favorites/history preservation, missing metadata, offline lookup failure and rollback after an injected write failure.

`ImportDeviceTest` creates two synthetic CSVs in app-private cache and passes their URIs through the actual ViewModel/ContentResolver import callback. It drives preview/save/library UI, verifies playlist order and the imported favorite, then repeats the import and checks zero added entries/hearts. Its fixtures are cleaned up. This exercises the picker callback, **not the system document-picker interaction or a real user's private export**.

During UI review an intentional MediaController release left a stale disconnection warning for the next Activity. The fix suppresses errors on intentional release and clears stale connection-specific errors after a successful connection. A new device regression test closes/reopens the Activity and asserts connected state with no player failure.

The final device run exercised actual PCM decoding, seeking, queue navigation, pause, a media transport notification and background position progression. It also extracted and played a real YouTube result for `Kevin MacLeod Carefree`, with ExoPlayer reporting playing beyond two seconds and continued progress in the background. The new Activity reopening regression passed. The separate UI run checked all three bottom-navigation screens. The import preview screenshot uses synthetic test metadata; it is not a seeded production library.

## Exact local APK

Path: `app/build/outputs/apk/debug/app-debug.apk`

```text
SHA-256  8fd41ccceda3ea50ba9d0806d3a1501f9eb94d0c0e0dd06b2ee42a1d5a500b1f
```

CI uses a different ephemeral debug key and can produce a different APK hash. Each artifact includes its own SHA256SUMS and source snapshot. See the [Android beta workflow](https://github.com/guardiola98/sonora-android/actions/workflows/android.yml). Upgrading without losing local data requires APKs signed with the same key; uninstalling clears the database.

## Remaining real-device checks

- Real Google Takeout exports vary by account, language and date. Formats are covered by synthetic fixtures, not a private Google account. Only exported video references can be recovered; Sonora does not promise that Google includes every like.
- Test the real document picker/provider permissions, large archives, user cancellation, TalkBack/large fonts, rotation and process interruption using the [manual checklist](MANUAL_TESTS.md).
- Emulator host audio is disabled. Physical audible output, lock-screen/headset controls, calls, manufacturer battery policies and long locked playback require a phone. API 26/36 device runs remain manual.
- Imports are a one-time local copy; no account synchronization or audio download. Queue restoration after process death is outside beta scope.
- Live source success is time-dependent and is not a guarantee for every video, network or future extractor change.
