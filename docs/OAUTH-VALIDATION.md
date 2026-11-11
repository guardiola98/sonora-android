# Account import validation — 0.7.0-beta01

This change adds configuration-gated Spotify import and an experimental YouTube API adapter. **No real Spotify/Google account authorization was performed.** There were no registered client IDs or deployed Google broker available. YouTube's permanent-copy/background-player policy conflict also remains unresolved; its adapter is disabled by default. Passing tests does not establish provider approval or prove real account access.

## Automated checks

On the local Android SDK/JDK 21 setup:

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug assembleRelease assembleReleaseAndroidTest \
  -Psonora.testBuildType=release -Pkotlin.incremental=false --max-workers=1
node --test oauth-broker/worker.test.mjs
python3 tools/verify-release.py
```

- Debug APK, release APK and release instrumentation APK compile successfully.
- Unit tests: **120 passed, 2 optional live-network tests skipped**, no failures/errors.
- Lint debug: **0 errors, 28 warnings**. The additional import-screen warning is the optional `String.toUri` KTX-style suggestion; the existing warnings remain.
- Broker: **6 tests passed**, including the actual Worker entrypoint signature. No broker was deployed and no secret was provisioned.
- Release package is `org.sonora.music`, version `0.7.0-beta01`, versionCode `8`, not debuggable. It uses the existing pinned certificate `01ad2a63fd92aea2cbff97812fe90e3adc46c108f3c110558be80ea89cc209f2`. The signing key was not replaced or exposed.

New deterministic coverage includes token redaction/expiry/disposal, cancellation during reading, late authorization results, authorization and selection timeouts, process-restart behavior, state/origin validation, S256 PKCE, a real loopback HTTP callback with invalid then valid state, paginated order, cycle/size limits, unsafe next-page URLs, updated Spotify response envelopes, private YouTube videos, likes filters, conservative matching, ambiguity beyond the five displayed candidates, explicit omission of pending entries, empty lists and repeat imports. Existing Room migration/backup/library tests continue to pass. The Room schema remains version 5.

## Emulator evidence

API 35 ATD, installed with `adb install -r` over the existing signed 0.6 app, without uninstalling or clearing its library:

- `AccountImportDeviceTest`: both entrypoints, actionable unconfigured-provider explanations, Google policy notice, cancellation when navigating away from pending authorization.
- `ImportDeviceTest`: existing ZIP/CSV preview, local playlists/favorites and duplicate-free reimport.
- `LibraryFeaturesDeviceTest`: existing library operations remain functional.
- `PlaybackDeviceTest`: activity reopen and generated-PCM decoder/queue/seek/background-session controls.

**5 device checks passed; 1 opt-in live YouTube playback check skipped.** Instrumentation reports the Playback class as three tests including that skip. Emulator audio output was disabled; decoder/session assertions are not a claim of human listening or of Spotify-equivalent playback.

[Actual import entry screen](screenshots/account-import.png) and [Google availability notice](screenshots/account-import-availability.png). Captures show the unconfigured build, not a simulated successful account connection. The provider adapters were exercised with synthetic HTTP fixtures in tests; no private library was used.

## Required real-account / physical-device checks

1. Register the Spotify client and allowlist the tester. On a physical phone, authorize in its system browser with playlist read scopes only. Repeat with the separate liked-song option and confirm only then that library-read permission is requested.
2. Test owned public/private and collaborative lists with more than 50 entries, multiple selections, empty lists, duplicate tracks, local Spotify files, episodes and unavailable items. Confirm inaccessible followed/editorial lists produce a clear error, not a partial silently saved import.
3. Check real artist/title/duration matching against known recordings. Covers, live versions, remixes, missing duration and multiple plausible candidates must remain pending. Open candidate audio in the browser, return to the disconnected review, then choose explicitly. Confirm pending entries cannot be omitted without acknowledgement.
4. Confirm names and first-occurrence order. Save, reauthorize and reimport unchanged lists; canonical references must not duplicate. Add new source entries and verify merge-only behavior preserves local edits/hearts/history. Choosing a different recording URL is a distinct library track, not a verified recording-identity merge.
5. Deny consent, press Back, close the browser, cancel while listing, cancel while matching, switch tabs, and background an active selection. Check the connection is discarded and no library writes occurred before confirmation. Closing the browser alone can leave an empty pending authorization attempt until Cancel or the three-minute timeout.
6. Kill Sonora's process during browser authorization and during selection. Return with the old callback; it must not restore authorization. Start a new import and confirm a new state/verifier/authorization attempt is required. Browser login/earlier consent may still exist.
7. Test airplane mode, token expiry/revocation, HTTP 401/403/429, provider outages, incomplete pages and quota exhaustion. Check bounded requests, no refresh loop and fresh authorization on retry. After three empty/failed music searches, remaining Spotify entries should require individual review.
8. Verify rotation, large font, narrow screen, screen lock and background playback. Scrolling long reviews should remain responsive and source attribution must stay visible. Confirm Cancel/Back and the final import action remain reachable.
9. Inspect a local backup after an import: selected library metadata/order may be present; access/refresh tokens, OAuth codes/verifiers, account profiles and unselected metadata must not be. Do not include real credentials or full unredacted device logs in bug reports.
10. **Google only after the documented policy blocker is resolved:** configure the public web client, HTTPS broker secret store and verified App Link. Validate with an allowlisted account first; confirm only YouTube read access and online access, ownership/channel behavior, separate YouTube liked videos and missing Music-only content. Test the real authorization exchange, denied scope and default-browser return on Android 26 and Android 31+.
11. Verify the deployed broker's HTTPS routing, certificate association, request bounds, sanitized errors, absence of payload/query logging/caching, and immediate dropping of refresh/ID tokens. Try a stale or altered state and a callback after process death. Confirm unverified App Links give the documented recovery page rather than exposing a code.
12. Upgrade the signed APK over the physical device's existing Sonora installation. Confirm playlists, favorites, history, podcasts, backups, queue and update checks remain intact. No Google/Spotify token is used by the player. Test actual streaming/audio separately before calling account import and playback fully validated.

Configuration, source links and distribution restrictions: [OAUTH-IMPORT.md](OAUTH-IMPORT.md). The signed 0.7.0 APK was subsequently published through the existing channel. Follow-up export-import evidence is recorded separately in [Spotify export validation](SPOTIFY-EXPORT-VALIDATION.md).
