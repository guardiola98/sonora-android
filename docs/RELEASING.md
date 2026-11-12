# Stable signing and publication

The official APK distribution uses one persistent release certificate. Its public SHA-256 fingerprint is tracked in `signing/certificate.sha256`. The private PKCS#12 keystore and signing properties are stored in the maintainer checkout's ignored `.signing/` directory (directory mode 0700, files 0600). They are not committed, embedded in the APK, uploaded to artifacts or included in source snapshots. A recovery copy is also stored locally at `~/.local/share/sonora-android/signing`, outside the checkout, with the same restrictive permissions. Keep an additional secure off-device backup of the key material: losing the key prevents future in-place updates. Do not generate a replacement key for an existing release line.

Android's [app signing documentation](https://developer.android.com/studio/publish/app-signing) explains certificate continuity for updates. Older debug APKs may have incompatible keys; see [backup/upgrade notes](BACKUPS.md). Stable continuity begins with the 0.2 signed distribution; it cannot retroactively replace a lost CI debug certificate.

## Local release

Use JDK 21 and the SDK documented in README. The ignored `.signing/signing.properties` contains `storeFile`, `storePassword`, `keyAlias`, and `keyPassword`. Environment variables `SONORA_KEYSTORE_PATH`, `SONORA_STORE_PASSWORD`, `SONORA_KEY_ALIAS`, and `SONORA_KEY_PASSWORD` override those values. No placeholder credentials are bundled. Without signing configuration, Gradle can build an unsigned release, but publication verification rejects it.

After tests pass and all source/docs changes are committed:

```bash
python3 tools/build-release.py
python3 tools/publish-release.py
```

The build helper verifies the release signature, pinned certificate, application ID and non-debuggable manifest and records the clean source commit plus APK SHA-256. The publisher requires that provenance to match the current clean commit and APK. It uses the existing SSH remote to append a version directory on the separate `apk-releases` branch containing APK, exact `git archive` source ZIP, GPL license, notices, SHA256SUMS and public release metadata. Existing versions are not replaced. No private signing material enters that branch. Main remains a source-only branch.

For release instrumentation, `./gradlew -Psonora.testBuildType=release assembleReleaseAndroidTest` selects the signed release variant. The normal device workflow continues to test debug. See VALIDATION.md for the actual observed release tests.

## Automatic GitHub Releases and Packages

`Android beta` compiles, tests and lints every main push. After a successful main push, `Publish official Sonora` publishes the version in `app/build.gradle.kts` to three destinations:

- **GitHub Releases**: versioned signed APK, exact GPL source archive, notices, metadata and checksums. Beta versions are marked as prereleases.
- **GitHub Packages**: `ghcr.io/guardiola98/sonora-android:<version>`, an OCI distribution image containing those same files in `/release/`. It is a distribution artifact, not a runnable Android container. GitHub Packages supports Docker/OCI images, not a native APK package type.
- **`apk-releases`**: the existing append-only distribution branch and `latest.json`, preserving compatibility with in-app updates.

Publication is serialized and only runs on trusted main push builds or manual dispatch from main (which runs unit tests and lint itself). Pull requests and forks cannot invoke the publishing job. `GITHUB_TOKEN` has `contents: write` and `packages: write` only in that job; no personal token is needed for publication. Re-running an existing version reuses the exact already-published signed APK and source archive and refuses to overwrite mismatched assets. A new version must increase both the name and code and have `docs/release-notes/<version>.md`.

The initial run can mirror the already-published 0.7.2 distribution without signing credentials. **Future new builds require the existing persistent signing key in Actions secrets.** To configure those once, install GitHub CLI, authenticate with repository secret administration access, then run locally:

```bash
gh auth login
python3 tools/configure-ci-signing.py
```

The helper sends the existing `.signing/signing.properties` values and PKCS#12 file to the encrypted repository secrets `SONORA_KEYSTORE_BASE64`, `SONORA_STORE_PASSWORD`, `SONORA_KEY_ALIAS` and `SONORA_KEY_PASSWORD`, using stdin rather than command arguments. It never prints or commits their values. Missing secrets fail a new publication explicitly; no unsigned or newly keyed substitute is published. The current Codex GitHub connector cannot administer Actions secrets, and SSH push access alone cannot configure them.

If enabled, the public OAuth configuration belongs in repository **variables** `SONORA_SPOTIFY_CLIENT_ID`, `SONORA_GOOGLE_CLIENT_ID`, `SONORA_GOOGLE_OAUTH_HOST`, and `SONORA_GOOGLE_IMPORT_APPROVED`. These are optional; preserve the values used for a release that enables those import providers. Client secrets never belong in the Android APK.

GitHub initially creates Packages with private visibility. Set the package visibility to public in its GitHub settings if anonymous registry pulls are desired; regular users always have the public APK download in Releases. The package links to this repository through its OCI source label.

The separate `sonora-web` project reads GitHub Releases (including betas) and the existing distribution manifest, refreshing every five minutes. It does not require a web redeployment when a new app version is published.

References: [GitHub Container registry](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry), [Releases API](https://docs.github.com/en/rest/releases/releases), [Actions secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets).

## Beta release code retention

Code shrinking and obfuscation are disabled for this beta. Release APKs remain non-debuggable and use the pinned persistent signing key. This makes diagnostics easier and lets instrumentation exercise the exact published application classes. An attempted minified test run failed in AndroidJUnitRunner before app startup because R8 removed shared tracing classes; that configuration is not the published build. Shrinking can be revisited with a dedicated external release-test harness. This choice increases APK size; it does not add debug access, account credentials or a different signing key.
