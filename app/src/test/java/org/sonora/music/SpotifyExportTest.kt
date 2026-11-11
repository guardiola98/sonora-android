package org.sonora.music

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.sonora.music.accountimport.*
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SpotifyExportTest {
    private fun song(title: String = "La flama") = """{"track":{"trackName":"$title","artistName":"Obrint Pas","trackUri":"spotify:track:1234567890123456789012"},"episode":null,"localTrack":null}"""
    private fun playlist(name: String = "My music", rows: String = song()) = """{"name":"$name","items":[$rows]}"""
    private fun export(vararg lists: String) = """{"playlists":[${lists.joinToString()}]}"""
    private fun read(json: String, parser: SpotifyExportParser = SpotifyExportParser()) = parser.read("Playlist1.json", json.byteInputStream())
    private fun zip(vararg files: Pair<String,String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip -> files.forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() } }
        return out.toByteArray()
    }
    private fun rejected(block: () -> Unit) { try { block(); fail("Expected explicit rejection") } catch (_: ImportAccessException) { } }
    @Test fun officialPlaylistShapePreservesOrderAndNeverInventsDuration() {
        val row = song("Second").replace("\"episode\":null", "\"episode\":null,\"msPlayed\":123456")
        val result = read(export(playlist(rows = song() + "," + row)))
        assertEquals(listOf("La flama", "Second"), result.single().songs.map { it.source.title })
        assertTrue(result.single().songs.all { it.source.durationSeconds == 0L && it.chosen == null })
        assertEquals("https://open.spotify.com/track/1234567890123456789012", result.single().songs.first().source.link)
        assertTrue(result.single().source.id.startsWith("export:"))
    }
    @Test fun savedTracksAreSeparateFromAlbumsEpisodesAndAccountInformation() {
        val json = """{"tracks":[{"artist":"Artist","album":"Album","track":"Song","uri":"spotify:track:1234567890123456789012"}],"albums":[{"name":"not a song"}],"episodes":[{}],"email":"private-fixture@example.invalid"}"""
        val result = read(json).single()
        assertEquals("likes", result.source.id); assertTrue(result.source.likes)
        assertEquals("Song", result.songs.single().source.title)
        assertFalse(result.toString().contains("private-fixture"))
    }
    @Test fun zipAcceptsOnlyPlaylistAndLibraryFilesIncludingSplitPlaylists() {
        val data = zip("Spotify Account Data/Playlist1.json" to export(playlist()),
            "Spotify Account Data/Playlist2.json" to export(playlist("Another", song("Another song"))),
            "Userdata.json" to "not even JSON", "StreamingHistory0.json" to "private history", "../Playlist3.json" to "bad JSON")
        val result = SpotifyExportParser().read("mydata.zip", data.inputStream())
        assertEquals(listOf("My music", "Another"), result.map { it.source.name })
    }
    @Test fun reimportIdentitySurvivesAddedSongsAndFileNamesButNotRenaming() {
        val original = read(export(playlist())).single()
        val changed = SpotifyExportParser().read("RenamedDownload.json", export(playlist(rows = song() + "," + song("New"))).byteInputStream()).single()
        assertEquals(original.source.id, changed.source.id)
        assertNotEquals(original.source.id, read(export(playlist("Other"))).single().source.id)
    }
    @Test fun conflictingSameNameListsFailInsteadOfMergingUnrelatedMusic() {
        rejected { read(export(playlist(), playlist(rows = song("Other")))) }
        assertEquals(1, read(export(playlist(), playlist())).size)
        val one = read(export(playlist()))
        val two = read(export(playlist(rows = song("Other"))))
        rejected { SpotifyExportParser.combine(one + two) }
    }
    @Test fun genuinePlaylistUrisShareTheOauthDestinationButUntrustedLinksDoNot() {
        val list = JSONObject(playlist()).put("uri", "spotify:playlist:1234567890123456789012").toString()
        assertEquals("1234567890123456789012", read(export(list)).single().source.id)
        val invalid = JSONObject(playlist()).put("uri", "https://evil.example/playlist/1234567890123456789012").toString()
        assertTrue(read(export(invalid)).single().source.id.startsWith("export:"))
    }
    @Test fun unsupportedRowsRemainVisibleAndNeverBecomeAutomaticMatches() {
        val local = """{"track":{"trackName":"Local","artistName":"Artist","trackUri":"spotify:local:Artist:Album:Local:180"}}"""
        val rows = read(export(playlist(rows = "null,{\"episode\":{\"episodeName\":\"Podcast\"}},$local,{}"))).single().songs
        assertEquals(4, rows.size); assertTrue(rows.all { it.chosen == null && it.source.unavailable != null })
        assertTrue(rows.all { it.source.link.isEmpty() })
    }
    @Test fun boundedZipAlsoCountsIgnoredFilesAndRejectsTooManySongs() {
        rejected { SpotifyExportParser(80).read("data.zip", zip("Userdata.json" to "x".repeat(81), "Playlist1.json" to export(playlist())).inputStream()) }
        rejected { read(export(playlist(rows = song() + "," + song())), SpotifyExportParser(maxSongs = 1)) }
    }
    @Test fun malformedDeepOrUnrelatedJsonNeverProducesAnEmptySuccess() {
        rejected { read("{unfinished") }
        rejected { read("{\"unrelated\":" + "[".repeat(40) + "0" + "]".repeat(40) + "}") }
        rejected { read("""{"msPlayed":5000,"endTime":"not a playlist"}""") }
        assertTrue(read(export(playlist(rows = ""))).single().songs.isEmpty())
    }
    @Test fun parserCanBeCancelledAndReusedWithoutRetainingPreviousData() {
        val parser = SpotifyExportParser()
        Thread.currentThread().interrupt()
        try { read(export(playlist()), parser); fail() } catch (_: InterruptedIOException) { } finally { Thread.interrupted() }
        assertEquals("Next", read(export(playlist("Next")), parser).single().source.name)
    }
}
