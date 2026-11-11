# Beta 0.1 real-device acceptance checklist

Record device/model, Android version, commit, APK SHA-256, network type and results. Run on at least API 26 and a current API 35/36 phone; include a manufacturer with restrictive battery settings. These are manual acceptance steps, not claims that they have passed.

## Install and UI

- [ ] Install the APK and cold-launch it. No blank screens or crash; Home contains honest empty states.
- [ ] Visit all three bottom tabs. No unwanted audio starts when opening the app.
- [ ] Check portrait, landscape, a small phone and tablet; large font size and TalkBack. Buttons remain reachable and labeled.
- [ ] Open About and follow the source/license link; return to Sonora.

## Search and source errors

- [ ] Search a public song/artist. Confirm real title, uploader, duration and thumbnail.
- [ ] Quickly submit a second query while the first loads; only the newest results survive.
- [ ] Submit empty text and a query with no results. No spinner remains stuck.
- [ ] Tap a Home mood chip; it must run a real search, not show a fabricated playlist.
- [ ] Enable airplane mode before searching: see a useful error. Reconnect and tap Retry.
- [ ] Try unavailable/removed, age-gated, geo-restricted and verification-required content when encountered. Show an error without a crash or bypass prompt.

## Playback and queue

- [ ] Play a real public result and **listen to audible audio**. Confirm title/artwork match the audio.
- [ ] Open full player, pause/resume, seek near the start/middle/end; progress follows playback.
- [ ] Previous restarts the current track after the default threshold; pressing near the start goes back. Next goes to the following queue item.
- [ ] Allow a track to finish; next track starts automatically. At the end of the queue, Play restarts the final track.
- [ ] Add several tracks from search and library. Queue ordering is correct; move up, jump and remove (including the current track) work.
- [ ] Clear the queue; playback stops and the mini-player disappears. Add another track and resume.
- [ ] Navigate all screens and rotate while playing. The same service/player continues without restart or duplicated audio.
- [ ] Pause for a long interval (long enough for the source URL to expire), then resume/seek. The app requests a fresh stream or shows Retry after the bounded recovery attempt.
- [ ] Disconnect Wi-Fi during audio loading; no infinite retry loop. Reconnect and Retry at the retained position.

## Background, session and lifecycle

- [ ] Press Home with audio playing; listen for at least 5 minutes.
- [ ] Swipe Sonora from recents while playing; playback continues. Reopen and reconnect to the queue.
- [ ] Check notification title, artist and art. Play/pause/next/previous work from notification and lock screen.
- [ ] Lock the phone for at least 10 minutes; playback continues and progress stays synchronized.
- [ ] Start another audio app / receive a call: audio focus follows Android policy. Test wired and Bluetooth headset removal: audio pauses rather than switching loudly to speakers.
- [ ] Test Bluetooth media buttons for play/pause and skip.
- [ ] Stop playback and remove the app from recents; no orphan playing service remains.
- [ ] Force-stop / reboot: playback stops. Reopening restores queue, selection and last checkpoint in pause; audio starts only after Play.

## Persistence and data

- [ ] Favorite a search result, restart/force-stop/reopen, verify it is still in Library.
- [ ] Unfavorite from full player; every visible heart and library row updates.
- [ ] Start a track, verify it appears in history. A failed load must not create a history entry.
- [ ] Replay a track; its history entry moves to the top without duplicate rows.
- [ ] Clear history, confirm favorites remain. Cancel the clear dialog and verify nothing changes.
- [ ] Open Library offline; saved metadata remains available and starting audio reports a recoverable error.

Attach only redacted logs when reporting failures. Audible output, physical headset behavior, manufacturer power management and lock-screen usability still need a human with a phone even when automated emulator tests pass.

## Import and upgrade (beta02)

