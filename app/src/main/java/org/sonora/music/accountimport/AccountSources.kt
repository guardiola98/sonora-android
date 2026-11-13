package org.sonora.music.accountimport

import org.sonora.music.i18n.tr

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.sonora.music.domain.Track
import org.sonora.music.domain.YouTubeLinks
import java.time.Duration

private fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }
private fun JSONObject.text(key: String) = optString(key).takeUnless { it == "null" }.orEmpty()
private fun JSONObject.nextPage() = text("nextPageToken").takeIf { it.isNotEmpty() }
private fun api(base: String, path: String, parameters: Map<String, String>): HttpUrl = base.toHttpUrl().newBuilder()
    .addPathSegment(path).apply { parameters.forEach { (key, value) -> addQueryParameter(key, value) } }.build()

class SpotifyPlaylistSource(private val http: AccountJsonClient) : AccountPlaylistSource {
    private fun url(path: String, cursor: String?) = ("https://api.spotify.com/v1/$path").toHttpUrl().newBuilder()
        .addQueryParameter("limit", "50").addQueryParameter("offset", cursor ?: "0").build()
    private fun next(json: JSONObject, current: HttpUrl): String? {
        val raw = json.text("next").takeIf { it.isNotEmpty() } ?: return null
        val next = runCatching { raw.toHttpUrl() }.getOrNull() ?: throw ImportAccessException(tr("Invalid Spotify pagination."))
        val offset = next.queryParameter("offset")?.toIntOrNull()
        if (!next.isHttps || next.host != current.host || next.port != 443 || next.encodedPath != current.encodedPath ||
            offset == null || offset <= (current.queryParameter("offset")?.toIntOrNull() ?: 0) || offset > 25_000)
            throw ImportAccessException(tr("Invalid Spotify pagination. Nothing was imported."))
        return offset.toString()
    }
    override suspend fun playlists(access: TemporaryAccess, cursor: String?): AccountPage<RemotePlaylist> {
        val request = url("me/playlists", cursor); val json = http.get(request, access)
        val lists = json.optJSONArray("items")?.objects().orEmpty().mapNotNull { item ->
            val id = item.text("id").takeIf { it.matches(Regex("[A-Za-z0-9]{10,100}")) } ?: return@mapNotNull null
            RemotePlaylist(id, item.text("name").ifBlank { tr("Spotify playlist") },
                (item.optJSONObject("items") ?: item.optJSONObject("tracks"))?.optInt("total"), "https://open.spotify.com/playlist/$id")
        }
        return AccountPage(lists, next(json, request))
    }
    override suspend fun songs(access: TemporaryAccess, playlist: RemotePlaylist, cursor: String?): AccountPage<RemoteSong> {
        require(playlist.likes || playlist.id.matches(Regex("[A-Za-z0-9]{10,100}")))
        val request = url(if (playlist.likes) "me/tracks" else "playlists/${playlist.id}/items", cursor)
        val json = http.get(request, access)
        val rows = json.optJSONArray("items")?.objects().orEmpty().mapIndexed { index, wrapper ->
            val item = wrapper.optJSONObject("item") ?: wrapper.optJSONObject("track") ?: JSONObject()
            val id = item.text("id")
            val title = item.text("name").ifBlank { tr("Unavailable Spotify entry") }
            val artists = item.optJSONArray("artists")?.objects().orEmpty().map { it.text("name") }.filter { it.isNotBlank() }
            val unavailable = when {
                item.text("type") != "track" -> tr("Unavailable entry or podcast; no music match attempted.")
                wrapper.optBoolean("is_local") || item.optBoolean("is_local") -> tr("Local Spotify file; choose a YouTube equivalent manually.")
                title.isBlank() || artists.isEmpty() -> tr("Insufficient metadata for a safe match.")
                else -> null
            }
            RemoteSong(id.ifBlank { "unavailable:${cursor ?: "0"}:$index" }, title, artists, item.optLong("duration_ms") / 1000,
                if (id.matches(Regex("[A-Za-z0-9]{10,100}"))) "https://open.spotify.com/track/$id" else playlist.link, unavailable = unavailable)
        }
        return AccountPage(rows, next(json, request))
    }
}

