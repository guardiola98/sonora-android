package org.sonora.music.accountimport

import org.sonora.music.i18n.tr

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Public embed metadata only: no cookies, credentials, private endpoints or Spotify audio. */
class PublicSpotifyPlaylist(private val client: OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false).followSslRedirects(false).callTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()) {
    suspend fun read(link: String): ReviewedPlaylist {
        var url = link.trim()
        if (isShortLink(url)) {
            repeat(4) {
                if (playlistId(url) == null) {
                    val result = fetch(url, redirects = true)
                    url = result.second ?: throw ImportAccessException(tr("This Spotify link did not lead to a public playlist."))
                    if (!isShortLink(url) && playlistId(url) == null) throw ImportAccessException(tr("This Spotify link did not lead to a public playlist."))
                }
            }
        }
        val id = playlistId(url) ?: throw ImportAccessException(tr("Paste a public Spotify playlist link, not an album, song or private collection."))
        val html = fetch("https://open.spotify.com/embed/playlist/$id").first
        return parse(html, id)
    }
    private suspend fun fetch(url: String, redirects: Boolean = false): Pair<String, String?> = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (compatible; Sonora/0.8)").header("Accept", "text/html").build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(ImportAccessException(tr("Could not load the public Spotify playlist. Check your connection and try again.")))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        if (redirects && it.code in 300..399) {
                            val next = it.header("Location")?.let { location -> request.url.resolve(location)?.toString() }
                            "" to next
                        } else {
                            if (!it.isSuccessful) throw ImportAccessException(tr("Spotify did not expose this playlist publicly. Check the link or use a Spotify ZIP / JSON export."))
                            val body = it.body ?: throw IOException()
                            if (body.contentLength() > MAX_BYTES) throw ImportAccessException(tr("This public playlist response is too large. Use a Spotify export instead."))
                            val output = java.io.ByteArrayOutputStream()
                            body.byteStream().use { input ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    if (call.isCanceled()) throw IOException()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (output.size() + count > MAX_BYTES) throw ImportAccessException(tr("This public playlist response is too large. Use a Spotify export instead."))
                                    output.write(buffer, 0, count)
                                }
                            }
                            val bytes = output.toByteArray()
                            String(bytes, Charsets.UTF_8) to null
                        }
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(if (e is ImportAccessException) e else ImportAccessException(tr("Could not read the public Spotify playlist. Try again or use a Spotify export.")))
                }
            }
        })
    }
    companion object {
        private const val MAX_BYTES = 2_000_000
        private val identifier = Regex("[A-Za-z0-9]{22}")
        fun playlistId(link: String): String? {
            val raw = link.trim()
            if (raw.startsWith("spotify:playlist:")) return raw.removePrefix("spotify:playlist:").takeIf(identifier::matches)
            val url = raw.toHttpUrlOrNull() ?: return null
            if (!url.isHttps || url.host != "open.spotify.com" || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
            val parts = url.pathSegments.filter(String::isNotEmpty).let { if (it.firstOrNull()?.matches(Regex("intl-[a-zA-Z-]+")) == true || it.firstOrNull() == "embed") it.drop(1) else it }
            return parts.takeIf { it.size == 2 && it[0] == "playlist" }?.get(1)?.takeIf(identifier::matches)
        }
        fun isShortLink(link: String): Boolean = link.trim().toHttpUrlOrNull()?.let {
            it.isHttps && it.host == "spotify.link" && it.port == 443 && it.username.isEmpty() && it.password.isEmpty() && it.pathSegments.filter(String::isNotEmpty).size == 1
        } == true
        fun accepts(link: String) = playlistId(link) != null || isShortLink(link)
        internal fun parse(html: String, expectedId: String): ReviewedPlaylist {
            try {
                require(html.length <= MAX_BYTES && identifier.matches(expectedId))
                val json = Regex("""(?is)<script\b[^>]*\bid\s*=\s*(['"])__NEXT_DATA__\1[^>]*>(.*?)</script>""")
                    .find(html)?.groupValues?.get(2) ?: throw IllegalArgumentException()
                val entity = JSONObject(json).getJSONObject("props").getJSONObject("pageProps")
                    .getJSONObject("state").getJSONObject("data").getJSONObject("entity")
                require(entity.getString("type") == "playlist" && entity.getString("id") == expectedId)
                val name = entity.optString("name").ifBlank { entity.optString("title") }.trim().take(180)
                require(name.isNotBlank())
                val tracks = entity.getJSONArray("trackList")
                require(tracks.length() <= 10_000)
                val songs = (0 until tracks.length()).map { index ->
                    val item = tracks.getJSONObject(index)
                    val id = item.optString("uri").removePrefix("spotify:track:")
                    val title = item.optString("title").trim().take(500)
                    val valid = item.optString("uri").startsWith("spotify:track:") && identifier.matches(id) && title.isNotBlank()
                    // Spotify separates credits with comma + NBSP, preserving commas within artist names.
                    val artists = item.optString("subtitle").split(",\u00a0").map { it.trim().take(200) }.filter(String::isNotEmpty)
                    RemoteSong(if (valid) id else "unavailable:$index", title.ifBlank { tr("Unavailable Spotify entry") }, artists,
                        (item.optLong("duration", 0) / 1000).coerceIn(0, 604800),
                        if (valid) "https://open.spotify.com/track/$id" else "https://open.spotify.com/playlist/$expectedId",
                        unavailable = if (valid) null else tr("This public playlist entry is unavailable or is not a music track."))
                }
                return ReviewedPlaylist(RemotePlaylist(expectedId, name, songs.size, "https://open.spotify.com/playlist/$expectedId"), songs.map(::ReviewedSong))
            } catch (e: Exception) {
                throw ImportAccessException(tr("Spotify did not expose readable playlist tracks. The playlist may be private or its public page may have changed. Use a Spotify ZIP / JSON export instead."))
            }
        }
    }
}
