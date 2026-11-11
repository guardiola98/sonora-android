package org.sonora.music.data

data class BackupPlaylist(val key: String, val name: String, val tracks: List<String>)
data class LibraryBackup(val createdAt: Long, val tracks: List<LibraryTrack>, val playlists: List<BackupPlaylist>) {
    val favoriteCount get() = tracks.count { it.favorite }
    val historyCount get() = tracks.count { it.lastPlayedAt != null }
}
data class RestoreSummary(val tracks: Int, val favorites: Int, val playlists: Int, val entries: Int)
