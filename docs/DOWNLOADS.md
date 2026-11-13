# Offline downloads

Sonora 0.8 adds on-device audio downloads for the public music sources and RSS podcast episodes that Sonora can already play. Downloads are optional and separate from favourites, playlists and listening history.

## Download music and podcasts

- Open a song's **Options → Download for offline listening**, or choose **Download** in the full player.
- In a saved playlist, choose **Download playlist** and confirm. The saved order is unchanged. Favourites also have **Download all**.
- On a podcast episode, choose **Download**. A completed episode can be played even if its feed is unavailable later.
- Open **Library → Downloads** to see progress, storage usage, music/podcast filters, pause/resume, retry, cancel or remove audio. **Play downloaded** queues only complete local items and does not start radio.

**Wi-Fi only is enabled by default.** It means Android reports an unmetered network; a metered Wi-Fi hotspot waits too. Turn it off in Downloads to permit mobile data. This preference survives restart. Downloading a playlist is a one-time selection: later additions are not automatically downloaded.

Downloads run in a separate foreground service, with at most two transfers at once. Android 13+ can ask permission to display the progress notification. Refusing notification permission does not grant access or block the in-app controls. You can pause all transfers from the notification.

## Offline playback and storage

Completed items play through the same player, queue, seek controls and podcast speed/progress support. The audio data source reads only the saved bytes for a completed download: it does not refresh YouTube, request the podcast feed or silently fall back to streaming. Search, radio, artwork not already cached, and loading new podcast feeds still need internet. Open Downloads to choose local audio when offline.

Audio is stored in Sonora's private **files** directory, not Android's disposable cache. The app does not automatically evict completed downloads. Removing audio frees that item's storage without removing its favourite, playlist membership or podcast position. Uninstalling or clearing app data removes downloads. Library JSON backups contain metadata only, so download the audio again on a new device. This feature is in-app offline listening; it does not export MP3 files or sync downloads between devices.

The Media3 download index/cache are separate from `sonora.db`; the Room schema and library migrations are unchanged. Only canonical source references and ordinary track metadata are persisted. Expiring stream and redirect URLs stay in memory.

## Interruptions and unavailable sources

Network interruptions retry while the transfer runs, and queued work waits for the allowed network. Pause state and requests survive process restart; reopening Sonora reconnects the download service. A newly started or resumed transfer may start from zero to avoid combining bytes from different renditions after a URL refresh. HTTP 401/403/410 triggers fresh resolution on retry. Completed files are kept across restarts.

Incomplete or failed transfers are never shown as available offline. A failed item offers Retry; free storage or check the connection as appropriate. If a completed file is missing, Downloads offers another download, and playback reports a failure rather than fetching online without asking.

Android can stop transfers on force-stop, battery restrictions or foreground-service time limits. On Android 15+, the service handles the data-sync timeout by persisting a pause and stopping; resume from Downloads when Android permits it. Boot-time unattended downloading and arbitrary manufacturer power policies are not guaranteed.

Music downloads use the public progressive audio already provided by Sonora's music source. Spotify imports contain metadata; Sonora does not download Spotify's audio or enable Spotify/YouTube paid account entitlements. Private/paid feeds, DRM media and source access restrictions remain unsupported. Use downloads where you have permission; publisher availability and terms still apply.

Implementation references: [Media3 offline downloads](https://developer.android.com/media/media3/exoplayer/downloading-media), [Android foreground-service timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout).