class YouTubePlaylistSource(private val http: AccountJsonClient) : AccountPlaylistSource {
    private fun url(path: String, parameters: Map<String, String>, cursor: String? = null) = api("https://www.googleapis.com/youtube/v3/", path,
        parameters + mapOf("maxResults" to "50") + (cursor?.let { mapOf("pageToken" to it) } ?: emptyMap()))
    override suspend fun playlists(access: TemporaryAccess, cursor: String?): AccountPage<RemotePlaylist> {
        val json = http.get(url("playlists", mapOf("part" to "snippet,contentDetails", "mine" to "true", "fields" to "nextPageToken,items(id,snippet/title,contentDetails/itemCount)"), cursor), access)
        return AccountPage(json.optJSONArray("items")?.objects().orEmpty().mapNotNull { item ->
            val id = item.text("id").takeIf { it.matches(Regex("[A-Za-z0-9_-]{10,150}")) } ?: return@mapNotNull null
            RemotePlaylist(id, item.optJSONObject("snippet")?.text("title").orEmpty().ifBlank { tr("YouTube playlist") },
                item.optJSONObject("contentDetails")?.optInt("itemCount"), "https://www.youtube.com/playlist?list=$id")
        }, json.nextPage())
    }
    override suspend fun songs(access: TemporaryAccess, playlist: RemotePlaylist, cursor: String?): AccountPage<RemoteSong> {
        if (playlist.likes) {
            val json = http.get(url("videos", mapOf("part" to "snippet,contentDetails,status", "myRating" to "like", "fields" to VIDEO_FIELDS), cursor), access)
            return AccountPage(json.optJSONArray("items")?.objects().orEmpty().map { video(it, playlist.link) }, json.nextPage())
        }
        require(playlist.id.matches(Regex("[A-Za-z0-9_-]{10,150}")))
        val json = http.get(url("playlistItems", mapOf("part" to "snippet,contentDetails", "playlistId" to playlist.id,
            "fields" to "nextPageToken,items(snippet(position,title),contentDetails/videoId)"), cursor), access)
        val rows = json.optJSONArray("items")?.objects().orEmpty().sortedBy { it.optJSONObject("snippet")?.optInt("position") }
        val ids = rows.mapNotNull { it.optJSONObject("contentDetails")?.text("videoId")?.takeIf { id -> YouTubeLinks.track(id) != null } }.distinct()
        val details = if (ids.isEmpty()) emptyMap() else {
            val response = http.get(url("videos", mapOf("part" to "snippet,contentDetails,status", "id" to ids.joinToString(","), "fields" to VIDEO_FIELDS)), access)
            response.optJSONArray("items")?.objects().orEmpty().associateBy { it.text("id") }
        }
        return AccountPage(rows.mapIndexed { index, row ->
            val id = row.optJSONObject("contentDetails")?.text("videoId").orEmpty()
            details[id]?.let { video(it, playlist.link) } ?: RemoteSong(id.ifBlank { "unavailable:${cursor ?: "0"}:$index" },
                row.optJSONObject("snippet")?.text("title").orEmpty().ifBlank { tr("Unavailable video") }, emptyList(), 0, playlist.link,
                unavailable = tr("Deleted, private or inaccessible video; Sonora cannot play it using account authorization."))
        }, json.nextPage())
    }
    private val VIDEO_FIELDS = "nextPageToken,items(id,snippet(title,channelTitle,thumbnails/medium/url),contentDetails/duration,status/privacyStatus)"
    private fun video(item: JSONObject, fallback: String): RemoteSong {
        val id = item.text("id"); val link = YouTubeLinks.track(id)
        val snippet = item.optJSONObject("snippet") ?: JSONObject()
        val title = snippet.text("title"); val credit = snippet.text("channelTitle")
        val duration = runCatching { Duration.parse(item.optJSONObject("contentDetails")?.text("duration")).seconds }.getOrDefault(0)
        val unavailable = if (link == null || item.optJSONObject("status")?.text("privacyStatus") == "private") tr("Private or unavailable video; authorization is for import only.") else null
        // Channel is source credit, never a verified musical artist. OAuth tokens never reach playback.
        val track = if (unavailable == null && link != null) Track(link, title, credit,
            snippet.optJSONObject("thumbnails")?.optJSONObject("medium")?.text("url").orEmpty(), duration) else null
        return RemoteSong(id, title, listOf(credit), duration, link ?: fallback, track, unavailable)
    }
}
