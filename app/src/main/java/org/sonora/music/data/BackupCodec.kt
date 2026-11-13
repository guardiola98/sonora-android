package org.sonora.music.data

import org.sonora.music.i18n.tr

import org.json.JSONArray
import org.json.JSONObject
import org.sonora.music.domain.*
import java.io.InputStream
import java.net.URI

/** Versioned metadata only: never audio, cookies, credentials or signed stream URLs. */
object BackupCodec {
    const val MAX_BYTES = 32 * 1024 * 1024
    private const val MAX_TRACKS = 50_000
    fun read(input: InputStream): LibraryBackup {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException(tr("Backup cancelled"))
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_BYTES) { tr("Backup exceeds 32 MiB.") }
            output.write(buffer, 0, count)
        }
        val bytes = output.toByteArray()
        require(bytes.size <= MAX_BYTES) { tr("Backup exceeds 32 MiB.") }
        return decode(bytes.toString(Charsets.UTF_8))
    }
    fun encode(backup: LibraryBackup): String {
        require(backup.tracks.size <= MAX_TRACKS && backup.playlists.size <= 2_000 && backup.playlists.sumOf { it.tracks.size.toLong() } <= 100_000) { tr("Library exceeds supported backup limits.") }
        return JSONObject().apply {
        put("format", "sonora-library"); put("version", 2); put("createdAt", backup.createdAt)
        require(backup.podcasts.size <= 2_000 && backup.progress.size <= 50_000) { tr("Too many podcast records.") }
        put("podcasts", JSONArray().apply { backup.podcasts.forEach { show -> put(JSONObject().apply {
            put("feedUrl", show.feedUrl); put("title", show.title); put("author", show.author); put("artwork", show.artwork); put("description", show.description)
        }) } })
        put("podcastProgress", JSONArray().apply { backup.progress.forEach { p -> put(JSONObject().apply {
            put("reference", p.reference); put("positionMs", p.positionMs); put("durationMs", p.durationMs); put("completed", p.completed); put("updatedAt", p.updatedAt)
        }) } })
        put("tracks", JSONArray().apply { backup.tracks.forEach { record -> put(trackJson(record.track()).apply {
            put("favorite", record.favorite); put("lastPlayedAt", record.lastPlayedAt ?: JSONObject.NULL); put("playCount", record.playCount)
        }) } })
        put("playlists", JSONArray().apply { backup.playlists.forEach { list -> put(JSONObject().apply {
            put("key", list.key); put("name", list.name); put("tracks", JSONArray(list.tracks))
        }) } })
    }.toString().also { require(it.toByteArray().size <= MAX_BYTES) { tr("Library exceeds the 32 MiB backup limit.") } }
    }
    fun decode(text: String): LibraryBackup {
        require(text.length <= MAX_BYTES) { tr("Backup exceeds supported size.") }
        var depth = 0; var quoted = false; var escaped = false
        for (character in text) {
            if (quoted) {
                if (escaped) escaped = false
                else if (character == '\\') escaped = true
                else if (character == '"') quoted = false
            } else when (character) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 16) { tr("Backup is nested too deeply.") } }
                '}', ']' -> { depth--; require(depth >= 0) { tr("Invalid backup structure.") } }
            }
        }
        require(depth == 0 && !quoted) { tr("Incomplete backup.") }
        val json = JSONObject(text)
        require(json.getString("format") == "sonora-library" && json.getInt("version") in 1..2) { tr("Unsupported Sonora backup version.") }
        val data = json.getJSONArray("tracks")
        require(data.length() <= MAX_TRACKS) { tr("Backup exceeds 50,000 tracks.") }
        val tracks = (0 until data.length()).map { i ->
            val row = data.getJSONObject(i); val t = parseTrack(row)
            val played = if (row.isNull("lastPlayedAt")) null else row.getLong("lastPlayedAt").also { require(it >= 0) }
            val count = row.getInt("playCount").also { require(it >= 0) }
            LibraryTrack(t.url, t.title, t.artist, t.artwork, t.durationSeconds, row.getBoolean("favorite"), played, count)
        }
        require(tracks.map { it.url }.toSet().size == tracks.size) { tr("Duplicate track records in backup.") }
        val urls = tracks.map { it.url }.toSet()
        val lists = json.getJSONArray("playlists")
        require(lists.length() <= 2_000) { tr("Backup exceeds 2,000 playlists.") }
        var entries = 0
        val playlists = (0 until lists.length()).map { i ->
            val list = lists.getJSONObject(i)
            val key = list.getString("key").also { require(it.length in 1..300) }
            val name = list.getString("name").also { require(it.isNotBlank() && it.length <= 180) }
            val ids = list.getJSONArray("tracks"); entries += ids.length()
            require(entries <= 100_000) { tr("Backup exceeds 100,000 playlist entries.") }
            val references = (0 until ids.length()).map { ids.getString(it).also { url -> require(url in urls) { tr("Playlist references a missing track.") } } }
            require(references.distinct().size == references.size) { tr("Duplicate entries in a backup playlist.") }
            BackupPlaylist(key, name, references)
        }
        require(playlists.map { it.key }.distinct().size == playlists.size) { tr("Duplicate playlists in backup.") }
        val shows = if (json.getInt("version") >= 2) json.getJSONArray("podcasts") else JSONArray()
        val positions = if (json.getInt("version") >= 2) json.getJSONArray("podcastProgress") else JSONArray()
        require(shows.length() <= 2_000 && positions.length() <= 50_000) { tr("Too many podcast records.") }
        val podcasts = (0 until shows.length()).map { index ->
            val row = shows.getJSONObject(index)
            val url = row.getString("feedUrl").also { require(PodcastLinks.https(it) == it) }
            val title = row.getString("title").also { require(it.length <= 2000) }
            val author = row.getString("author").also { require(it.length <= 2000) }
            val description = row.getString("description").also { require(it.length <= 10000) }
            SavedPodcast(url, title, author, PodcastLinks.https(row.optString("artwork")).orEmpty(), description, true)
        }
        val progress = (0 until positions.length()).map { index ->
            val row = positions.getJSONObject(index)
            val ref = row.getString("reference").also { require(PodcastLinks.isEpisode(it)) }
            val position = row.getLong("positionMs").also { require(it in 0..604800000) }
            val duration = row.getLong("durationMs").also { require(it in 0..604800000) }
            PodcastProgress(ref, position, duration, row.getBoolean("completed"), row.getLong("updatedAt").also { require(it >= 0) })
        }
        require(podcasts.distinctBy { it.feedUrl }.size == podcasts.size && progress.distinctBy { it.reference }.size == progress.size) { tr("Duplicate podcast records.") }
        return LibraryBackup(json.getLong("createdAt"), tracks, playlists, podcasts, progress)
    }
    internal fun trackJson(track: Track) = JSONObject().apply {
        put("url", track.url); put("title", track.title); put("artist", track.artist)
        put("artwork", track.artwork); put("durationSeconds", track.durationSeconds)
    }
    internal fun parseTrack(row: JSONObject): Track {
        val url = row.getString("url")
        require(isPersistentTrack(url)) { tr("Backup contains an unsupported track reference.") }
        val title = row.getString("title").also { require(it.length <= 2_000) }
        val artist = row.getString("artist").also { require(it.length <= 2_000) }
        val duration = row.getLong("durationSeconds").also { require(it in 0..604_800) }
        val artwork = row.optString("artwork").takeIf { value ->
            runCatching { val uri = URI(value); uri.scheme == "https" && uri.userInfo == null && uri.port == -1 &&
                ((PodcastLinks.isEpisode(url) && PodcastLinks.https(value) != null) || uri.host == "i.ytimg.com" || uri.host?.endsWith(".ytimg.com") == true || uri.host?.endsWith(".googleusercontent.com") == true || uri.host?.endsWith(".ggpht.com") == true)
            }.getOrDefault(false)
        }.orEmpty()
        return Track(url, title, artist, artwork, duration)
    }
}
