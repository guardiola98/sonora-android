# Import public playlists

Open **Library → Import music**. The first option is **1 YouTube Music**, followed by **2 Spotify**. Choose the provider, tap **Paste link** (or enter it manually), then **Import playlist**. YouTube Music supports public/unlisted playlists; Spotify requires a public playlist. A link for the other provider shows an inline explanation without starting an import. This works in the standard APK without signing in, creating a developer account or configuring OAuth.

## Spotify

Accepted links include `https://open.spotify.com/playlist/<id>`, locale-prefixed and embed playlist URLs, `spotify:playlist:<id>` and `https://spotify.link/<code>` share links. Short-link redirects are limited to validated Spotify playlist/share hosts; arbitrary destinations, albums, individual tracks and private saved-song collections are rejected.

Sonora reads the publicly exposed playlist name, track titles, artist credits and durations from Spotify's embed page. No cookies, account tokens, Spotify audio previews or private endpoints are used. The request is bounded to 2 MB and can be cancelled.

**The public embed can expose only part of a playlist.** Sonora shows this limitation in the link dialog and import status, and reports the returned entry count after importing. For missing tracks or unavailable public metadata, use a [Spotify account-data ZIP/JSON export](SPOTIFY-EXPORT.md). Public page changes can temporarily break this import; there is no private-account fallback.

Tap **Import playlist** once. Sonora searches YouTube for the titles and artists, ranks the available versions by title, credit, version and duration, and saves the best available result automatically. Missing duration, several similar results and approximate matches no longer require review or confirmation. This is a best-effort match, not a guarantee of recording identity. Entries with no result are skipped and counted; failed searches are reported so you can reimport to retry. If none of the entries can be matched, nothing is saved and an error is shown.

Up to three searches run concurrently, with one request per distinct title/artist/duration within the import. Empty results do not halt later songs, and there is no 200-search cutoff. Each lookup has a 20-second timeout. Order is reconstructed from the source, regardless of response order. Reimporting the same Spotify playlist ID merges new canonical YouTube references without removing existing entries, favorites or history. A different YouTube URL still represents a different library track, even when it is another upload of the same recording. Spotify audio is never transferred.

## YouTube Music

Public and unlisted YouTube Music/YouTube playlist links use the existing paginated import, up to 10,000 entries. The same **Import playlist** action saves directly, without a destination-selection screen. Sonora preserves accessible track order, deduplicates entries and reports unavailable entries. Private Likes, dynamic mixes and private playlists require a [Takeout ZIP/CSV export](IMPORTING.md).

## Offline listening

Importing copies playlist metadata into your local library. To listen offline, open the imported playlist and choose **Download playlist**. See [downloads](DOWNLOADS.md) for progress, Wi-Fi preferences and storage controls.

OAuth account sign-in, callbacks, client configuration and the optional broker were removed in 0.8.1. Background/resumable import jobs are not included: keep Sonora open until the import finishes. Cancel is available during reading/searching; the final atomic save completes without interruption.

Source reference: Spotify's [public embeds](https://developer.spotify.com/documentation/embeds). Their public page format is an external dependency, not a guaranteed full-playlist export API.
