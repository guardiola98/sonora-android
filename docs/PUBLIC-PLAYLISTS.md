# Import public playlists

Open **Library → Import music → Import a playlist link**, paste a public Spotify or YouTube Music playlist URL, and choose **Preview playlist**. This works in the standard APK without signing in, creating a developer account or configuring OAuth.

## Spotify

Accepted links include `https://open.spotify.com/playlist/<id>`, locale-prefixed and embed playlist URLs, `spotify:playlist:<id>` and `https://spotify.link/<code>` share links. Short-link redirects are limited to validated Spotify playlist/share hosts; arbitrary destinations, albums, individual tracks and private saved-song collections are rejected.

Sonora reads the publicly exposed playlist name, track titles, artist credits and durations from Spotify's embed page. No cookies, account tokens, Spotify audio previews or private endpoints are used. The request is bounded to 2 MB and can be cancelled.

**The public embed can expose only part of a playlist.** Sonora shows the returned count and this limitation before selection. Compare the count with Spotify before continuing. For missing tracks or unavailable public metadata, use a [Spotify account-data ZIP/JSON export](SPOTIFY-EXPORT.md). Public page changes can temporarily break this import; there is no private-account fallback.

Select the playlist and review the suggested playable YouTube versions. Only then confirm, choose your local Playlist/Favorites destination and save. Matching sends the selected titles and artist names to YouTube and uses conservative title/credit/duration/version checks. You can turn automatic search off. Ambiguous or unavailable entries stay pending until you choose a version or explicitly acknowledge skipping them. The source order is preserved; reimporting the same Spotify playlist ID merges new entries rather than making duplicates. Spotify audio is never transferred.

## YouTube Music

Public and unlisted YouTube Music/YouTube playlist links use the existing paginated import, up to 10,000 entries. Sonora preserves accessible track order and reports unavailable or duplicate entries. Private Likes, dynamic mixes and private playlists require a [Takeout ZIP/CSV export](IMPORTING.md).

## Offline listening

Importing copies playlist metadata into your local library. To listen offline, open the imported playlist and choose **Download playlist**. See [downloads](DOWNLOADS.md) for progress, Wi-Fi preferences and storage controls.

The optional account-authorization adapters remain separate under [OAuth setup](OAUTH-IMPORT.md). Public links do not require those adapters. Background/resumable import jobs are not included: keep Sonora open during preview and review.

Source reference: Spotify's [public embeds](https://developer.spotify.com/documentation/embeds). Their public page format is an external dependency, not a guaranteed full-playlist export API.
