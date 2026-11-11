# Podcasts

Sonora 0.3 adds public RSS podcasts without an account. Open **Podcasts**, search by name or choose **Add RSS feed** and paste a public HTTPS RSS 2.0 URL. Search discovery uses [Apple’s public Search API](https://performance-partners.apple.com/search-api) with the device country, up to 40 results per explicit search and a three-second request interval. Search uses only show titles/authors/feed addresses from that directory; publisher artwork, descriptions and full episode audio come from RSS, not Apple preview audio.

Open a show to load episodes, then Subscribe to save it. Refresh requests its current feed. The last loaded episode metadata remains available offline, but playback always needs a connection. Subscription refresh is on demand, not a scheduled background worker. Unsubscribing retains cached metadata and progress.

Episodes share Sonora’s Media3 service, persistent mini-player, mixed queue, favorites and playlists. Play/Resume restores that episode’s last position; automatic queue transitions also resume it. Use −15/+30 seconds or the seek slider in the full player. Speed options are 0.75, 1, 1.25, 1.5 and 2×; music transitions reset speed to 1×. Speed is not persisted. A finished episode restarts at zero. Mark played/unplayed updates local state; if it is the current episode, it is removed from the queue first to prevent playback checkpoints undoing that choice.

Progress is saved on playback events and about every two seconds while playing, using one ordered writer. An abrupt process kill can lose the last uncommitted interval. The whole queue restores paused after restart; restoring alone never fetches audio. Subscription and progress data are included in Sonora version-2 backups. Version-1 music backups remain readable.

## Formats and limits

The parser follows [RSS 2.0](https://www.rssboard.org/rss-specification), with iTunes author/image/duration fields. It accepts public HTTPS feeds/artwork/audio on the default port, up to 16 MiB per feed, depth 32 and 500 playable audio items. It handles GUID-based identity, publication dates, descriptions and audio enclosures. DTDs/external entities, HTML pages, Atom feeds, video-only items, HTTP-only URLs, login/payment feeds and private network feeds are unsupported. Descriptions are rendered as plain text, never in a WebView. A feed without a GUID falls back to its enclosure URL for identity; changing that URL can produce a new episode identity.

RSS audio URLs are resolved when an episode is opened and retained only in a short-lived memory cache. Room and backups store feed URLs plus hashed episode GUIDs, not signed audio addresses. HTTP 403/410 invalidates both caches and retries once; manual Retry can fetch a fresh feed. If an episode is removed from the publisher’s current 500-item catalog, it cannot be resolved even if its old metadata remains in a favorite or playlist.

Publishers control content, embedded advertising, audio availability and their measurement redirects. Their servers receive normal network metadata; see [privacy](../PRIVACY.md). There are no podcast downloads, paid-feed access, account sync or credentials. The existing pinned Android dependencies are unchanged; RSS parsing uses Android’s XML parser and the existing OkHttp client.
