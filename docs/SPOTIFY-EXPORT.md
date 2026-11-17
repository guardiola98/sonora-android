# Spotify export import

Sonora can read Spotify's official account-data export without connecting an account or requiring developer credentials. Account sign-in was removed in 0.8.1. Google Takeout and YouTube playlist-link imports remain available.

## Use it

1. Open Spotify account → Privacy settings → Download your data and request **Account data**. Wait for Spotify to prepare it, then download it. Sonora does not request the archive on your behalf.
2. In Sonora, open Library → Import music → **Choose Spotify ZIP / JSON**. Choose the account-data ZIP, its `Playlist*.json` files, or `YourLibrary.json`. Multiple JSON files can be selected together. Do not select extended streaming history.
3. All playlists and saved songs in the chosen files are imported automatically. Sonora searches YouTube for their titles and artists and selects the best available versions, including approximate matches when no exact match exists. No per-list selection, song review or second confirmation is needed. Playlists become local playlists; saved songs become Favorites.
4. The completion screen reports entries read, matched and skipped, plus new playlists, favorites and tracks. Podcasts/local-file entries and songs without results are skipped. Failed searches can be retried by importing again. The final save is atomic and preserves existing library data. The app does not transfer Spotify audio.

## Supported metadata and identity

Spotify's [official account-data description](https://support.spotify.com/article/understanding-my-data/) documents playlist names/song/artist metadata and saved-library entities. The parser supports playlist objects with `playlists[].name`, `items[].track.{trackName,artistName,trackUri}` and library `tracks[].{track,artist,uri}`. Fixtures exercise these shapes; no real personal export was provided during development. Shape changes fail clearly instead of inventing a successful import. Albums, shows, profile, payments and listening history are not converted to songs. `msPlayed` is never treated as full track duration.

Source names and first-occurrence chosen-track order are preserved. Spotify's export commonly omits playlist IDs. For those lists the destination is `spotify:export:<SHA-256 of NFC playlist name>`, independent of filename, song count or track changes. This is a **name-based identity**, not a guessed Spotify playlist ID. Reimporting the same name merges new canonical YouTube URLs; renaming a source playlist creates a new destination. Different accounts with equal playlist names also share that file-import destination: choose a distinct source name before exporting if those should remain separate. Existing local order/edits/favorites/history are preserved.

Same-name lists with different contents in a selected set are rejected, so their identities cannot silently collapse during parsing. Identical duplicate files collapse once. When an export explicitly includes a valid Spotify playlist URI, it shares the `spotify:<ID>` destination with public-link imports. Otherwise file and public-link imports remain separate. Saved songs share `spotify:likes`; no account identifier is stored. A different chosen YouTube URL is a distinct track, even if it represents the same recording.

## Privacy and bounds

ZIP entries are never extracted. Only safe `Playlist*.json` and `YourLibrary.json` paths are decoded; traversal paths and unrelated files are discarded. All decompressed bytes, including ignored entries, count toward a 32 MiB limit. An individual decoded JSON file is limited to 8 MiB, JSON depth to 32, ZIP entries to 500, selection to 20 files, playlists to 2,000 and tracks to 10,000. Oversize imports fail before saving. For large account archives, select just the extracted playlist/library JSON using your file manager.

Reading runs on the IO dispatcher with interruptible cancellation. Source metadata remains in memory during the import; no original file, pending playlist or account profile is added to Room/preferences/backups/logs. Only matched playable references and playlist identities are saved. Online matching needs a connection; no connected account is needed. Matching runs at most three searches concurrently, caches repeated title/artist/duration queries for this import, and has a 20-second timeout per request. The former 200-search cap and pause after empty results have been removed. Source order is retained even when responses complete out of order. Cancel before saving discards the in-memory import; the brief database transaction cannot be cancelled from the screen.

## Direct-account imports remain blocked on external setup


Google Data Portability was rechecked as a possible permanent-copy alternative. Its [official workflow](https://developers.google.com/data-portability/user-guide/introduction) needs app approval and geographic availability. Archive [methods](https://developers.google.com/data-portability/user-guide/methods) can take minutes, hours or days and suggest polling every 5–60 minutes. It is not an immediate list-picker replacement within Sonora's memory-only, no-refresh-token session. No new infrastructure or untested portability login is shipped. User-initiated Takeout remains the usable Google route.
