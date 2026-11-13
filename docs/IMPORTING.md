# Import YouTube Music into Sonora

Open **Library → Import music** (the import icon in the library header). Import is a one-time copy into Sonora's local database. It does not change your Google account or keep the two libraries synchronized.

## Public or unlisted playlist link

1. Copy the playlist's share link from YouTube Music or YouTube.
2. Paste it under **Import a playlist link**, then choose **Preview playlist**.
3. Review the title/count and choose **Playlist**, **Favorites**, both, or neither.
4. Tap **Import selected music**. Open **Library → Playlists** to play it.

Sonora follows subsequent pages, preserves source order and skips duplicate/unavailable entries. If a page fails, nothing is saved; retry or use an export. Account-only Likes (`LM`/`LL`), Watch Later and dynamic radio/mix links are not supported through this route. Sonora does not request credentials or bypass access restrictions.

## Private playlists, library songs and likes

1. Open [Google Takeout](https://takeout.google.com/) in your browser. Deselect unrelated products and select **YouTube and YouTube Music**.
2. In the available data options, include playlists and music-library data. Leave uploaded videos and unrelated data out to keep the export small.
3. Request a **downloadable ZIP**. Download it when Google makes it available. Google's [export instructions](https://support.google.com/accounts/answer/3024190?hl=en) describe delivery options.
4. Return to Sonora and choose **Choose ZIP / CSV files**. Select the ZIP, or extracted playlist/music CSV files. Multiple CSV files can be selected together.
5. In the preview, choose **Favorites** for the list containing your liked songs. Familiar names such as `Liked Music` suggest this automatically; review the choice because export names and contents vary. You may also keep those songs as a named playlist.
6. Tap **Import selected music** and keep Sonora open until it finishes.

Google's direct transfer-to-another-service flow excludes Liked Music, among other playlists; it is not this ZIP/CSV workflow ([Google's transfer documentation](https://support.google.com/accounts/answer/14792019?hl=en)). Downloaded Takeout content can also vary. Sonora can import only songs actually present in a supported file; it cannot recover omitted likes. If a supported playlist contains your liked songs, selecting **Favorites** imports those hearts regardless of the filename.

No sign-in occurs inside Sonora, and no Google password, cookie or API key is needed. Albums/artists without individual video references, watch-history JSON/HTML, uploaded audio files and arbitrary third-party export formats are not imported. Importing songs never adds them to listening history.

## Preview, offline use and reimporting

Nothing is written before **Import selected music**. Preview reports duplicates, invalid/unavailable entries and ignored files. Unselect both destinations to skip a list.

Files containing only video IDs can be imported offline: disable **Look up missing titles online**. Songs are saved as `YouTube track <id>` and remain playable if the source is accessible. Optional lookup has a 20-second timeout per request, at most three concurrent requests, and stops further lookups after repeated network/restriction failures. Failed lookups retain the video reference. Reimport with lookup enabled to retry. Streaming requires a connection; [completed downloads](DOWNLOADS.md) play offline.

Reimports add missing entries and likes. They never remove existing entries, unset hearts or modify listening history. Existing order stays intact; newly seen tracks are appended. The same track occurs once per playlist and once in Favorites. The transaction rolls back on failure. **Cancel import** is available while reading/preparing; the brief final database transaction cannot be cancelled from the screen. Import is not scheduled as a background job and is not resumed after process death.

Online playlist IDs and IDs present in older Takeout metadata identify the destination. CSVs without playlist IDs use their filename (without `.csv` or `-videos`), so importing the same named file merges it. Renaming such a file creates another playlist; different exports with the same name merge. No deletion in YouTube is mirrored in Sonora. Deleting a playlist in Sonora preserves favorites and history.

## Supported CSV data and limits

UTF-8 CSV, with or without BOM; comma or semicolon separator; quoted fields, escaped quotes and embedded newlines. Supported reference headers include `Video ID`, `YouTube ID`, `Song URL`, `Video URL`, `Track URL` and `URL`; common Spanish aliases are accepted. References must be 11-character YouTube IDs or recognized YouTube/Music video URLs. Optional title, artist and duration columns are used when present. Older Takeout files with a `Playlist ID`/`Title` metadata section followed by a `Video ID` section are supported.

Example (synthetic reference; not a bundled catalog):

```csv
Video ID,Title,Artist
abcdefghijk,Example song,Example artist
```

- Up to 20 selected files and 10,000 track entries per import.
- Up to 32 MiB decompressed data per selected file/archive, including ignored ZIP entries.
- Up to 500 ZIP entries; 25,000 CSV rows, 100 fields per row, 16,384 characters per field.
- ZIP contents are read in memory; no archive paths are extracted to storage. Oversized/malformed files fail before saving.

For a larger Takeout archive, extract it yourself and select only the relevant CSVs in smaller batches. Some export format variations may require parser updates; report the column headings and a redacted synthetic example, not your private archive.

For public Spotify links, see [public playlist import](PUBLIC-PLAYLISTS.md).
