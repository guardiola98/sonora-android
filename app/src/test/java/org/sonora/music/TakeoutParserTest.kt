package org.sonora.music

import org.junit.Assert.*
import org.junit.Test
import org.sonora.music.data.TakeoutParser
import org.sonora.music.domain.YouTubeLinks
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TakeoutParserTest {
    private val one = "abcdefghijk"
    private val two = "lmnopqrstuv"
    private fun parse(name: String, csv: String) = TakeoutParser().read(name, csv.byteInputStream())
    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> files.forEach { (name, text) -> z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() } }
        return out.toByteArray()
    }
    @Test fun optionalDescriptionHeaderIsNotMistakenForAVideoId() {
        val result = TakeoutParser().read("Songs.csv", "Video ID,Title,Description\nabcdefghijk,My song,Notes\n".byteInputStream())
        assertEquals("My song", result.collections.single().tracks.single().title)
    }

    @Test fun idsBecomeCanonicalTracksAndLikesAreSuggested() {
        val p = parse("Liked Music-videos.csv", "Video ID,Time Added\n$one,2026-01-01\n$two,2026-01-02")
        assertTrue(p.collections.single().favoritesHint)
        assertEquals(listOf("https://www.youtube.com/watch?v=$one", "https://www.youtube.com/watch?v=$two"), p.collections.single().tracks.map { it.url })
    }
    @Test fun quotedTitlesNewlinesBomAndEscapedQuotesArePreserved() {
        val csv = "\uFEFFSong URL,Song Title,Artist Names,Duration\r\nhttps://music.youtube.com/watch?v=$one,\"One, \"\"two\"\"\nthree\",Artist,3:07\r\n"
        val track = parse("music-library-songs.csv", csv).collections.single().tracks.single()
        assertEquals("One, \"two\"\nthree", track.title)
        assertEquals(187L, track.durationSeconds)
        assertFalse(parse("music-library-songs.csv", csv).collections.single().favoritesHint)
    }
    @Test fun duplicatesAndInvalidIdsAreReportedWithoutChangingOrder() {
        val p = parse("Roadtrip.csv", "Video Id,Title\n$two,Second\n$one,First\n$two,Duplicate\nhttps://evil.example/watch?v=$one,Bad\ninvalid,Missing\n").collections.single()
        assertEquals(1, p.duplicates); assertEquals(2, p.skipped)
        assertEquals(listOf("Second", "First"), p.tracks.map { it.title })
    }
    @Test fun olderTakeoutMetadataProvidesStablePlaylistIdentity() {
        val csv = "Playlist Id,Channel Id,Title\nPL12345678901234567890,channel,My list\n\nVideo Id,Time Added\n$one,2026-01-01\n"
        val p = parse("old-export.csv", csv).collections.single()
        assertEquals("My list", p.name); assertEquals("youtube:PL12345678901234567890", p.key)
        assertEquals(1, p.tracks.size)
    }
    @Test fun zipReadsOnlyCsvAndRejectsTraversalEntries() {
        val data = zip("Takeout/YouTube/playlists/First-videos.csv" to "Video ID,Title\n$one,First",
            "Takeout/YouTube/playlists/Liked Music-videos.csv" to "Video ID,Title\n$two,Second",
            "../../private.csv" to "Video ID,Title\n$one,Wrong", "index.html" to "not music")
        val p = TakeoutParser().read("export.zip", ByteArrayInputStream(data))
        assertEquals(listOf("First", "Liked Music"), p.collections.map { it.name })
        assertEquals(2, p.ignoredFiles)
    }
    @Test fun semicolonAndLocalizedHeadersWork() {
        val p = parse("Mis temas.csv", "ID de vídeo;Título;Artista\n$one;Canción;Autor\n").collections.single()
        assertEquals("Canción", p.tracks.single().title)
        assertEquals("Autor", p.tracks.single().artist)
    }
    @Test fun punctuationInPlaylistNamesDoesNotMergeDifferentLists() {
        val first = parse("A-B.csv", "Video ID\n$one").collections.single()
        val second = parse("AB.csv", "Video ID\n$one").collections.single()
        assertNotEquals(first.key, second.key)
    }
    @Test fun malformedAndUnsupportedFilesFailExplicitly() {
        for ((name, text) in listOf("bad.csv" to "Video ID,Title\n$one,\"unfinished", "index.html" to "<html/>", "unknown.csv" to "Name,Age\nSomeone,30")) {
            try { parse(name, text); fail("Expected a format error: $name") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun archiveSizeAndEntryCountAreBoundedIncludingIgnoredFiles() {
        val data = zip("ignored.txt" to "x".repeat(5000), "Music.csv" to "Video ID\n$one")
        try { TakeoutParser(maxBytes = 1000).read("bad.zip", data.inputStream()); fail("Expected byte limit") } catch (_: IllegalArgumentException) { }
        val entries = zip("first.csv" to "Video ID\n$one", "second.csv" to "Video ID\n$two")
        try { TakeoutParser(maxEntries = 1).read("bad.zip", entries.inputStream()); fail("Expected entry limit") } catch (_: IllegalArgumentException) { }
    }
    @Test fun aSongNamedUrlIsDataRatherThanAnotherHeader() {
        val p = parse("List.csv", "Video ID,Title\n$one,URL\n$two,Video ID").collections.single()
        assertEquals(listOf("URL", "Video ID"), p.tracks.map { it.title })
    }
    @Test fun onlyWhitelistedYouTubeUrlsAreAccepted() {
        assertEquals("https://www.youtube.com/watch?v=$one", YouTubeLinks.track("https://youtu.be/$one?si=test"))
        assertEquals("https://www.youtube.com/watch?v=$one", YouTubeLinks.track("https://music.youtube.com/watch?v=$one&list=ignored"))
        for (url in listOf("https://youtube.com.evil.example/watch?v=$one", "file:///watch?v=$one", "https://user@youtube.com/watch?v=$one", "https://youtube.com:8080/watch?v=$one")) assertNull(YouTubeLinks.track(url))
        assertNull(YouTubeLinks.playlist("https://music.youtube.com/playlist?list=LM"))
        assertNull(YouTubeLinks.playlist("https://youtube.com/playlist?list=RDabcdefghijk"))
        assertEquals("https://www.youtube.com/playlist?list=PL12345678901234567890", YouTubeLinks.playlist("https://music.youtube.com/playlist?list=PL12345678901234567890&si=test"))
    }
}
