# Sonora 0.7.3-beta01 validation

Validated on 12 November 2026 using JDK 21, SDK 36/build-tools 35.0.0 and the API 35 emulator. This release adds **Shuffle up next** to the full player and queue sheet. It shuffles pending items in one player operation and preserves the current item, position, play/pause state, past items and saved playlist order. It is an action, not a persistent shuffle mode, and needs at least two pending items.

- Debug and signed release APKs, release instrumentation, unit tests and lint completed successfully. **139 unit tests passed, 3 optional live tests skipped; lint reported 0 errors and 8 warnings.** Release-tool tests (3) and OAuth broker test (1) passed.
- Deterministic tests exercise different permutations, duplicate entries, empty queues, the last two queue positions, and unchanged current position/play state using ExoPlayer.
- The signed versionCode 11 APK updated the existing signed 0.7.2 installation with `adb install -r`, without clearing data. Its certificate matches the pinned release certificate.
- **Five device checks passed**, with two optional live-source checks skipped: the new player/queue shuffle check, activity reopening, PCM/session/seek/background playback, playlist shuffle/library preservation and podcast controls. The shuffle test drives both actual buttons, checks playing and paused states, queue membership and prefix, the saved Room checkpoint, Next ordering and the disabled button with one pending item.
- Shuffle and podcast checks also passed at **320 × 570 dp**. The shuffle button and previous/play/next controls remain visible. Captures were inspected at normal and compact sizes.

Actual screenshots: [player](screenshots/release-073-player-shuffle.png), [compact player](screenshots/release-073-player-compact.png), [queue](screenshots/release-073-queue-shuffle.png). Screenshots and audio use labeled, locally generated test fixtures that are not bundled with the application. [Counts and tested runtime hashes](measurements/release-0.7.3/checks.json).

The emulator uses generated PCM with host audio disabled. Physical-device audio, live YouTube/RSS playback and process-death restoration were not revalidated in this release; the persisted shuffled checkpoint was checked. Existing Room schema, backup format, import flows and signing key are unchanged. Uncommitted offline-download work in the maintainer workspace is excluded from this release.
