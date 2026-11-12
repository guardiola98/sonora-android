# In-app updates

Sonora uses the existing `apk-releases/latest.json` on raw.githubusercontent.com. The CI publishes the exact source archive, signed APK, version code and digest to the distribution branch, GitHub Releases and GitHub Packages. The app keeps using the same branch manifest for compatibility. The publisher adds `sizeBytes` and plain-text `notes` from `docs/release-notes/<version>.md`. Older manifests still work.

On a release build WorkManager checks roughly daily when connected and the battery is not low. About → Updates shows the most recent successful result. A manual check is available in both debug and release builds. Only the manifest is checked automatically. Download starts from the Updates dialog. Leaving the dialog cancels an active transfer. A failed or cancelled download can be restarted. Completed APKs are kept in app-private cache.

Sonora restricts APK downloads to the expected path on its distribution branch and verifies the SHA-256, application ID, version code, version name and the APK's actual signing certificate against the fingerprint embedded at build time from `signing/certificate.sha256`. The manifest cannot supply the trusted fingerprint. The installed app must also use the official certificate. The update is handed to Android through a read-only content URI, and Android may require allowing installs from Sonora first. Android confirms the in-place update. The application ID, release key and data schema stay the same.

## Publish

After a successful main CI run, the publication workflow distributes the app automatically. New versions require the existing signing key configured in Actions secrets. The manual `tools/build-release.py` and `tools/publish-release.py` commands remain available as a fallback. See [publication and one-time setup](RELEASING.md). Both paths verify the certificate and preserve the existing distribution channel.

## Validate on device

Install a signed build over the preceding official APK, seed favorites, a playlist, history and a non-default setting, and point the client at a published higher signed version by installing the older of the two versions. Open About → Updates and check the release notes. Cancel a download, retry, grant install permission if prompted, and confirm in Android. Reopen Sonora and confirm the new version and the seeded data. Repeat with a disconnected network and constrained storage. Use Android 8 and a recent API level to cover both certificate APIs. Debug builds can check for versions but do not offer an official APK over a debug certificate.
