# Sonora 0.8.0-beta01 validation

This release adds persistent audio downloads, public Spotify playlist links alongside the existing paginated YouTube Music importer, and English/Spanish/Catalan interface resources. VersionCode is 12; the Room schema stays at v5.

## Build and source checks

- The full JVM/Robolectric suite ran 156 tests: 154 passed, two opt-in probes skipped, no failures. Checks cover cache-only music/podcast reads and seeks, incomplete transfers, expired-stream re-resolution, cancellation, cache/index reopening, public Spotify URL/redirect validation and bounded parsing, conservative version review, and all translated resource formats.
- The live import probes read 50 entries from Spotify's public Top 50 Global embed without credentials or audio, and 200 YouTube Music entries across two pages. These are network probes; they do not establish that every public playlist will remain available.
- All 680 resource keys exist in English, Spanish and Catalan with matching format arguments. The generated Kotlin catalog and explicit template coverage are checked by `tools/verify-translations.py` and CI. User/provider titles and artist names remain unchanged.
- Android lint reports no errors and 23 warnings.
- The publishing-script guards and optional OAuth-broker unit check pass. The optional broker is not deployed or required by public-link import.

## Signed update and offline playback

The signed APK updated the API 35 x86_64 emulator's official 0.7.3 installation with `adb install -r`. No uninstall or data reset was used. A row-by-row comparison preserved the existing 13 tracks, queue checkpoint, two podcast feeds, 500 cached episodes, four episode positions and five listening-preference records. The existing playlist tables were empty and unchanged. The release certificate remains `01ad2a63fd92aea2cbff97812fe90e3adc46c108f3c110558be80ea89cc209f2`.

A real public search for Kevin MacLeod's **Carefree** selected canonical YouTube reference `sMcWOaJFuw0`. The production download service saved the complete 3,918,963-byte audio. After stopping/restarting the app process and disabling both emulator Wi-Fi and mobile data, the production MediaSession decoded it, advanced playback and sought to five seconds without an active network. The saved item and its playlist metadata remained available. A final cleanup check removed the audio, verified that its playlist still existed, then removed only the test playlist.

A separate generated-PCM device test downloaded music and podcast references, reopened the cache/index, decoded both, sought within them and changed podcast playback speed with a network factory that fails if used. No generated test audio is bundled in the app. The manager-resume regression checks that an explicit resume clears the global paused flag left by a data-sync timeout; this is not a six-hour OS timeout soak test.

## Interface and regression checks

All six release device checks passed. The compact 320 × 570 dp language/navigation and shuffle checks also passed (two checks). The release device suite covers the real language picker, activity recreation, retained library and queue, the full player's visible download action, downloads, public-link import, all four navigation tabs, and the existing player/queue shuffle behavior. Captures use AndroidX's app screenshot API because this ATD emulator disables normal display drawing.

See [screenshots](screenshots/release-080/) and [measured checks](measurements/release-0.8.0/checks.json). Final checked DEX, manifest and resource hashes are compared with the clean committed build before publication.

## Scope and limits

Spotify's public embed may expose only part of a large playlist. The review screen states this and displays the exposed count before matching or saving; ZIP/JSON export remains available for missing entries. Public page availability/format changes can affect import. Spotify audio is not downloaded.

Downloads are in-app local audio, separate from library JSON backups. A newly resumed incomplete transfer may restart from zero to avoid combining different renditions. Android force-stop, power restrictions and data-sync limits can stop transfers; reopening/resuming is supported, unattended boot-time downloading is not promised. Search, radio and uncached artwork still need internet.

The checks above ran on an emulator and prove decoder/position behavior, not human-audible playback or every physical-device manufacturer's background policy. Legacy language wrapping is covered with API 28 Robolectric; Android 13+ per-app language behavior is exercised on API 35. Wider physical-device, accessibility and long-running storage/power testing remains in the manual checklist.
