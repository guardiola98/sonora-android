package org.sonora.music.data

import org.sonora.music.domain.*
import java.io.*
import java.text.Normalizer
import java.util.Locale
import java.util.zip.ZipInputStream

/** Reads in memory without extracting paths or executing archive content. No network access. */
class TakeoutParser(
    private val maxBytes: Int = 32 * 1024 * 1024,
    private val maxTracks: Int = 10_000,
    private val maxEntries: Int = 500
) {
    fun read(name: String, input: InputStream): ImportPreview {
        val buffered = BufferedInputStream(input)
        buffered.mark(4)
        val first = buffered.read(); val second = buffered.read()
        buffered.reset()
        val collections = mutableListOf<ImportCollection>()
        var ignored = 0
        var consumed = 0
        fun bytes(stream: InputStream): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Import cancelled")
                val count = stream.read(buffer)
                if (count == -1) break
                consumed += count
                require(consumed <= maxBytes) { "Export is too large. Export only playlists/music, or select individual CSV files (32 MiB limit)." }
                out.write(buffer, 0, count)
            }
            return out.toByteArray()
        }
        fun add(file: String, data: ByteArray) {
            val group = parseCsv(file, data.toString(Charsets.UTF_8))
            if (group == null) ignored++ else collections += group
            require(collections.sumOf { it.tracks.size } <= maxTracks) { "Import up to $maxTracks track entries at a time." }
        }
        if (first == 'P'.code && second == 'K'.code) {
            ZipInputStream(buffered).use { zip ->
                var count = 0
                var entry = zip.nextEntry
                while (entry != null) {
                    require(++count <= maxEntries) { "Archive has too many files. Export only playlists/music." }
                    // Count all decompressed bytes, including ignored entries, to bound ZIP bombs.
                    val data = bytes(zip)
                    val path = entry.name.replace('\\', '/')
                    if (!entry.isDirectory && path.endsWith(".csv", true) && !path.split('/').any { it == ".." }) add(path, data)
                    else if (!entry.isDirectory) ignored++
                    zip.closeEntry(); entry = zip.nextEntry
                }
            }
        } else {
            require(name.endsWith(".csv", true)) { "Select a Google Takeout ZIP or a UTF-8 CSV file." }
            add(name, bytes(buffered))
        }
        require(collections.any { it.tracks.isNotEmpty() }) { "No supported YouTube tracks found. Select playlists or music-library CSV files, not the export's HTML index." }
        return ImportPreview(collections, ignored)
    }

    internal fun parseCsv(path: String, text: String): ImportCollection? {
        val file = path.substringAfterLast('/').removeSuffix(".csv").removeSuffix(".CSV")
        var name = file.replace(Regex("-videos$", RegexOption.IGNORE_CASE), "").take(180)
        var key = "takeout:${name.trim()}"
        var header: List<String>? = null
        var metadata: List<String>? = null
        val tracks = linkedMapOf<String, Track>()
        var skipped = 0; var duplicates = 0
        val rows = csv(text)
        for (row in rows) {
            if (row.all { it.isBlank() }) continue
            val names = row.map(::normalized)
            val currentId = header?.let { columns -> idHeaders.firstNotNullOfOrNull { id ->
                row.getOrNull(columns.indexOf(id))?.let(YouTubeLinks::track)
            } }
            if (names.any { it in idHeaders } && currentId == null) { header = names; metadata = null; continue }
            if (names.contains("playlistid") && names.contains("title")) { metadata = names; header = null; continue }
            metadata?.let { columns ->
                fun cell(key: String) = row.getOrNull(columns.indexOf(key)).orEmpty().trim()
                name = cell("title").ifBlank { name }.take(180)
                cell("playlistid").takeIf { it.matches(Regex("[A-Za-z0-9_-]{10,150}")) }?.let { key = "youtube:$it" }
                metadata = null
            }
            val columns = header ?: continue
            fun cell(vararg keys: String): String = keys.firstNotNullOfOrNull { k -> row.getOrNull(columns.indexOf(k))?.trim()?.takeIf { it.isNotBlank() } }.orEmpty()
            val raw = cell(*idHeaders.toTypedArray())
            val url = YouTubeLinks.track(raw)
            if (url == null) { skipped++; continue }
            val title = cell("songtitle", "videotitle", "tracktitle", "title", "titulo", "titol", "nombre")
            val artist = cell("artistnames", "artist", "artists", "uploader", "channelname", "artista", "artistas")
            val duration = duration(cell("durationseconds", "duration", "duracion"))
            val track = Track(url, title.ifBlank { YouTubeLinks.fallbackTitle(url) }, artist.ifBlank { "Unknown artist" }, "", duration)
            if (tracks.putIfAbsent(url, track) != null) duplicates++
            require(tracks.size <= maxTracks) { "Import up to $maxTracks track entries at a time." }
        }
        if (header == null && tracks.isEmpty()) return null
        val normalizedName = normalized(name)
        val liked = listOf("likedmusic", "likedvideos", "likedsongs", "likes", "megustan", "megusta", "videosquegustan", "musicaquemegusta", "videosquemegustan").any { normalizedName == it }
        return ImportCollection(key, name.ifBlank { "Imported playlist" }, tracks.values.toList(), liked, skipped, duplicates)
    }
    private fun duration(value: String): Long {
        val pieces = value.split(':')
        return if (pieces.size in 2..3 && pieces.all { (it.toLongOrNull() ?: -1) >= 0 }) pieces.fold(0L) { total, piece -> (total * 60 + piece.toLong().coerceAtMost(604800)).coerceAtMost(604800) }
        else value.toLongOrNull()?.coerceIn(0, 604800) ?: 0
    }
    companion object {
        private val idHeaders = setOf("videoid", "songurl", "videourl", "trackurl", "youtubeid", "url", "idde video".replace(" ", ""), "urlcancion", "urlvideo")
        internal fun normalized(value: String): String = Normalizer.normalize(value.removePrefix("\uFEFF"), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "").lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

        /** RFC 4180 quoting, CRLF, embedded newlines, BOM and semicolon exports. */
        internal fun csv(raw: String): List<List<String>> {
            val text = raw.removePrefix("\uFEFF")
            val firstLine = text.substringBefore('\n')
            val delimiter = if (firstLine.count { it == ';' } > firstLine.count { it == ',' }) ';' else ','
            val rows = mutableListOf<List<String>>()
            var row = mutableListOf<String>(); val field = StringBuilder()
            var quoted = false; var afterQuote = false; var i = 0
            fun finishField() { row.add(field.toString()); field.setLength(0); afterQuote = false }
            while (i < text.length) {
                val c = text[i]
                if (quoted) {
                    if (c == '"') {
                        if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ }
                        else { quoted = false; afterQuote = true }
                    } else field.append(c)
                } else when {
                    c == '"' && field.isEmpty() && !afterQuote -> quoted = true
                    c == delimiter -> finishField()
                    c == '\n' || c == '\r' -> {
                        finishField(); rows.add(row); row = mutableListOf()
                        if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    }
                    afterQuote && c.isWhitespace() -> Unit
                    afterQuote -> throw IllegalArgumentException("Invalid CSV quoting. Export the original CSV again.")
                    else -> field.append(c)
                }
                require(field.length <= 16_384 && rows.size <= 25_000 && row.size <= 100) { "CSV exceeds supported row or field limits." }
                i++
            }
            require(!quoted) { "Incomplete CSV: a quoted field was not closed." }
            if (field.isNotEmpty() || row.isNotEmpty() || afterQuote) { finishField(); rows.add(row) }
            return rows
        }
    }
}
