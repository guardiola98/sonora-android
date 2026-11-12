# Home, search and Sonora Radio (0.5 beta)

Sonora's discovery system runs on the device. It combines public-source candidates with local library and listening signals, and explains the evidence it used. It has no mandatory server, paid backend, account connection or trained embedding model.

## Using it

Home shows a saved-queue resume card, recent listening, favorites, real discovery candidates, matched artist/album groups when available, chosen search interests and saved playlists. Empty libraries show real search entry points, not seeded music. Covers come from the source. Source credit/channel groups are explicitly distinguished from MusicBrainz-matched artists.

- Tap a **single song in Home/Search** to start playback and, with **Autoplay** enabled, a related-music radio. **Start radio** is available from song menus and the full player, including songs inside a manual playlist. That explicit action enables Autoplay and replaces the current queue with a new radio seed.
- **Play loaded tracks** and existing playlist/library list playback create a manual queue. Adding, removing, moving or replacing entries manually ends automatic extension. Existing visible radio entries are retained unless you remove them; they keep their origin labels. A manual queue is never silently replaced by recommendations.
- Turning Autoplay off cancels pending discovery, removes only upcoming automatic items, and lets the current song and any remaining manual items finish. Turning it back on does not inject music into a manual queue; choose a single song or Start radio.
- **More music like this** records an explicit local preference and refreshes Home/current radio; it does not replace a manual queue. **Not interested** hides that source track from recommendations and removes it from the active radio queue. **Why this / music details** shows score contributions, candidate origin and available metadata provenance.
- **Discovery settings** is available through Home's tune icon and About. It controls Autoplay, local personalization, public metadata enrichment, online typeahead, up to five user-written search interests, clearing search history and resetting learned/hidden preferences. Reset retains favorites, playlists, podcasts and listening history. Old play counts stop informing ranking after reset; explicit favorites still count.

## Search

Typing waits 350 ms for suggestions and 450 ms for search. New input cancels prior work; generation checks also reject late responses from operations that cannot immediately cancel. Explicit submissions enter a normalized, persistent 50-query history. Partial typed queries are not stored as completed searches. Online suggestions can be disabled independently.

Local matching normalizes Unicode accents, case and punctuation and searches up to the supported 50,000 library records off the main thread. Remote queries use NewPipeExtractor's public search interfaces. Category selection only fetches the chosen category:

| Category | Evidence and behavior |
| --- | --- |
| Songs | `music_songs`; if unavailable, fall back to public video search with an explicit notice and musical ranking |
| Artists | Remote `music_artists` results from YouTube Music plus MusicBrainz-matched local artist credits; selecting an artist searches songs using the artist name |
| Albums | Source-provided `music_albums`, plus unambiguous local MusicBrainz album groups |
| Playlists | Source-provided `music_playlists`; open and page actual tracks, play or save the loaded subset |
| Channels | Public `channels` search, labeled as channels; selecting one searches music using its source name |

