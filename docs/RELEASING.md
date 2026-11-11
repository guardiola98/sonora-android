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

## Optional Actions signing

The available GitHub connection could push source over SSH but had no repository-secret administration capability. The signed APK is therefore built locally and published over SSH. **Actions signing is prepared, not claimed as configured.** The debug build/test/lint artifact remains automatic.

An administrator can later add the existing key as the encrypted repository secrets `SONORA_KEYSTORE_BASE64` (base64 of the PKCS#12 file), `SONORA_STORE_PASSWORD`, `SONORA_KEY_ALIAS` and `SONORA_KEY_PASSWORD`, following [GitHub's secrets documentation](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets). Use the same key whose public fingerprint is pinned, not a newly generated key.

The workflow exposes these secrets only to a signing step for trusted main/tag builds, never pull-request code. It decodes the keystore into the runner's temporary directory, removes it on exit, verifies the resulting APK against the pinned certificate and uploads only APK, source, license and checksums. Without the secrets it skips signing explicitly and builds the development artifact. It never generates an ephemeral key and labels it as an official release.

## Beta release code retention

Code shrinking and obfuscation are disabled for this beta. Release APKs remain non-debuggable and use the pinned persistent signing key. This makes diagnostics easier and lets instrumentation exercise the exact published application classes. An attempted minified test run failed in AndroidJUnitRunner before app startup because R8 removed shared tracing classes; that configuration is not the published build. Shrinking can be revisited with a dedicated external release-test harness. This choice increases APK size; it does not add debug access, account credentials or a different signing key.
