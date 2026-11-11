# Temporary account import

Status: Spotify is implemented behind public build-time configuration. The YouTube Data API adapter/broker is experimental and additionally gated on use-case approval. No provider application credentials, live account grants, hosted callback or deployed broker were available during development. **A default APK cannot connect an account until its distributor completes the setup below.** The import screen explains this and retains working Takeout and public-link imports. Since 0.7.1, [official Spotify ZIP/JSON exports](SPOTIFY-EXPORT.md) also provide a working, credential-free transfer route; it is not an OAuth connection.

## What the user can do

Library → Import music offers Spotify and YouTube / YouTube Music. A consent explanation precedes the system browser. Both flows use authorization code + S256 PKCE and random, per-attempt state. Sonora never asks for a password.

After authorization, select multiple accessible playlists, read the selection, and review every entry. The provider connection is discarded **before** matching/review. Spotify title/artist queries go to Sonora's existing YouTube music source; this disclosure is shown before consent. Confirm the chosen versions, then use the existing local import preview to select Playlist/Favorites destinations and save atomically.

An uncertain entry stays pending. Review candidates, listen in the browser, select a version, retry search, or look up a YouTube video link. Nothing ambiguous is automatically substituted. To proceed with unresolved entries, explicitly acknowledge leaving them out. Pending entries are not persisted: reconnect to revisit them after leaving. This limitation avoids storing a second account-derived library or inventing unplayable tracks.

New playlists preserve source names and first-occurrence track order. The existing library model permits one instance of a track per playlist. Reimport merges new tracks, preserving local edits, existing order, favorites and history; it is not a destructive mirror. YouTube account/link imports share `youtube:<playlist ID>` identity. Spotify uses `spotify:<playlist ID>`. Separate likes collections default to local favorites. Source playlist IDs are destination identities, not account profiles.

## Provider availability (official documentation checked 2026-11-11)

### Spotify

Use a registered public client and [authorization code with PKCE](https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow). No Spotify client secret is needed. Playlist scopes are `playlist-read-private` and `playlist-read-collaborative`; `user-library-read` is added only when the user explicitly includes liked songs.

