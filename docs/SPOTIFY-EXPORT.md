# Spotify export import

Sonora 0.7.1 can read Spotify's official account-data export without connecting an account or requiring developer credentials. The account OAuth adapter remains a separate, configuration-gated option. Google Takeout and YouTube playlist-link imports remain available.

## Use it

1. Open Spotify account → Privacy settings → Download your data and request **Account data**. Wait for Spotify to prepare it, then download it. Sonora does not request the archive on your behalf.
2. In Sonora, open Library → Import music → **Choose Spotify ZIP / JSON**. Choose the account-data ZIP, its `Playlist*.json` files, or `YourLibrary.json`. Multiple JSON files can be selected together. Do not select extended streaming history.
3. Select the playlists/saved songs to copy. Before confirming, choose whether to search YouTube for their titles and artists. Disable search for offline review. Parsing and selection never make Spotify API requests or search unselected playlists.
4. Review playable versions. Official exports usually omit duration, so **no exported song is automatically selected**. Listen to a candidate if needed, then choose it; use Search again or a YouTube video link for missing versions. Podcasts/local-file entries remain pending. The app does not transfer Spotify audio.
5. Continue to the normal local import preview and choose Playlist, Favorites, both or neither. Saved songs suggest Favorites. The final save is atomic. Pending entries can only be omitted after explicit acknowledgement and are not retained for later; reopen the original file to revisit them.

## Supported metadata and identity

Spotify's [official account-data description](https://support.spotify.com/article/understanding-my-data/) documents playlist names/song/artist metadata and saved-library entities. The parser supports playlist objects with `playlists[].name`, `items[].track.{trackName,artistName,trackUri}` and library `tracks[].{track,artist,uri}`. Fixtures exercise these shapes; no real personal export was provided during development. Shape changes fail clearly instead of inventing a successful import. Albums, shows, profile, payments and listening history are not converted to songs. `msPlayed` is never treated as full track duration.

Source names and first-occurrence chosen-track order are preserved. Spotify's export commonly omits playlist IDs. For those lists the destination is `spotify:export:<SHA-256 of NFC playlist name>`, independent of filename, song count or track changes. This is a **name-based identity**, not a guessed Spotify playlist ID. Reimporting the same name merges new canonical YouTube URLs; renaming a source playlist creates a new destination. Different accounts with equal playlist names also share that file-import destination: choose a distinct source name before exporting if those should remain separate. Existing local order/edits/favorites/history are preserved.

Same-name lists with different contents in a selected set are rejected, so their identities cannot silently collapse during preview. Identical duplicate files collapse once. When an export explicitly includes a valid Spotify playlist URI, it shares the `spotify:<ID>` destination with OAuth imports. Otherwise file and OAuth imports remain separate. Saved songs share `spotify:likes`; no account identifier is stored. A different chosen YouTube URL is a distinct track, even if it represents the same recording.

## Privacy and bounds

ZIP entries are never extracted. Only safe `Playlist*.json` and `YourLibrary.json` paths are decoded; traversal paths and unrelated files are discarded. All decompressed bytes, including ignored entries, count toward a 32 MiB limit. An individual decoded JSON file is limited to 8 MiB, JSON depth to 32, ZIP entries to 500, selection to 20 files, playlists to 2,000 and tracks to 10,000. Oversize imports fail before saving. For large account archives, select just the extracted playlist/library JSON using your file manager.

Reading runs on the IO dispatcher with interruptible cancellation. Selected/unselected export metadata stays in memory until confirmation or cancellation; no original file, pending playlist, OAuth credential or account profile is added to Room/preferences/backups/logs. Only confirmed playable library entries and playlist identities are saved. Parsing and review do not require a connected account; individual lookups and enabled automatic matching require a network connection. Matching retains the existing 200-search limit, sequential pacing, timeout and pause after repeated empty/failing searches.

## Direct-account imports remain blocked on external setup

No Spotify client ID or tester account was available, and no Google OAuth project, approved use case or broker deployment was supplied. Do not advertise the released default APK as having a working Spotify/Google login. See [OAuth setup](OAUTH-IMPORT.md).

Google Data Portability was rechecked as a possible permanent-copy alternative. Its [official workflow](https://developers.google.com/data-portability/user-guide/introduction) needs app approval and geographic availability. Archive [methods](https://developers.google.com/data-portability/user-guide/methods) can take minutes, hours or days and suggest polling every 5–60 minutes. It is not an immediate list-picker replacement within Sonora's memory-only, no-refresh-token session. No new infrastructure or untested portability login is shipped. User-initiated Takeout remains the usable Google route.
