# Privacy

Sonora has no account system, analytics, advertisements or application backend.

Search terms and playback requests are sent directly to YouTube through NewPipeExtractor. Stream requests go to the media hosts returned by that source. Artwork requests go to the image hosts returned by the source. Those services receive standard network information such as your IP address. Their policies apply to those requests.

Favorites, playlists and their track order, canonical track URLs, titles, artwork URLs and listening history stay in a local Room database. Android app backup is disabled. Clear history in Library → History; remove favorites with the heart button. Uninstalling Sonora or clearing its app storage removes the local database. The queue and its last saved position are stored locally; resolved playback URLs remain in memory; Coil may cache thumbnails in app-private storage.

Imported ZIP/CSV files are read locally through the Android document picker. Sonora does not upload the archive or request Google credentials, cookies or account access. Optional missing-title lookup sends canonical video references to YouTube through NewPipeExtractor; disable it in the preview to import offline. Public playlist-link imports also contact YouTube. Artwork may subsequently be fetched when displayed. Files are parsed in memory and are not retained by Sonora. Delete imported playlists from Library → Playlists; favorites are managed separately.

Library backups are created only when you choose Export library and a document destination. They contain metadata, playlist order, favorites and listening timestamps/counts in readable JSON, not audio or account credentials. A cloud document provider receives the file only if you select that provider. Restoring reads and validates the chosen file locally and merges it without contacting YouTube. Sonora does not encrypt exported backups.

No storage, microphone, location or account permission is requested. Internet, network-state, foreground media service and wake-lock permissions support streaming and background playback. Android media-session notifications are exempt from the Android 13 notification runtime permission, so Sonora does not request unrelated notification access.

The beta does not collect diagnostic logs remotely. If reporting a bug, avoid including signed stream URLs, private information or an unredacted full device log.