[Development mode](https://developer.spotify.com/documentation/web-api/concepts/quota-modes) requires a Premium app owner and permits five allowlisted users. An unlisted user can reach consent yet receive HTTP 403 from the API. General public distribution needs approved extended access; Spotify currently limits new applications to qualifying organizations, including a 250,000 monthly-active-user threshold. Shipping a public APK does not grant public Spotify API access. Do not rotate client IDs to evade limits.

The [current playlist-items endpoint](https://developer.spotify.com/documentation/web-api/reference/get-playlists-items) is `/v1/playlists/{id}/items`, limited to owned/collaborative playlists. Followed/editorial/automatic lists may appear as metadata without readable contents. The parser accepts both `item` and older `track` envelopes, following the [2026 changes](https://developer.spotify.com/documentation/web-api/references/changes/february-2026). Liked songs use `/v1/me/tracks`, separately from playlists. Local files, unavailable items and episodes are flagged for review rather than treated as music matches.

This is user-directed playlist metadata transfer, not Spotify playback, catalog mirroring or recommendation training. The [Developer Policy](https://developer.spotify.com/policy) permits personal/playlist metadata transfer as an exception to its transfer restriction; distributor approval is still not guaranteed. Preview metadata links to Spotify and carries the official Spotify logo. No Spotify artwork, audio preview, profile, email or account identifier is imported.

### YouTube / YouTube Music

The [Data API](https://developers.google.com/youtube/data/guides/implementation/playlists) exposes playlists owned by the authorized channel using `playlists.list(mine=true)`, not every playlist saved/followed in Music. Sonora follows page tokens, reads items in position order and batches video details. A private playlist can contain public playable videos; authorization does **not** make private/restricted videos playable through NewPipe.

There is no separate Music-library endpoint used here. Do not promise albums, uploads, radio/mixes or complete Music likes. The optional likes collection uses [`videos.list(myRating=like)`](https://developers.google.com/youtube/data/reference/rest/v3/videos/list), which can include non-music YouTube videos. API order is retained; no chronological ordering guarantee is invented. Use Takeout when API coverage is insufficient.

**Distribution blocker beyond credentials:** YouTube's [API developer policies](https://developers.google.com/youtube/terms/developer-policies) require refresh/deletion of most stored authorized data within 30 days and revalidation/deletion after revocation. They also restrict audio separation and background players. Sonora's permanent, disconnected library copy and existing player cannot simply be declared compliant by registering OAuth. The Google adapter remains disabled unless the operator has obtained applicable use-case permission or otherwise established a compliant deployment. The build flag below is not permission from Google. The working default alternative is user-provided Takeout files/public links; no automatic 30-day deletion of the user's existing library has been introduced.

Google's [Data Portability API](https://developers.google.com/data-portability/user-guide/introduction) is a more appropriate future avenue for a one-time permanent transfer. It has dedicated [YouTube playlist/music scopes](https://developers.google.com/data-portability/user-guide/scopes), verification and availability requirements, and asynchronous exports that can take minutes or hours. It does not provide the same immediate playlist-selection API. It is not implemented or misrepresented as working in this change; supporting long-running exports within the no-refresh/process-death constraints needs a separate, validated flow.

The single scope is `https://www.googleapis.com/auth/youtube.readonly`. A Cloud project, enabled Data API, consent configuration and OAuth registration are required. External testing requires registered test users. Production access to sensitive scopes requires [Google verification](https://developers.google.com/identity/protocols/oauth2/production-readiness/policy-compliance), unless an applicable exception exists. Consent branding/privacy-policy/domain requirements and the actual project's user cap must be checked in Cloud Console before distribution. The API has [project quotas](https://developers.google.com/youtube/data/guides/quota_and_compliance_audits); the read endpoints used here generally cost one unit per request/page. Quota failure ends the session without automatic retry loops.

Google has [removed mobile loopback support](https://developers.google.com/identity/protocols/oauth2/resources/loopback-migration) and does not support custom-scheme redirects for Android clients. We do not disguise Android as a desktop client. The recommended native AuthorizationClient was evaluated, but its SDK declares the Android SDK license, not an open-source GPL-compatible dependency license. **It is not included in Sonora.**

To retain the requested browser flow, the Google adapter uses a [web OAuth client](https://developers.google.com/identity/protocols/oauth2/web-server), an HTTPS Android App Link and an optional secret-holding broker. Google's [authorization-server discovery](https://accounts.google.com/.well-known/openid-configuration) advertises S256. Sonora requests `access_type=online`, not offline access. Google may remember consent/browser login, but a new Sonora import always starts a new authorization attempt.

## Configure Spotify

1. Create the Sonora app in Spotify Developer Dashboard. Add permitted testers and register exactly `http://127.0.0.1/sonora/callback` **without a port**. Spotify's [redirect rules](https://developer.spotify.com/documentation/web-api/concepts/redirect_uri) explicitly permit dynamically assigned loopback ports for this registration.
2. Supply the **public** client ID through `SONORA_SPOTIFY_CLIENT_ID` or Gradle `-Psonora.spotifyClientId=...`. No secret, account credential or token belongs in Gradle properties.
3. Build and install. The listener binds only `127.0.0.1`, on a random port, during the three-minute authorization window. After consent the browser says to return to Sonora. Cancel in Sonora if consent was dismissed. No external backend is used for Spotify.

## Configure Google and the optional broker

The broker is source code in `oauth-broker/`, **not deployed by this change or by CI**. It uses standard Request/Response/fetch APIs and includes a Cloudflare Worker example; use an HTTPS host you control. It can run within free-tier allowances, but provider quotas and domain/hosting availability are operational requirements. Normal Sonora playback, Spotify, Takeout and public-link import do not depend on it.

1. In Google Cloud enable YouTube Data API v3, configure consent/privacy policy and testing users, then create a **Web application** OAuth client. Register exactly `https://YOUR_HOST/oauth/callback` as its redirect URI. Request only YouTube read access. Do not use desktop-client credentials.
2. Copy `oauth-broker/wrangler.toml.example` to ignored `wrangler.toml`. Configure `PUBLIC_HOST`, the public `GOOGLE_CLIENT_ID`, and the colon-separated public signing certificate fingerprint in `ANDROID_CERTIFICATES_SHA256`. Serve the example custom domain over HTTPS.
3. Set `GOOGLE_CLIENT_SECRET` only in the hosting platform's secret store, for example `wrangler secret put GOOGLE_CLIENT_SECRET` from the broker directory. Never put it in the APK, Gradle, `.env` committed to Git, CI output or this repository. Consult [Workers secret configuration](https://developers.cloudflare.com/workers/configuration/secrets/). Deployment is a separate operator action.
4. Disable request/response payload logging, query-string logging, Worker observability/tail sessions, proxy caching and third-party analytics on these routes. The broker itself has no logging, cookies, database, KV, queue or refresh endpoint. It handles the one-time code/verifier and access token transiently; it returns only the required access-token fields and discards any refresh/ID token.
5. **Only after resolving the policy blocker above**, enable `SONORA_GOOGLE_IMPORT_APPROVED=true` or `-Psonora.googleImportApproved=true`. Configuration alone does not prove approval. Configure public Android build values `SONORA_GOOGLE_CLIENT_ID` and `SONORA_GOOGLE_OAUTH_HOST`, or `-Psonora.googleClientId=... -Psonora.googleOAuthHost=...`. The host contains no scheme/path. Both values are required together. The manifest derives the verified HTTPS callback from this host.
6. Verify `https://YOUR_HOST/.well-known/assetlinks.json` identifies `org.sonora.music` and the **existing** Sonora release certificate. Obtain the public SHA-256 with `apksigner verify --print-certs app-release.apk`; do not replace the signing key. Debug builds have a different fingerprint and should use a separate test OAuth project/host.
7. Verify App Links on the device (`adb shell pm get-app-links org.sonora.music`). If Android opens the callback in the browser, enable Sonora's supported links and start again; the fallback page does not echo or redeem the code.

The broker accepts only bounded POST form bodies with code + verifier, uses a fixed upstream Google token endpoint/client/redirect, disallows redirects and browser CORS requests, and sanitizes errors. Put deployment-specific rate protection in front of the exchange route if needed; never log token-bearing bodies. Public clients cannot hide a shared API password in an APK. The broker does not fetch playlists or receive the imported library.

## Privacy and lifecycle

`TemporaryAccess` is deliberately absent from StateFlow, Room, preferences, SavedStateHandle, backups and logs. The import-specific HTTP client has no disk cache, cookie jar, logging interceptor, authenticator, redirects or automatic retries. Access uses Authorization headers, never URL query tokens. HTTP calls are cancelled with the import job. Every new import gets fresh state and verifier.

Cancel/back/navigation, leaving an active account selection, definitive failures, three-minute authorization timeout, ten-minute selection timeout and ViewModel destruction discard authorization. Process death restores no credential or OAuth attempt; late App Links are ignored. Rotation may retain the same in-memory ViewModel. Listening to candidate links after disconnection can retain the review in memory; leaving the import screen discards it. An outstanding browser grant can still complete after cancellation, but its callback cannot reconnect Sonora.

Selected playlist names, destination IDs and chosen playable track metadata become local library data only after confirmation, and then follow the existing backup rules. Unselected playlists and pending source metadata remain transient. No account profile is requested. JVM/Android memory is managed: references are released and the owned token character array is overwritten, but secure erasure of every internal temporary string is not claimed.

Local disconnect is not browser logout or guaranteed provider revocation. Users can remove consent separately in their Spotify/Google account settings. No refresh mechanism is present. Playback and recommendations never receive these credentials.

## Matching and bounded work

A single automatic Spotify match requires normalized exact title agreement, exact primary-artist/source-credit agreement, compatible version markers and positive durations within 1.5%, bounded to 2–4 seconds. Source credit is a matching hint, not proof that a channel is the artist. All returned candidate URLs are checked for ambiguity before limiting the displayed suggestions. Missing duration, several plausible recordings, covers/remixes, unrelated uploaders and failures remain pending.

At most 200 unique automatic searches occur per selection, sequentially with a 200 ms pause and 20-second timeout. Three consecutive empty/failed searches pause automatic matching; remaining entries can be searched individually. Search results are cached only within the flow. Playlist reads are capped at 2,000 lists and 10,000 selected entries, with repeated-page detection; oversize imports fail before saving instead of silently truncating. No Room migration, backup format or signing change is needed.

See [test evidence and real-account checklist](OAUTH-VALIDATION.md).