From 0.7.2, Sonora uses the pinned NewPipeExtractor v0.27.1 `music_artists` search path. Although its available-filter enumeration comments out this category, its query handler accepts it and its music search extractor implements artist responses and continuation pages. Sonora calls this external API without copying extractor code. See the upstream [query handler](https://github.com/TeamNewPipe/NewPipeExtractor/blob/v0.27.1/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/linkHandler/YoutubeSearchQueryHandlerFactory.java) and [music search extractor](https://github.com/TeamNewPipe/NewPipeExtractor/blob/v0.27.1/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/extractors/YoutubeMusicSearchExtractor.java).

Only channel-shaped items returned by this dedicated music artist filter are labeled **YouTube Music artist**. Ordinary channel searches remain **YouTube channel**; uploader strings are never promoted to artist identities. This catalog classification is not a MusicBrainz identity assertion and does not enrich recommendation metadata. Artist selection performs a single cancellable music-song search by name, not an exhaustive or guaranteed artist discography. Search caching, local fallback, error/retry, pagination and limits are shared with other categories. The upstream service can change; failures are surfaced rather than silently substituting ordinary channels as artists.

Ranking scores query-term coverage (55), exact title (+25) or contained title (+12), the music catalog (+18), music/title/duration hints (up to 10 outside that catalog), library presence (+8), and explicitly requested versions (+18). Unrequested version hints have only a small penalty (−6). Version detection applies only to tracks, so artist names such as Live are not penalized as alternate song versions. Every result can show its ranking reasons. The legacy `Track.artist` field remains the source credit/uploader for compatibility; it is never automatically promoted to a canonical artist ID.

URL duplicates are removed. Default result folding is conservative about same-credit/title uploads and explicit version annotations; **All versions** reveals separate uploads. Different uploaders/performers remain separate unless reliable recording identity is available. Live performances, covers, remixes and other annotations are title-derived hints, not verified recording facts. Actual live streams are excluded by extractor stream type. Source pages are fetched only on request, with opaque in-memory continuation handles, repeated-page protection and a 500-result UI bound. First pages and suggestions have small expiring memory caches; saved library/history remains usable offline. Public collection screens disclose exactly how many tracks have loaded before Play/Save.

## Providers and data ownership

`MusicCatalogSource` extends the capabilities around the existing `MusicSource` without exposing extractor types. `NewPipeMusicSource` remains the only NewPipe adapter. `LocalHistoryProvider`, `MusicMetadataProvider`, `ArtistSimilarityProvider`, `RecommendationRanker` and `DiscoveryRepository` form the recommendation layer; Compose never extracts or resolves playback streams.

**NewPipeExtractor:** related items are public source hints, not verified musical similarity. They may include poor matches or be unavailable. Music search is an additional candidate source. Sonora does not copy or patch extractor code or introduce credentials, DRM handling, proxies or restriction overrides. [Pinned public search filters](https://github.com/TeamNewPipe/NewPipeExtractor/blob/v0.27.1/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/linkHandler/YoutubeSearchQueryHandlerFactory.java).

**MusicBrainz, optional and off by default:** query selected titles and source-credit hypotheses using the keyless API. A credit is accepted only with a high search score, exact normalized title/credit agreement, compatible known duration and unambiguous artist identity. Multiple matching recording IDs can yield an artist-only match; they do not yield a recording or album. Album grouping requires a unique ordinary Album release group, excluding compilation/live secondary types. Genre lookup uses the actual `genres` field, not arbitrary free-text tags; artist-level genre evidence is explicitly scoped as artist evidence. Missing lyrics language, scene, album or genre stays missing. These are metadata matches, not audio fingerprints. [API](https://musicbrainz.org/doc/MusicBrainz_API), [search](https://musicbrainz.org/doc/MusicBrainz_API/Search).

**ListenBrainz, optional with enrichment:** query the public similar-artists dataset by a matched MusicBrainz artist ID. No account token is sent. Its session-based public similarity results provide other-artist search candidates; a candidate only receives the artist-similarity score after its artist identity matches. Sparse artists may have no results. The selected published algorithm is `session_based_days_9000_session_300_contribution_5_threshold_15_limit_50_skip_30`; it is a replaceable provider choice, not a promise that Labs will retain the endpoint forever. [Dataset and available algorithms](https://labs.api.listenbrainz.org/similar-artists), [API pacing and user-agent requirements](https://listenbrainz.readthedocs.io/en/latest/users/api/index.html), [CC0 public listen data](https://listenbrainz.org/terms-of-service/).

**Last.fm:** evaluated but not used in this beta. It requires an API key and acceptance of its service terms. Sonora does not ship a key, fake a working integration or require a user account to compensate. A future opt-in provider can implement `ArtistSimilarityProvider` without changing ranking or the player. [Authentication](https://www.last.fm/api/authentication), [terms](https://www.last.fm/api/tos).

Each enriched value records its source URL, confidence, retrieval time, license and recording/artist scope. Core MusicBrainz credits are CC0; community genre data is CC BY-NC-SA 3.0 and remains separately attributed. Metadata caches are not shipped in the APK or library backups. [MusicBrainz data licenses](https://musicbrainz.org/doc/About/Data_License).

## Recommendation ranking

Scores are weighted means over available evidence. Missing attributes do not count as zero similarity. A weight is multiplied by its evidence confidence; scores are transparent heuristics, not probabilities.

| Dimension | Base weight | Notes |
| --- | ---: | --- |
| Same artist / verified related artist | 25 | Requires accepted identity; ListenBrainz links use lower confidence |
| Shared genre evidence | 25 | Used only with attributed genre evidence on both sides; weaker artist context remains labeled |
| Public source relatedness | 22 | Weak confidence (0.65); cannot be treated as a genre or scene label |
| Personal preferences | 18 | Favorites, completions and explicit “more”; skips/removals only gently reduce it |
| Novelty | 8 | Candidates absent from the bounded local ranking context |
| Musical evidence | 8 | Music-catalog category or low-confidence title/duration heuristics |
| Prior listening | 7 | Logarithmic play-count contribution; disabled with personalization |
| Same source credit/channel | 6 | Only 0.55 confidence; never an artist-identity assertion |
| Album / language / scene | 5 each | Only when supported; language/scene are currently normally unavailable |
| Release era | 4 | Recording release date, never upload date |

A public related video needs positive musical evidence before ranking: music-catalog or recording corroboration, a strong music hint, a prior local selection, or the seed’s source credit. Duration alone is insufficient, and obvious spoken/comedy candidates are rejected unless the music catalog identifies them as music. Multiple sources for the same URL are merged so a local row cannot erase catalog/related evidence. These filters reduce unrelated trending videos but cannot perfectly classify all content.

Recent session tracks, the seed, existing queue entries and explicitly hidden URLs are excluded. Recording IDs, when reliable, strengthen duplicate detection. Greedy selection applies repetition penalties and allows at most two items per artist/credit group in a batch; alternate groups are preferred between adjacent entries. When enough candidates exist, familiar music is limited to roughly half the batch so discovery has room. There is no compulsory quota if the source cannot supply credible alternatives.

Without enrichment, radio can still use public related tracks, music searches and local preferences. It cannot honestly promise a precise subgenre, language or regional scene. A Valencian-rock seed can lead to locally relevant source recommendations, but Sonora does not infer language or cultural identity from the user's location. User-written interests are search instructions, not catalog metadata. No large semantic model is bundled; text overlap supports search, not the entire music-recommendation strategy.

## Learning, background playback and recovery

The Media3 service owns `RadioCoordinator`. It appends up to five candidates when fewer than three remain, and starts discovery after actual playback begins to protect startup latency. It trims already played radio queue prefixes while retaining recent context. The existing bounded audio-preparation system can warm the next item. Radio generation tokens prevent stale requests from editing a replaced/manual queue.

Listening signals use elapsed time while the player actually plays, not seek position. A natural transition/end with at least 80% listened is a completion. A user skip after 1–30 seconds and under 20% of known duration is a soft skip. Pauses, failed loads and queuing alone are not dislikes. Listening-time increments are checkpointed about every ten seconds; incomplete last intervals can be lost on abrupt process death. A skip never hides an artist. Explicit Not interested is track-specific.

Room schema v5 adds search history, listening preferences and a bounded metadata/candidate cache. Existing v1→v4 migrations remain intact. Queue JSON v2 stores radio seed, origin reasons and recent session tracks; old array checkpoints still decode. Restoration stays paused and does not begin radio/network work until playback resumes. Existing library backup format v2 remains compatible; search history, preference signals and enrichment caches are intentionally device-local and excluded from backups.

Public metadata calls are spaced at least 1.1 seconds apart, use an identifying User-Agent, bounded payloads and expiration, and honor rate-limit/retry headers. Successful metadata is cached for days; negative matches for a day. Discovery source work has pacing, timeouts and exponential failure backoff. Per batch, enrichment is limited to the seed and two candidates, and music candidate searches to two queries. Source errors retain local/cached candidates. Three empty/failed radio fills stop automatic retries until the user explicitly retries or starts another radio; there is no request loop or mass catalog download.

## What remains conditional

Public-source availability, matching coverage and musical quality vary. Albums are omitted when uncertain, remote artist search depends on upstream music-catalog availability, with accepted local credits as an offline fallback, and results beyond UI bounds require a new query or existing import tools. Long uninterrupted sessions depend on enough distinct accessible candidates; the engine replenishes continuously but stops honestly when it runs out. Physical audio, battery, lock-screen and multi-hour network behavior need a phone test; see [validation](VALIDATION.md) and [manual acceptance](MANUAL_TESTS.md).
