package org.sonora.music.domain

import org.sonora.music.i18n.tr

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest
import java.util.Base64

data class PodcastShow(val feedUrl: String, val title: String, val author: String = "", val artwork: String = "", val description: String = "")
data class PodcastEpisode(val reference: String, val title: String, val description: String, val artwork: String,
    val durationSeconds: Long, val publishedAt: Long, val audioUrl: String) {
    fun track(show: PodcastShow) = Track(reference, title, show.title, artwork.ifBlank { show.artwork }, durationSeconds)
}
data class PodcastFeed(val show: PodcastShow, val episodes: List<PodcastEpisode>)
interface PodcastSource {
    suspend fun search(query: String): List<PodcastShow>
    suspend fun feed(url: String, refresh: Boolean = false): PodcastFeed
    suspend fun resolve(reference: String): AudioStream
    suspend fun invalidate(reference: String)
}
/** A feed URL + hash of the publisher's GUID. Never persist the enclosure URL. */
object PodcastLinks {
    fun https(value: String): String? {
        if (value.length > 4096) return null
        val url = value.trim().toHttpUrlOrNull() ?: return null
        val host = url.host.lowercase()
        if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() || url.port != 443 ||
            !host.contains('.') || host.endsWith(".local") || host.endsWith(".localhost") ||
            host.all { it.isDigit() || it == '.' } || host.contains(':')) return null
        return url.newBuilder().fragment(null).build().toString()
    }
    fun reference(feed: String, guid: String): String {
        val canonical = requireNotNull(https(feed)) { tr("Use a public HTTPS RSS feed URL.") }
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(canonical.toByteArray(Charsets.UTF_8))
        val hash = MessageDigest.getInstance("SHA-256").digest(guid.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return "sonora-podcast://episode/$encoded/$hash"
    }
    fun parse(reference: String): Pair<String, String>? = runCatching {
        if (!reference.startsWith("sonora-podcast://episode/") || reference.length > 5600) return null
        val parts = reference.removePrefix("sonora-podcast://episode/").split('/')
        if (parts.size != 2 || !parts[1].matches(Regex("[0-9a-f]{64}"))) return null
        val feed = String(Base64.getUrlDecoder().decode(parts[0]), Charsets.UTF_8)
        if (https(feed) != feed || Base64.getUrlEncoder().withoutPadding().encodeToString(feed.toByteArray(Charsets.UTF_8)) != parts[0]) return null
        feed to parts[1]
    }.getOrNull()
    fun isEpisode(reference: String) = parse(reference) != null
}
fun isPersistentTrack(url: String) = YouTubeLinks.track(url) == url || PodcastLinks.isEpisode(url)
