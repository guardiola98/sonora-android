# Sonora 0.8.1-beta01 validation

VersionCode 13 keeps the existing signing certificate and Room schema v5. This release makes language selection available through a labelled top-bar button, adds complete French/Italian resources, gives Library a labelled import button, and places YouTube Music and Spotify link choices first and second. Provider-specific dialogs support explicit clipboard paste and inline validation.

The Spotify/Google authorization classes, account API clients, callback intent filter and handling, client BuildConfig configuration, optional broker and broker CI job have been removed. Spotify public metadata and local exports retain their review/matching flow and existing `spotify:` playlist identities. Old YouTube-import identities remain compatible with public-link reimports. Existing library data is not migrated or cleared.

## Automated checks

- 141 JVM/Robolectric tests: 139 passed, two unrelated opt-in live probes skipped, no failures. Public Spotify and YouTube Music probes were enabled: 50 Spotify entries without credentials; 200 YouTube Music tracks across two pages.
- Translation verifier: 624 keys in each of English, Spanish, Catalan, French and Italian; matching format arguments and 673 explicit translated calls. Robolectric checks all resources plus legacy API 28 locale persistence, dynamic labels and untranslated user content.
- Import checks retain conservative matching, explicit pending-entry acknowledgement, offline review, cancellation, empty playlists, duplicate prevention and file/URL validation after removing account access.
- Android lint: no errors, 22 warnings.
- The three publishing guard tests pass. No broker deployment or account setup remains.

## Device and release checks

Seven release device checks passed on API 35: the visible language button through all five languages and activity recreation; clipboard paste and rejection of the other provider’s link without starting an import; Spotify export review/reimport; Takeout playlist/favorite reimport; player/queue shuffle; cache-only music/podcast decoding and seeking; and all four navigation tabs. The language and clipboard/form checks also passed at 320 × 570 dp (two checks). Both provider choices were visible without scrolling, with YouTube Music first.

The pinned, non-debuggable APK updated the existing signed 0.8.0 installation using `adb install -r`, without uninstalling or clearing data. All rows in the checked tables remained identical: 13 tracks, one playlist and entry, one queue checkpoint, two podcast feeds, 500 episodes, four progress records and five listening-preference records. The offline decoder regression uses generated fixtures; no new production live audio download is claimed for this UI release.

See [measured checks](measurements/release-0.8.1/checks.json) and [screenshots](screenshots/release-081/). Captures use AndroidX’s app screenshot API because the ATD emulator disables normal display drawing. Final DEX, manifest and resource hashes are compared with the clean committed release build before publication.

## Limits

Spotify public metadata can expose only part of a long playlist. The link dialog and review retain that notice and show the returned count; ZIP/JSON export is still available. No Spotify audio or private account access is used. Public provider pages can change independently of this release.

Device checks use the API 35 emulator. They do not establish behavior on every physical device or every font/display scale. The existing manual checklist includes large text and Android 8–12 device coverage; legacy resource behavior is tested with Robolectric.
