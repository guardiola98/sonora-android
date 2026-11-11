# Library backups

In **Library**, tap **Backup and restore** (the restore-clock icon).

## Export

Choose **Export library** and select a destination through Android's document picker. Save outside Sonora's private app storage so the file survives uninstalling. A backup contains favorites, titles/artists/artwork references, playlists with order (including empty playlists), and listening timestamps/counts. It does not contain audio, Google credentials, expiring stream URLs or the playback queue. The JSON is readable and unencrypted; choose a destination appropriate for your listening history. Sonora uploads nothing itself; selecting a cloud document provider gives that provider the file.

## Restore

Choose **Choose Sonora backup**. Sonora validates the entire file and shows counts before writing anything. Press **Merge backup into library** to apply it; Cancel leaves the library unchanged. Keep the app open until it finishes.

- Existing favorites stay favorites. An imported favorite never toggles a heart off.
- The latest listening timestamp and greatest play count are retained, so repeated restores do not multiply history counts.
- Playlists match by stable source/local ID. Existing names and order stay; missing entries are appended. A fresh install restores original backup order and names, including empty lists.
- Restore is one Room transaction; failure rolls back partial writes.
- Exported backup format version 1 supports up to 32 MiB, 50,000 tracks, 2,000 playlists and 100,000 playlist entries. Unsupported versions, duplicate records, noncanonical source URLs and dangling references are rejected.
- Restoration does not start playback or fetch song metadata. Thumbnails may be requested when subsequently displayed.

The file has `format: "sonora-library"`, `version: 1`, `createdAt`, `tracks` and `playlists`. YouTube Takeout CSV/ZIP imports use the separate **Import music** screen.

## Upgrading from older debug builds

Android requires matching signing certificates for an in-place update. If your previous APK was locally built with the same debug key, you can first install the new debug build with that key, export a backup, then install the signed distribution and restore it. If the old APK was signed with a lost CI debug key and has no backup feature, Sonora cannot recover its private data by installing a differently signed APK. Keep that installation until you have decided how to preserve/recreate its data. Never assume uninstalling keeps the library.
