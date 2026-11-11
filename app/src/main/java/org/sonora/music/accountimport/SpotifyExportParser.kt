package org.sonora.music.accountimport

import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.security.MessageDigest
import java.text.Normalizer
import java.util.zip.ZipInputStream

/** Only playlist/library JSON is decoded. Archives are never extracted, uploaded or retained. */
class SpotifyExportParser(private val maxBytes: Int = 32 * 1024 * 1024, private val maxSongs: Int = 10_000) {
    private var consumed = 0L
    private var songCount = 0
    private val lists = mutableListOf<ReviewedPlaylist>()

    fun read(name: String, input: InputStream): List<ReviewedPlaylist> {
        consumed = 0; songCount = 0; lists.clear()
        try {
            val stream = BufferedInputStream(input)
            stream.mark(4); val first = stream.read(); val second = stream.read(); stream.reset()
            if (first == 'P'.code && second == 'K'.code) {
                ZipInputStream(stream).use { zip ->
                    var entries = 0; var entry = zip.nextEntry
                    while (entry != null) {
                        check(++entries <= 500, "Archive has too many files. Select the playlist/library JSON files instead.")
                        val path = entry.name.replace('\\', '/')
                        val filename = path.substringAfterLast('/')
                        val accepted = !entry.isDirectory && !path.split('/').any { it == ".." } &&
                            (filename.matches(Regex("Playlist[0-9]*\\.json", RegexOption.IGNORE_CASE)) || filename.equals("YourLibrary.json", true))
                        val data = readBounded(zip, accepted)
                        if (accepted) parse(data)
                        zip.closeEntry(); entry = zip.nextEntry
                    }
                }
            } else {
                check(name.endsWith(".json", true), "Choose a Spotify account-data ZIP, Playlist JSON or YourLibrary.json.")
                parse(readBounded(stream, true))
            }
            check(lists.isNotEmpty(), "No Spotify playlists or saved tracks found. Choose Playlist*.json or YourLibrary.json from your account-data export.")
            return combine(lists)
        } catch (e: InterruptedIOException) { throw e }
        catch (e: ImportAccessException) { throw e }
        catch (_: Exception) { throw ImportAccessException("Could not read this Spotify export. Choose the original account-data ZIP or playlist/library JSON files.") }
        finally { lists.clear() }
    }

    private fun readBounded(stream: InputStream, keep: Boolean): ByteArray {
        val output = if (keep) ByteArrayOutputStream() else null
        val bytes = ByteArray(8192)
        while (true) {
            interrupted()
            val n = stream.read(bytes); if (n < 0) break
            consumed += n
            check(consumed <= maxBytes, "Export exceeds 32 MiB. Select only Playlist*.json and YourLibrary.json, without history or other account files.")
            if (output != null) {
                check(output.size() + n <= 8 * 1024 * 1024, "A playlist JSON exceeds 8 MiB. Split the export into smaller playlist files.")
                output.write(bytes, 0, n)
            }
        }
        return output?.toByteArray() ?: byteArrayOf()
    }

