# Spotify export validation — 0.7.1-beta01

The first requested publication was completed: signed 0.7.0 was pushed to the existing `apk-releases` channel. The follow-up 0.7.1 adds a usable Spotify export-file route. **Direct account OAuth is still unconfigured. No Spotify/Google account was authorized, no broker was deployed, and no personal Spotify export was supplied.** Do not treat fixture tests as provider approval or successful real-account integration.

## Build and automated checks

JDK 21, Android SDK 36, existing pinned dependencies and persistent signing configuration:

```sh
./gradlew --no-daemon assembleDebug testDebugUnitTest lintDebug assembleRelease assembleReleaseAndroidTest \
  -Psonora.testBuildType=release -Pkotlin.incremental=false --max-workers=1
node --test oauth-broker/worker.test.mjs
python3 tools/verify-release.py
```

Final build successful. **134 unit tests passed, 2 optional live-network tests skipped; 0 failures/errors. Lint: 0 errors, 28 existing warnings. Broker: 6 passed.**

Fourteen new unit checks cover playlist/library export shapes, ordering, missing duration, ignored account data, ZIP path/size/depth bounds, cancellation, stable reimport identity, conflicting names, explicit playlist URIs, unsupported entries, offline review, no account/network access before selection, confirmation of ambiguous recordings, previous-session disposal, duplicate-free Room import, and preservation of favorites/history/backup identity. Existing OAuth pagination/session/PKCE, Room migration, podcast, discovery and playback suites continue to pass. Room remains schema 5 and backups remain format 2.

## Real Android runtime

The signed production APK (`org.sonora.music`, code 9) was installed with `adb install -r` over signed 0.7.0 on the API 35 ATD emulator. No uninstall, database clearing or replacement signing key was used.

- `SpotifyExportDeviceTest`: reads an actual local JSON fixture via the production ViewModel/document-reading path; selects a list using the UI; disables matching; checks that absent duration leaves the entry pending; explicitly omits it; confirms/saves the resulting empty list; repeats the import and checks no duplicate destination. This test does **not** claim a real song was matched or played. Unit tests separately exercise selecting a fixture candidate and persisting its canonical reference.
- `AccountImportDeviceTest`: provider configuration messages and cancellation when navigating away from authorization.
- `ImportDeviceTest`: Takeout preview, playlist/favorite import and reimport.
- `LibraryFeaturesDeviceTest`: library operations and backup behavior.
- `PlaybackDeviceTest`: Activity reopening and actual Media3 decoder/session/seek/queue/background control checks with generated PCM. Live YouTube playback remains opt-in and was skipped.

**6 Android checks passed, 1 live-network check skipped.** Emulator audio output was disabled, so these are runtime/decoder checks, not human listening. Fixtures were removed afterward; the user's existing library was preserved. The emulator was shut down after testing.

[Recorded counts and tested runtime hashes](measurements/release-0.7.1/checks.json). [Actual import entry screen](screenshots/import-0.7.1.png) and [actual offline review of the clearly named test fixture](screenshots/spotify-export-review.png). These are real emulator captures, not mockups or an OAuth success screen.

The clean-commit publication build must match the tested DEX, manifest and compiled-resource hashes before `tools/publish-release.py` runs. The exact source commit, APK checksum, size and pinned certificate are published beside the APK in `release.json`; private signing material stays outside Git.

## Still to verify on a physical phone

1. Request a real Spotify Account data export; use the Android system document picker with its ZIP, split Playlist JSON files and YourLibrary.json. Compare source names/order/counts. New export shapes should fail clearly rather than fabricate tracks.
2. Select only some lists and verify unselected metadata is not searched/saved. Test offline review, then explicitly search selected songs or enter a YouTube link. Listen to candidate versions and confirm only the intended recordings; missing duration must never auto-select a version.
3. Save chosen songs as playlists/favorites, repeat the same import, and add tracks upstream before exporting again. Check the documented name-based identity, duplicate-name rejection, source rename behavior, and preservation of local edits/history/favorites.
4. Cancel during reading/matching, change screens, rotate, lock the screen and kill the process. Confirm nothing is saved before the final confirmation and reopening starts without a retained account/file selection.
5. Check large exports, unsupported/local/podcast entries, airplane mode and search failures. Explicitly omitted entries must stay absent; revisit them by reopening the source file.
6. Upgrade over the installed official APK and check playback, queue, podcasts and backups. Exercise the in-app update-download/installer flow on that physical phone.
7. For direct authorization, complete the separate [OAuth setup and real-account checklist](OAUTH-IMPORT.md). Spotify needs a public client ID and permitted users. Google still needs a viable approved use case, OAuth configuration and HTTPS deployment. Neither service is enabled merely by publishing this APK.
