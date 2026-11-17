# Sonora — Android music and podcast player

[![Android beta](https://github.com/guardiola98/sonora-android/actions/workflows/android.yml/badge.svg)](https://github.com/guardiola98/sonora-android/actions/workflows/android.yml)

An independent, open-source music and podcast player for Android 8.0+ (API 26). Sonora searches public YouTube tracks through the **[NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor)** library and plays audio through **Media3 ExoPlayer**. Public RSS podcasts have their own discovery and subscriptions tab. No account or API key is required for playback. No ads or analytics. Playlist imports use public links or local export files; no sign-in setup is needed.

## Built on NewPipeExtractor

**Sonora’s YouTube integration would not be possible without [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) and the work of [Team NewPipe](https://github.com/TeamNewPipe) and its contributors.** Their library provides the YouTube search, playlist, metadata and stream extraction used by Sonora. Thank you to the people who build and maintain it as streaming services change.

Sonora uses the unmodified **[NewPipeExtractor v0.27.1](https://github.com/TeamNewPipe/NewPipeExtractor/tree/v0.27.1)** dependency, licensed under **[GPL-3.0-or-later](https://github.com/TeamNewPipe/NewPipeExtractor/blob/v0.27.1/README.md#license)**. Sonora’s interface, local library, player integration and import flows are developed in this repository. Please visit the upstream project for its source, documentation and ways to contribute. [More open-source acknowledgements](#open-source-acknowledgements) are listed below.

**Current beta: 0.8.2-beta01.** This is a testing build. See [validation evidence and limitations](docs/VALIDATION.md) before interpreting build success as proof of device playback.

<p>
  <img src="docs/screenshots/discovery-home.png" width="240" alt="Personalized Sonora Home on Android 15" />
  <img src="docs/screenshots/discovery-search.png" width="240" alt="Real music search results and categories" />
  <img src="docs/screenshots/discovery-radio.png" width="240" alt="Automatically recommended queue with explanations" />
</p>

Actual emulator captures from the signed 0.5 beta, using public music searches and playback. No demo catalog is seeded. [Algorithm, sources and privacy controls](docs/DISCOVERY.md).

## Install

Download the [signed Sonora 0.8.2 beta APK](https://github.com/guardiola98/sonora-android/raw/refs/heads/apk-releases/releases/0.8.2-beta01/sonora-0.8.2-beta01.apk). The [APK distribution branch](https://github.com/guardiola98/sonora-android/tree/apk-releases) includes the exact source snapshot, GPL license, attribution, certificate fingerprint and APK SHA-256 for each version.

This release uses a **persistent signing key**. Future releases using this key can update it without uninstalling or clearing the library. Earlier **debug** builds can have different certificates and cannot necessarily be upgraded in place. Do not uninstall an older build with data you need unless you have a usable backup. A new signing key cannot grant access to another application's private data. See [release signing and upgrade notes](docs/RELEASING.md).

Android may ask you to allow installation from the app opening the APK. Search for a song or use **Library → Import music** for public Spotify/YouTube Music playlist links, Spotify exports and Takeout playlists/likes. Use **Library → Backup and restore** to save a copy outside the app before changing phones or uninstalling.

From Sonora 0.6, **About → Updates** checks the official distribution channel and hands verified updates to Android for confirmation. See [in-app updates](docs/UPDATES.md) for signing, publication and validation.

GitHub Actions builds a versioned debug artifact for developers and, after successful main checks, publishes the official distribution to [Releases](https://github.com/guardiola98/sonora-android/releases), [Packages](https://github.com/guardiola98/sonora-android/pkgs/container/sonora-android) and the existing update channel. Existing published versions are reused exactly. New versions need the persistent signing key configured once in Actions secrets; see [automatic publication and setup](docs/RELEASING.md).

## Features

- Dark Material 3 interface with original Sonora colors and icon, large artwork and subtle screen transitions.
- Podcasts: search Apple’s public directory or paste a public HTTPS RSS feed; subscribe/unsubscribe, refresh episodes, and browse previously loaded descriptions offline.
- Resume episodes from saved positions, mark played/unplayed, seek −10/+10 seconds with round controls beside previous/next episode and choose 0.75–2× speed. Podcast episodes share the queue, favorites, playlists and background player.
- Personalized Home: resume the queue, recent listening, favorites, real discovery shelves, playlists and evidence-backed artist/album groups.
- Remote artist search in the YouTube Music catalog, alongside verified local artist credits. Tap an artist to search their songs; ordinary channels remain separate. No account or metadata opt-in is required.
- Music search with debounce, cancellation, local results, persistent history, optional online suggestions, categories, pagination, explainable ranking and an All versions option.
- **Sonora Radio:** a single song can build and replenish a related queue in the background. Manual queues are respected; Autoplay can be disabled without stopping the current song.
- Track-specific **Not interested**, **More music like this**, recommendation reasons, and local learning from favorites, actual listening, completions and gentle skip/removal signals.
- Optional, keyless MusicBrainz / ListenBrainz enrichment with attributed evidence and expiring caches; it is off by default. No artist, album, genre or language is invented from an uploader name.
- Audio playback, pause, previous/restart, next and seeking.
- Faster starts: prepare likely tracks and the next queue item, with bounded audio prefixes kept only in RAM. Keeps the same audio quality; disable extra data use in About → Faster playback starts. [Strategy and measured results](docs/STARTUP.md).
- Persistent mini-player across Home, Search, Library and Podcasts.
- `MediaSessionService` with automatic media notification, lock-screen controls, audio focus and headphone-disconnect handling.
- Queue: play a list, append, jump, remove, move up and clear; shuffle pending tracks directly from the full player or queue without interrupting the current track; restore the list, selected song and last saved position after process death/reboot, paused until you press Play.
- Create and rename local playlists, add/remove songs, drag handles to reorder with edge scrolling, and accessible move up/down menu actions. Imported playlists can be edited too. Shuffle starts a randomized manual queue without changing saved playlist order; public collections can shuffle their currently loaded tracks.
- Export a versioned Sonora JSON backup of favorites, all history, playlists (including empty ones), podcast subscriptions and episode progress; preview and merge backups atomically without deleting existing data.
- Room favorites, listening history and imported playlists; history starts only when the player reports playback.
- Import official Spotify account-data ZIP/JSON playlists and saved songs without OAuth. Included playlists and saved songs import automatically using the best available YouTube versions, including approximate matches. Up to three searches run concurrently and repeated queries are reused. [Usage, identity and limits](docs/SPOTIFY-EXPORT.md).
- Download songs, playlists and podcast episodes for offline listening. Manage transfers and storage in **Library → Downloads**. [Offline downloads](docs/DOWNLOADS.md).
- Import public Spotify playlist links without an account or developer setup, with automatic selection of playable YouTube equivalents and a notice when the public page may be incomplete. Public/unlisted YouTube Music links include subsequent pages. Both link types save directly after Import playlist, without another confirmation. [Public playlist imports](docs/PUBLIC-PLAYLISTS.md).
- English, Spanish, Catalan, French and Italian interface text, with a labelled **Language** button in the top bar and Android 13+ per-app language settings. [Languages](docs/LANGUAGES.md).
- Import Takeout ZIP/CSV playlists, library songs and exported likes, with a preview and per-list Playlist/Favorites choices. Reimports merge without duplicating entries or removing existing likes.
- Optional missing-title lookup; file imports also work offline. Database upgrades preserve existing favorites and history.
- Network/source errors with retry; short-lived stream cache and one automatic fresh extraction on HTTP 403/410.

Home adapts to your library and listening. New users can search or enter their own interests; no fictitious songs are shown. Local search, library data and cached recommendations work without Internet; streaming still requires a connection.

## Build and test

Install **JDK 21**, Android SDK **platform 36** and **build-tools 35.0.0**. Set `ANDROID_HOME` or create an untracked `local.properties` containing `sdk.dir=/absolute/path/to/android-sdk`.

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The wrapper uses Gradle 8.13 with a pinned distribution SHA-256. Dependency versions are pinned in [`gradle/libs.versions.toml`](gradle/libs.versions.toml). See [dependency decisions and official references](docs/DEPENDENCIES.md).

Unit tests cover persistent Room data, v1→v2→v3→v4→v5 migrations, backup validation/round trips/rollback, playlist editing, queue checkpoints, CSV/ZIP parsing and size bounds, atomic imports, deduplication, independent favorites/history updates, history clearing, stale search cancellation, error/retry behavior stream cache expiry/invalidation/concurrency, cancellation races, and byte-exact prepared audio continuation and seeking. Live source tests are skipped by default so public CI is deterministic.

```bash
# Opt-in: real search, extraction and an HTTP audio-byte probe (not an audio playback test)
SONORA_LIVE_TESTS=true ./gradlew testDebugUnitTest --tests '*LiveSourceTest' --rerun-tasks

# Opt-in: fetch a real public playlist across multiple pages
SONORA_LIVE_IMPORT=true ./gradlew testDebugUnitTest --tests '*LiveImportTest'

# With a connected phone or emulator: real decoder/session/queue/background test
./gradlew connectedDebugAndroidTest

# Opt-in: real podcast discovery, RSS playback, resume and background behavior
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.livePodcasts=true

# Opt-in: real music search/pages, radio, Home, autoplay policy and public metadata
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.liveDiscovery=true

# Opt-in: startup timings, automatic preparation, cached/network handoff and seeking
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.startupBenchmark=true

# Also require successful playback of a real extracted YouTube stream
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.liveYouTube=true
```

The device test creates a temporary WAV fixture at runtime; this is never included in the production catalog. The optional live test depends on upstream availability and network policy. [Device playback smoke test](https://github.com/guardiola98/sonora-android/actions/workflows/device.yml) exposes these options from GitHub's **Run workflow** menu.

## Architecture

```text
Compose screens → MusicViewModel → SearchPresenter → SearchRepository → MusicCatalogSource
                            │                           └── NewPipeMusicSource → external NewPipeExtractor
                            ├── HomeDiscoveryPresenter → DiscoveryRepository → local + public providers
                            ├── ImportPresenter → ImportRepository → LibraryImportSource
                            │                  └── TakeoutParser      └── NewPipeMusicSource
                            ├── PodcastPresenter → PodcastSource → RssPodcastSource / PodcastFeedParser
                            ├── BackupCodec → Room library snapshot / merge
                            ├── Room LibraryDao (schema v5, explicit migrations from v1–v4)
                            └── MediaController → PlaybackService / MediaSession → ExoPlayer
                                                                         ├── RadioCoordinator → DiscoveryRepository
                                                                         └── StreamResolver → MusicSource
```

- `domain/` contains stable track metadata and the `MusicSource` / `LibraryImportSource` / `PodcastSource` interfaces, with no UI/player dependency.
- `data/` owns the OkHttp transport adapter, external extractor integration, short-lived stream cache and Room database.
- `discovery/` contains catalog and evidence models, public metadata providers, ranking, radio policy, settings and local-first recommendation orchestration.
- `offline/` owns persistent audio, download state, the foreground transfer service and cache-only playback.
- `playback/` owns ExoPlayer, the session/service and controller state. The service is the single playback owner.
- `ui/` contains Compose screens, the MVVM presentation layer and lifecycle-aware StateFlow collection.
- A small application graph supplies dependencies; no global Activity or player references are retained by the UI.

Stream URLs are resolved on an IO dispatcher, either by bounded optional preparation or when ExoPlayer needs them. Identical requests share one extraction; unrelated requests can proceed independently. Prepared audio prefixes use the exact resolved URL, expire after five minutes and are never written to disk. They are never persisted in Room or passed to the screens. Signed URL expiry triggers a bounded retry, with a manual retry if the source still fails. Favorites, history, playlists and persisted queue contain canonical source references and metadata only (podcasts use the feed URL and a hash of the publisher’s episode GUID). File parsing is independent of networking and playback; importing does not start playback or create listening-history entries. Import and backup restore transactions either complete or roll back. A process-wide queue writer serializes checkpoints and outlives service teardown; position-only updates avoid rewriting the queue JSON. Core library NIO desugaring supports NewPipeExtractor on API 26–32.

Discovery architecture, ranking, sources, privacy and autoplay policy: [DISCOVERY.md](docs/DISCOVERY.md).

Podcast formats, privacy, limits and manual checks: [PODCASTS.md](docs/PODCASTS.md).

Offline listening, storage and transfer controls: [DOWNLOADS.md](docs/DOWNLOADS.md).

## Beta scope

- Search uses public music categories where supported and pages up to 500 displayed results. Artists are locally matched credits; channels remain channels. Live streams are excluded. Playlist imports retain their separate documented bounds.
- Import is a one-time copy, not account synchronization. Sonora never signs in to Google. Likes/private playlists require exported files; only tracks present in the export can be imported. [Formats and limits](docs/IMPORTING.md).
- Legacy track credit is the source uploader. Optional accepted MusicBrainz identities are stored separately with provenance; incomplete matches do not create album/genre claims. Public related items and sparse metadata cannot guarantee precise subgenre, language or scene matching.
- Queue metadata and position are saved locally. Restoration itself remains paused and does not extract a stream. With faster starts enabled, the foreground screen can separately prepare likely tracks without autoplay. While playing, position checkpoints are written every two seconds; an abrupt kill can lose the last uncommitted interval. A cleared queue stays cleared.
- Offline music and podcast downloads are available in Library → Downloads; see [downloads and limits](docs/DOWNLOADS.md). Persistent account sign-in, casting, an equalizer, repeat, a sleep timer, resumable background imports and more interface languages remain future work.
- Source changes, IP throttling, verification requests and unavailable tracks can prevent playback. Sonora surfaces those failures and does not add credentials, proxies, DRM handling or access-restriction overrides.
- Android force-stop stops playback. Manufacturer battery policies still require testing on physical hardware.
- History shows the 200 most recently played distinct tracks.

See the [backup guide](docs/BACKUPS.md), [playlist guide](docs/PLAYLISTS.md), [real-device checklist](docs/MANUAL_TESTS.md), [privacy notes](PRIVACY.md), [license](LICENSE) and [third-party notices](NOTICE.md).

## Contributing

Use JDK 21, keep extraction behind `MusicSource`, and run the three local checks above. Include a regression test for playback state, persistence or error-handling changes. Do not commit signing files, SDKs, Gradle caches, local paths, credentials or extracted stream URLs. Changes to Room entities require a migration and an exported schema update.

## Open-source acknowledgements

Sonora builds on the work of many maintainers and contributors. These are the main projects used by the app, including libraries brought in by other dependencies. The versions in the [version catalog](gradle/libs.versions.toml) and the resolved Gradle dependency graph identify the artifacts used in a particular build.

| Project | Used for | License / upstream notice |
|---|---|---|
| **[NewPipeExtractor — Team NewPipe and contributors](https://github.com/TeamNewPipe/NewPipeExtractor/tree/v0.27.1)** | YouTube search, playlists, metadata and stream extraction | [GPL-3.0-or-later](https://github.com/TeamNewPipe/NewPipeExtractor/blob/v0.27.1/README.md#license) |
| [AndroidX](https://android.googlesource.com/platform/frameworks/support/) — Compose, Material 3, Room, Lifecycle, Activity, Core and WorkManager | Interface, local database, Android lifecycle and background work | [Apache-2.0](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt) |
| [Media3 / ExoPlayer](https://github.com/androidx/media) | Audio playback, media sessions, notifications and offline playback | [Apache-2.0](https://github.com/androidx/media/blob/1.8.0/LICENSE) |
| [Material icons](https://github.com/google/material-design-icons) | Interface icons | [Apache-2.0](https://github.com/google/material-design-icons/blob/master/LICENSE) |
| [Kotlin](https://github.com/JetBrains/kotlin), [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines), [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) and [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform) support artifacts | Language runtime, asynchronous work and dependency support | Apache-2.0; see the upstream repositories’ license files |
| [OkHttp](https://github.com/square/okhttp) / [Okio](https://github.com/square/okio) | HTTP requests and I/O | [Apache-2.0](https://github.com/square/okhttp/blob/parent-4.12.0/LICENSE.txt) / [Apache-2.0](https://github.com/square/okio/blob/master/LICENSE.txt) |
| [Coil](https://github.com/coil-kt/coil) and [Accompanist DrawablePainter](https://github.com/google/accompanist/tree/v0.37.3/drawablepainter) | Artwork loading and drawable rendering in Compose | [Apache-2.0](https://github.com/coil-kt/coil/blob/3.3.0/LICENSE.txt) / [Apache-2.0](https://github.com/google/accompanist/blob/v0.37.3/LICENSE) |
| [jsoup](https://github.com/jhy/jsoup) | HTML parsing in the extractor | [MIT](https://jsoup.org/license) |
| [nanojson — Team NewPipe fork, based on Matt Mastracci’s nanojson](https://github.com/TeamNewPipe/nanojson/tree/e9d656ddb49a412a5a0a5d5ef20ca7ef09549996) | JSON parsing in the extractor | [Apache-2.0 in the pinned fork’s source headers](https://github.com/TeamNewPipe/nanojson/blob/e9d656ddb49a412a5a0a5d5ef20ca7ef09549996/src/main/java/com/grack/nanojson/JsonParser.java) |
| [Rhino / Rhino engine — Mozilla and contributors](https://github.com/mozilla/rhino) | JavaScript execution used by the extractor | [MPL-2.0](https://github.com/mozilla/rhino/blob/Rhino1_8_1_Release/LICENSE.txt) |
| [Protocol Buffers Java Lite](https://github.com/protocolbuffers/protobuf) | Structured data used by the extractor | [BSD-3-Clause](https://github.com/protocolbuffers/protobuf/blob/v36.2/LICENSE) |
| [Guava](https://github.com/google/guava), including failureaccess / ListenableFuture support | Collections, utilities and asynchronous interoperability | [Apache-2.0](https://github.com/google/guava/blob/v33.3.1/LICENSE) |
| [JetBrains annotations](https://github.com/JetBrains/java-annotations) and [JSpecify](https://github.com/jspecify/jspecify) | Nullability and API annotations used by dependencies | [Apache-2.0](https://github.com/JetBrains/java-annotations/blob/master/LICENSE.txt) / [Apache-2.0](https://github.com/jspecify/jspecify/blob/main/LICENSE) |
| [JSR-305 / JCIP annotations](https://repo.maven.apache.org/maven2/com/google/code/findbugs/jsr305/3.0.2/) | Annotations used by the extractor and its dependencies | Apache-2.0 in the published POM; CC-BY-2.5 notices in the bundled JCIP files; see below |
| [Android desugar JDK libraries](https://github.com/google/desugar_jdk_libs) | Java / NIO compatibility on older Android versions | [GPL-2.0 with the Classpath Exception](https://github.com/google/desugar_jdk_libs/blob/master/LICENSE) |

The pinned **nanojson fork** declares Apache-2.0 in its source headers. For **JSR-305 3.0.2**, the [published POM](https://repo.maven.apache.org/maven2/com/google/code/findbugs/jsr305/3.0.2/jsr305-3.0.2.pom) declares Apache-2.0, while the [source archive](https://repo.maven.apache.org/maven2/com/google/code/findbugs/jsr305/3.0.2/jsr305-3.0.2-sources.jar) includes JCIP annotations with Brian Goetz’s copyright and **CC-BY-2.5** notices. The nanojson and JSR-305 entries above correct the labels in the older [third-party notice overview](NOTICE.md). Preserve the upstream, per-file notices when redistributing.

The build and tests also rely on **[Gradle](https://github.com/gradle/gradle)**, the **[Android Gradle Plugin](https://android.googlesource.com/platform/tools/base/)**, **[KSP](https://github.com/google/ksp)** and **[AndroidX Test](https://github.com/android/android-test)** (Apache-2.0), **[JUnit 4](https://github.com/junit-team/junit4/blob/r4.13.2/LICENSE-junit.txt)** (EPL-1.0) and **[Robolectric](https://github.com/robolectric/robolectric/blob/robolectric-4.16.1/LICENSE)** (MIT). Thank you to their contributors as well.

To inspect the full resolved runtime dependency tree:

```bash
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
```

Optional discovery enrichment also credits the **[MusicBrainz](https://musicbrainz.org/)** and **[ListenBrainz](https://listenbrainz.org/)** communities. Their runtime data has separate terms: MusicBrainz [core data is CC0 and supplementary data is CC-BY-NC-SA-3.0](https://musicbrainz.org/doc/About/Data_License). See [provider attribution and data licensing](NOTICE.md#optional-runtime-metadata) for how Sonora uses these services.

## License

**Sonora’s source code is licensed under GPL-3.0-or-later**: GNU GPL version 3, or, at your option, any later version. It is distributed without warranty. Read the full [LICENSE](LICENSE). NewPipeExtractor is also GPL-3.0-or-later; the other dependencies retain their own copyright notices and license terms listed above.

Each official [release](https://github.com/guardiola98/sonora-android/releases) provides the signed APK, a matching **Sonora source ZIP**, `LICENSE`, `NOTICE.md` and release metadata identifying the exact source commit. Dependency source is maintained in the linked upstream projects and is not vendored into that ZIP. When redistributing Sonora or a modified build, provide the corresponding source as required by the GPL and preserve applicable third-party copyright, license and attribution notices. The README’s credits are an overview, not a replacement for those license texts and source-distribution requirements.

The Sonora launcher artwork is original project artwork. Music, podcasts, artwork and provider metadata retain their respective owners’ rights; the app’s software license does not grant rights to that content. The bundled Spotify attribution logo remains a Spotify brand asset governed by its [branding guidelines](https://developer.spotify.com/documentation/design), outside Sonora’s GPL grant.

Sonora is an independently maintained project, **not affiliated with or endorsed by Team NewPipe, YouTube, Google, Spotify or Apple**. Please report Sonora-specific issues in [this repository](https://github.com/guardiola98/sonora-android/issues).
