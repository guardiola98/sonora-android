# Faster playback starts (0.4 beta)

Sonora prepares likely audio before Play while preserving the same highest-bitrate progressive audio selection. It does not switch from a low-quality file to another file mid-song. Those independent progressive representations do not provide a shared adaptive timeline; replacing one could interrupt sound or seeking.

## What changed

- Resolve audio through NewPipeExtractor's public stream-extractor API without requesting unused video formats, subtitles or related-item metadata. The external extractor still owns access/playability checks; no extractor code is copied, patched or bypassed.
- Join concurrent requests for the same canonical track. A slow speculative request no longer holds a global network lock that blocks another song or podcast feed. Canceling preparation does not cancel an active playback request, and invalidated results cannot restore stale cache entries.
- While a screen is resumed, prepare up to two likely tracks from search, home history/favorites, the selected playlist/favorites or an opened podcast feed. While playing with at least five seconds already buffered, prepare the next queue item near the start and again near the end if needed. Changing screens cancels browsing work; selecting Play cancels unrelated work and can join extraction already in progress.
- Fetch at most **192 KiB** from the beginning of each candidate. Keep up to **four prefixes / 768 KiB** in a five-minute RAM cache, keyed by the exact resolved URL. This speculative path persists neither audio nor signed URLs; explicit [offline downloads](DOWNLOADS.md) are separate and downloaded items skip preparation. A single preparation worker limits speculative traffic; unsuccessful completed attempts have a short cooldown, while canceled work can be requested immediately by another screen.
- Feed cached bytes to Media3, then continue the same file at the exact byte offset. Preserve range requests and bypass the prefix for unsupported request headers, different signed URLs or seeks beyond it. Expiry recovery clears both resolution and the relevant prepared prefix. Interleaved live-radio metadata is not preloaded.
- Start once **250 ms** of audio is buffered, then maintain a **15–50 second** buffer target. Rebuffering still requires **two seconds**; the back buffer retains ten seconds for short backward seeks. A slow or unstable connection can still stall after a fast initial start.

**About → Faster playback starts** is on by default. Turn it off to cancel speculation and clear the shared prefix cache; already active player buffers remain valid. The preference survives restart. Preparation uses extra data, can contact sources before Play, never starts playback and never writes listening history. See [privacy details](../PRIVACY.md).

## Measured result

On the tested API 35 emulator, the final beta started the prepared track in a median **203 ms** (three samples, 191–260 ms); automatic preparation from the Search screen measured **238 ms**. With no prepared URL/audio, median startup was **2130 ms**, versus **2768 ms** on the published 0.3 beta. Preparation took 1.07–1.56 seconds ahead of Play. These are limited real-network measurements, not guaranteed phone timings. One repeat received an upstream HTTP 403 during continued playback; the same APK subsequently passed. [All samples and validation](VALIDATION.md).

## Measurement method

Measurements use the signed release APK on the same API 35 x86_64 emulator and host network, with the public result `https://www.youtube.com/watch?v=sMcWOaJFuw0` (search: Kevin MacLeod Carefree). The baseline is the published 0.3 APK. Three trials per mode measure MediaController's set-item/prepare/play command until `isPlaying` and at least 50 ms of timeline progression, polled every 10 ms. They measure decoded playback state, **not human-audible latency**.

- **Cold resolver:** invalidate Sonora's stream cache and clear prefixes immediately before each Play. This does not restart the process or clear every internal extractor/HTTP/DNS cache.
- **Resolved:** resolve the URL before the timer, without preparing audio bytes.
- **Prepared:** resolve and fetch the prefix before the timer. Preparation itself takes time; its benefit depends on browsing or queue playback providing that lead time.

The network is variable, this is one public track with three samples per condition, and runs are sequential rather than a controlled population study. An immediate first tap with nothing prepared still waits for extraction and the media host. Distant podcast resume positions and seek-heavy containers may not benefit from a beginning-only prefix. No universal instant-start guarantee is made.

The opt-in `StartupBenchmarkTest` records each measurement in `startup-benchmark.json` in the app's external files directory, plays beyond the prefix into network audio, and verifies a seek to 60 seconds. `AutomaticPreparationDeviceTest` separately checks that a visible search result is prepared without autoplay/history and then starts through the production UI controller. Run either with `-Pandroid.testInstrumentationRunnerArguments.startupBenchmark=true`; the device workflow exposes the same option. Results and release validation are recorded in [VALIDATION.md](VALIDATION.md).

## Reference APIs

- [Media3 1.8.0 DefaultLoadControl](https://github.com/androidx/media/blob/1.8.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/DefaultLoadControl.java)
- [Media3 progressive source and ICY request handling](https://github.com/androidx/media/blob/1.8.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/source/ProgressiveMediaPeriod.java)
- [NewPipeExtractor v0.27.1 stream information API](https://github.com/TeamNewPipe/NewPipeExtractor/blob/v0.27.1/extractor/src/main/java/org/schabi/newpipe/extractor/stream/StreamInfo.java)

Dependency versions are unchanged from 0.3; no new runtime library or account connection is introduced.