    private fun parse(bytes: ByteArray) {
        val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        // Bound nesting before JSONObject's recursive decoder, including unrecognized fields.
        var depth = 0; var quoted = false; var escaped = false
        text.forEachIndexed { index, c ->
            if (index % 8192 == 0) interrupted()
            if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
            else when (c) { '"' -> quoted = true; '[', '{' -> { depth++; check(depth <= 32, "The export has excessive JSON nesting.") }; ']', '}' -> depth-- }
        }
        val root = JSONObject(text)
        when {
            root.has("playlists") -> {
                val playlists = root.getJSONArray("playlists")
                check(playlists.length() + lists.size <= 2000, "Import up to 2,000 playlists at a time.")
                for (i in 0 until playlists.length()) {
                    interrupted()
                    val playlist = playlists.getJSONObject(i)
                    val name = field(playlist, "name")
                    check(name.isNotBlank(), "A playlist has no name. Export it again before importing.")
                    val spotifyId = spotifyId(field(playlist, "uri", "playlistUri"), "playlist")
                    // Export files usually omit playlist IDs. Use an explicit export namespace, never guess an API ID.
                    val id = spotifyId ?: "export:" + digest(Normalizer.normalize(name, Normalizer.Form.NFC))
                    val songs = rows(playlist.getJSONArray("items"), false)
                    lists += ReviewedPlaylist(RemotePlaylist(id, name, songs.size,
                        spotifyId?.let { "https://open.spotify.com/playlist/$it" }.orEmpty()), songs)
                }
            }
            root.has("tracks") -> {
                val songs = rows(root.getJSONArray("tracks"), true)
                lists += ReviewedPlaylist(RemotePlaylist("likes", "Spotify saved songs", songs.size, "https://open.spotify.com/collection/tracks", likes = true), songs)
            }
            else -> throw ImportAccessException("This JSON is not a Spotify playlist or saved-song library. History, profile, albums and podcast exports are not imported here.")
        }
    }

    private fun rows(array: JSONArray, library: Boolean): List<ReviewedSong> {
        check(songCount + array.length() <= maxSongs, "Import up to $maxSongs entries at a time.")
        songCount += array.length()
        return (0 until array.length()).map { index ->
            interrupted()
            val entry = array.optJSONObject(index)
            val track = if (library) entry else entry?.optJSONObject("track")
            val title = track?.let { field(it, if (library) "track" else "trackName", "name") }.orEmpty()
            val artist = track?.let { field(it, if (library) "artist" else "artistName") }.orEmpty()
            val rawUri = track?.let { field(it, "trackUri", "uri") }.orEmpty()
            val id = spotifyId(rawUri, "track")
            val unavailable = when {
                track == null -> "Pending review: this export entry is a podcast, local file or unavailable item."
                rawUri.startsWith("spotify:local:") -> "Pending review: local Spotify files cannot be transferred as audio."
                title.isBlank() || artist.isBlank() -> "Pending review: this entry lacks a song title or artist."
                else -> null
            }
            val song = RemoteSong(id ?: "export-track:" + digest("$title\u0000$artist"), title.ifBlank { "Unavailable export entry ${index + 1}" },
                listOfNotNull(artist.takeIf { it.isNotBlank() }), 0,
                id?.let { "https://open.spotify.com/track/$it" }.orEmpty(), unavailable = unavailable)
            // Spotify's export does not establish full recording duration; msPlayed is never duration.
            ReviewedSong(song, reason = unavailable ?: "Pending review: exported metadata has no recording duration. Choose a playable version.")
        }
    }
    private fun field(value: JSONObject, vararg names: String): String = names.firstNotNullOfOrNull {
        (value.opt(it) as? String)?.trim()?.takeIf(String::isNotEmpty)
    }.orEmpty().also { check(it.length <= 1024, "An export field is too long.") }
    private fun spotifyId(value: String, kind: String): String? = Regex("(?:spotify:$kind:|https://open\\.spotify\\.com/$kind/)([A-Za-z0-9]{22})").matchEntire(value)?.groupValues?.get(1)
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun interrupted() { if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Import cancelled") }
    private fun check(condition: Boolean, message: String) { if (!condition) throw ImportAccessException(message) }

    companion object {
        fun combine(parts: List<ReviewedPlaylist>): List<ReviewedPlaylist> {
            val groups = parts.groupBy { it.source.id }
            if (groups.size > 2000 || parts.sumOf { it.songs.size } > 10_000) throw ImportAccessException("Import up to 2,000 playlists and 10,000 entries at a time.")
            return groups.values.map { copies ->
                if (copies.distinct().size != 1) throw ImportAccessException("Two exported playlists have the same identity or name but different contents. Choose one export, or rename duplicate playlists in Spotify before exporting again.")
                copies.first()
            }
        }
    }
}
