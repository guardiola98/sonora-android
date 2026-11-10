# Privacy

Sonora has no account system, analytics, advertisements or application backend.

Search terms and playback requests are sent directly to YouTube through NewPipeExtractor. Stream requests go to the media hosts returned by that source. Artwork requests go to the image hosts returned by the source. Those services receive standard network information such as your IP address. Their policies apply to those requests.

Favorites, canonical track URLs, titles, artwork URLs and listening history stay in a local Room database. Android app backup is disabled. Clear history in Library → History; remove favorites with the heart button. Uninstalling Sonora or clearing its app storage removes the local database. The queue and resolved playback URLs live in memory; Coil may cache thumbnails in app-private storage.

No storage, microphone, location or account permission is requested. Internet, network-state, foreground media service and wake-lock permissions support streaming and background playback. Android media-session notifications are exempt from the Android 13 notification runtime permission, so Sonora does not request unrelated notification access.

The beta does not collect diagnostic logs remotely. If reporting a bug, avoid including signed stream URLs, private information or an unredacted full device log.