- [ ] Upgrade beta01 with an APK signed by the same key. Existing favorites/history survive; never uninstall to test migration. Different debug keys require uninstall and lose local data.
- [ ] Use the actual Android document picker with a real Takeout ZIP and multiple CSVs, from local Downloads and a document-provider app. Cancel the picker and return without changes.
- [ ] Check a real account's private playlists, exported library songs and exported likes against its CSV contents. Verify manual Playlist/Favorites choices and skipped-entry counts. Do not assume omitted likes can be recovered.
- [ ] Preview a public/unlisted playlist with more than 100 tracks. Compare first/last entries and order; cancel before saving and verify the library is unchanged.
- [ ] Import as Playlist, Favorites, both and neither. Reimport the same data and confirm no duplicate playlist entries or extra hearts; existing history stays untouched.
- [ ] Open an imported playlist, play all, play a row, enqueue, favorite/unfavorite and navigate back. Mini-player and background playback continue normally.
- [ ] Clear history and confirm imported playlists/favorites remain. Delete a playlist, cancel its confirmation, then delete; favorites/history remain and YouTube is unchanged.
- [ ] Import an ID-only CSV in airplane mode with title lookup disabled. Reconnect and reimport with lookup to fill missing titles; unavailable references remain visible and playback fails cleanly.
- [ ] Cancel during metadata lookup; no partial data is written. Rotate/navigate while importing; progress remains consistent. Force-stop before final save and verify no partial collections appear.
- [ ] Try a private/removed playlist link, a malformed URL, an invalid CSV, duplicate IDs, oversized ZIP and unsupported HTML/JSON. Errors are actionable; no library data is lost.
- [ ] Check large font, TalkBack and landscape on the preview, all destination chips, long playlist names and the import button at the bottom.

## Beta 0.2 library and signed updates

- [ ] Install two successive official APKs with the same pinned certificate using an in-place update. Favorites/history/playlists survive. Do not uninstall an incompatible old debug build without deciding how to preserve its data.
- [ ] Export to local Downloads and to a document provider; cancel a picker without changes. Copy the file off-device, restore to a fresh install and compare favorites, all history, empty playlists and ordering.
- [ ] Preview a backup without committing, cancel, then restore twice. Existing favorites stay on; history counts do not double; custom list names/order stay intact. Try truncated, unsupported-version and oversized backups.
- [ ] Create/rename lists, add songs from Search/Home/Library/full player, create-and-add from the picker, remove songs and delete a list. Confirm favorites/history are retained.
- [ ] Drag first/middle/last rows, scroll through a long list while dragging, cancel a drag, rotate and use TalkBack move up/down actions. Saved order must match the UI after reopening.
- [ ] Pause and seek, force-stop the process, reopen offline: queue/index/position return without autoplay. Clear the queue, kill/reopen and confirm it stays empty. Reboot and repeat.
- [ ] Repeat actual streaming, background notification and lock-screen checks on the signed release APK, not only debug.


## Podcasts (0.3 beta)

- Upgrade the signed 0.2 APK in place to 0.3; check favorites, playlist order and queue remain.
- Open Podcasts, search a known show, open it, subscribe and relaunch. Paste a public HTTPS RSS URL through Add RSS feed.
- Load a podcast, switch to airplane mode and reopen it: saved episodes remain visible and a useful network error appears. Restore networking and Refresh episodes.
- Play a real episode. Verify audible output, background/lock-screen notification controls, headset removal, Bluetooth and interruption by a phone call.
- Open the full player, try −15/+30 seconds, seek and 0.75/1/1.25/1.5/2× speed. Switch to a song and confirm speed resets to 1×.
- Pause after a minute, play something else, then Resume the episode. Force-stop and relaunch: queue and position return paused.
- Queue songs and episodes together; next, previous, remove and clear work. Mark played removes the currently selected episode before updating progress; mark unplayed resets its position.
- Favorite an episode and add it to a playlist. Export a backup, restore on another installation, check favorites/subscriptions/progress and load a subscribed feed.
- Unsubscribe and check other library data remains. Try malformed XML, an HTML URL, HTTP-only audio, a removed episode and a feed over 16 MiB; no crashes or false success.
- Check small/large screens, rotation, TalkBack labels and large font sizes. No audio files are downloaded for offline use.
