# Dependency decisions

Checked 2026-11-10 against official documentation and published Maven/JitPack artifacts. Beta 0.1 uses a deliberately pinned stable toolchain rather than adopting every latest major version. The first successful `debugRuntimeClasspath` resolution provides an additional compatibility check; APK compilation and tests are the final checks.

| Component | Selected stable version | Reason / authoritative reference |
|---|---|---|
| Gradle | 8.13 | [AGP 8.13 compatibility](https://developer.android.com/build/releases/agp-8-13-0-release-notes); distribution checksum verified by wrapper |
| Android Gradle Plugin | 8.13.2 | Supports API 36 and Kotlin 2.2; avoids AGP 9 built-in Kotlin migration in the first beta |
| JDK / bytecode | 21 / 17 | Supported Gradle runtime; Android desugaring enabled |
| Kotlin + Compose compiler | 2.2.21 | Same-version compiler plugin; [Kotlin releases](https://kotlinlang.org/docs/releases.html) |
| KSP | 2.2.21-2.0.4 | Matches Kotlin 2.2.21; [releases](https://github.com/google/ksp/releases) |
| Compose BOM | 2025.11.01 | Published stable BOM; coherent Compose runtime/UI/Material 3 versions; [BOM mapping](https://developer.android.com/develop/ui/compose/bom/bom-mapping) |
| Material icons | 1.7.8 | Last stable standalone icons line; vector resources only |
| Activity / Core | 1.11.0 / 1.17.0 | API 36 compatible stable AndroidX versions |
| Lifecycle | 2.9.4 | Stable lifecycle-aware Compose collection and ViewModel |
| Media3 | 1.8.0 | Stable ExoPlayer/session API used here; [release notes](https://developer.android.com/jetpack/androidx/releases/media3) |
| Room | 2.8.4 | Stable KSP-based persistence; [release notes](https://developer.android.com/jetpack/androidx/releases/room) |
| Coroutines | 1.10.2 | Stable Kotlin 2.x line; [releases](https://github.com/Kotlin/kotlinx.coroutines/releases) |
| NewPipeExtractor | v0.27.1 | Latest stable reported by upstream release API on verification date; [release](https://github.com/TeamNewPipe/NewPipeExtractor/releases/tag/v0.27.1) |
| OkHttp | 4.12.0 | Stable transport API; [releases](https://github.com/square/okhttp/releases) |
| Coil | 3.3.0 | Stable Compose image loading; [releases](https://github.com/coil-kt/coil/releases) |
| NIO desugaring | 2.1.5 | Required by [NewPipeExtractor below API 33](https://github.com/TeamNewPipe/NewPipeExtractor/tree/v0.27.1#usage) |
| JUnit / Robolectric | 4.13.2 / 4.16.1 | Deterministic JVM logic and actual Room/SQLite tests |
| AndroidX Test core / runner / JUnit extension | 1.7.0 / 1.7.0 / 1.3.0 | Device instrumentation and ActivityScenario |

Newer stable branches exist (for example AGP 9.5.x, Media3 1.11.x, Room 2.8.5 and Kotlin 2.4.x). They are not required by this app's APIs. Selected versions are not labeled “latest”; upgrade them together with regression testing. NewPipeExtractor is kept current because extraction compatibility depends on upstream service changes.

NewPipeExtractor transitively supplies jsoup, nanojson, protobuf-javalite, Rhino and JSR-305. Versions come from its published POM. JitPack is restricted to the two NewPipe groups, and no dynamic or snapshot dependency versions are used. No extractor source is copied or patched.

The current command-line SDK tool emits a schema-version warning when used alongside AGP 8.13.2; installed API 36 and build-tools 35 are still recognized. This warning is not a reason to skip APK/test validation.
