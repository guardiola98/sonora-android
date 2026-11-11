package org.sonora.music.domain

import java.net.URI
import java.net.URLDecoder

data class ImportCollection(
    val key: String, val name: String, val tracks: List<Track>,
    val favoritesHint: Boolean = false, val skipped: Int = 0, val duplicates: Int = 0
)
data class ImportPreview(val collections: List<ImportCollection>, val ignoredFiles: Int = 0, val allowEmptyCollections: Boolean = false) {
    val trackCount get() = collections.flatMap { it.tracks }.distinctBy { it.url }.size
}
data class ImportChoice(val key: String, val playlist: Boolean, val favorites: Boolean)
data class ImportSelection(val collection: ImportCollection, val playlist: Boolean, val favorites: Boolean)
data class ImportSummary(
    val newTracks: Int = 0, val newFavorites: Int = 0, val newPlaylists: Int = 0,
    val addedEntries: Int = 0, val unresolved: Int = 0
)
interface LibraryImportSource {
    suspend fun playlist(url: String, progress: (Int) -> Unit): ImportCollection
    suspend fun track(url: String): Track
}

/** Only canonical YouTube references enter the library. Never fetch arbitrary import URLs. */
object YouTubeLinks {
    private val videoId = Regex("[A-Za-z0-9_-]{11}")
    private val playlistId = Regex("[A-Za-z0-9_-]{10,150}")
    private val hosts = setOf("youtube.com", "www.youtube.com", "music.youtube.com", "m.youtube.com", "youtu.be", "www.youtu.be")
    private fun uri(value: String): URI? = runCatching { URI(value.trim()) }.getOrNull()
        ?.takeIf { it.scheme?.lowercase() in setOf("http", "https") && it.host?.lowercase() in hosts && it.userInfo == null && it.port in setOf(-1, 80, 443) }
    private fun parameter(uri: URI, key: String): String? = uri.rawQuery?.split('&')?.firstNotNullOfOrNull {
        val parts = it.split('=', limit = 2)
        if (parts.size == 2 && parts[0] == key) runCatching { URLDecoder.decode(parts[1], "UTF-8") }.getOrNull() else null
    }
    fun track(value: String): String? {
        val raw = value.trim()
        if (videoId.matches(raw)) return "https://www.youtube.com/watch?v=$raw"
        val u = uri(raw) ?: return null
        val id = if (u.host.lowercase().endsWith("youtu.be")) u.path.trim('/').substringBefore('/')
            else if (u.path.startsWith("/shorts/") || u.path.startsWith("/embed/")) u.path.split('/').getOrNull(2)
            else if (u.path == "/watch") parameter(u, "v") else null
        return id?.takeIf(videoId::matches)?.let { "https://www.youtube.com/watch?v=$it" }
    }
    fun playlist(value: String): String? {
        val u = uri(value) ?: return null
        if (u.path !in setOf("/playlist", "/watch")) return null
        val id = parameter(u, "list") ?: return null
        // Personal likes and dynamic mixes need account state; files are the supported route.
        if (id == "LL" || id == "LM" || id.startsWith("RD") || !playlistId.matches(id)) return null
        return "https://www.youtube.com/playlist?list=$id"
    }
    fun fallbackTitle(url: String) = "YouTube track ${url.substringAfter("v=")}"
    fun needsDetails(track: Track) = track.title.isBlank() || track.title == fallbackTitle(track.url)
}
