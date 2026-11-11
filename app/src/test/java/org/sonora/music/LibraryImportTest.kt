package org.sonora.music

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.sonora.music.data.*
import org.sonora.music.domain.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LibraryImportTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: LibraryDatabase
    private val a = Track("https://www.youtube.com/watch?v=abcdefghijk", "First", "Artist", "", 120)
    private val b = a.copy(url = "https://www.youtube.com/watch?v=lmnopqrstuv", title = "Second")
    private val c = a.copy(url = "https://www.youtube.com/watch?v=12345678901", title = "Third")
    private fun selection(tracks: List<Track>, favorites: Boolean = false) = ImportSelection(ImportCollection("test", "List", tracks), true, favorites)
    @Before fun open() { db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build() }
    @After fun close() { db.close(); context.deleteDatabase("migration-import.db") }
    @Test fun repeatedImportsMergeInOrderAndPreserveHeartsAndHistory() = runTest {
        val dao = db.library()
        dao.toggleFavorite(a); dao.recordPlay(a, 42)
        val initial = dao.importCollections(listOf(selection(listOf(b, a, b), true)), 100)
        assertEquals(1, initial.newTracks); assertEquals(1, initial.newFavorites); assertEquals(2, initial.addedEntries)
        val repeated = dao.importCollections(listOf(selection(listOf(a, c), true)), 200)
        assertEquals(0, repeated.newPlaylists); assertEquals(1, repeated.addedEntries)
        val id = dao.findPlaylist("test")!!.id
        assertEquals(listOf(b.url, a.url, c.url), dao.playlistTracks(id).first().map { it.url })
        assertEquals(42L, dao.find(a.url)!!.lastPlayedAt)
        assertEquals(1, dao.find(a.url)!!.playCount)
        val again = dao.importCollections(listOf(selection(listOf(a, b, c), true)), 300)
        assertEquals(ImportSummary(), again)
    }
    @Test fun oauthYoutubeAndLinkImportsShareTheDestinationAndKeepOriginalOrder() = runTest {
        val key = "youtube:PLabcdefghijk"
        val original = ImportCollection(key, "Private list imported with consent", listOf(c, a, b))
        db.library().importCollections(listOf(ImportSelection(original, true, false)), 1)
        val result = db.library().importCollections(listOf(ImportSelection(original.copy(name = "Renamed upstream"), true, false)), 2)
        assertEquals(ImportSummary(), result)
        val id = db.library().findPlaylist(key)!!.id
        assertEquals(listOf(c.url, a.url, b.url), db.library().playlistTracks(id).first().map { it.url })
        val backup = db.library().exportBackup()
        assertEquals(key, backup.playlists.single().key)
        assertEquals(3, backup.tracks.size)
    }
    @Test fun clearingHistoryRetainsPlaylistTracksAndDeletingAListRetainsFavorites() = runTest {
        val dao = db.library()
        dao.importCollections(listOf(selection(listOf(a, b))), 100)
        dao.toggleFavorite(a); dao.recordPlay(b, 42)
        dao.clearHistory()
        assertNotNull(dao.find(b.url))
        dao.deletePlaylist(dao.findPlaylist("test")!!.id)
        assertTrue(dao.favorites().first().single().favorite)
        assertNull(dao.find(b.url))
    }
    @Test fun failureRollsBackTheWholeImport() = runTest {
        val failing = object : AbstractList<ImportSelection>() {
            override val size = 2
            override fun get(index: Int): ImportSelection = if (index == 0) selection(listOf(a)) else throw IllegalStateException("Injected failure")
        }
        try { db.library().importCollections(failing, 100); fail("Expected failure") } catch (_: IllegalStateException) { }
        assertNull(db.library().find(a.url))
        assertNull(db.library().findPlaylist("test"))
    }
    @Test fun unresolvedImportsCannotOverwriteKnownMetadataOrToggleFavoritesOff() = runTest {
        val dao = db.library(); dao.toggleFavorite(a)
        val unknown = a.copy(title = YouTubeLinks.fallbackTitle(a.url), artist = "Unknown artist", durationSeconds = 0)
        dao.importCollections(listOf(selection(listOf(unknown), false)), 100)
        assertEquals("First", dao.find(a.url)!!.title)
        assertTrue(dao.find(a.url)!!.favorite)
        assertEquals(120L, dao.find(a.url)!!.durationSeconds)
    }
    @Test fun networkLookupFailuresStillSaveReferencesAndCanBeRetried() = runTest {
        var calls = 0
        val source = object : LibraryImportSource {
            override suspend fun playlist(url: String, progress: (Int) -> Unit): ImportCollection = error("unused")
            override suspend fun track(url: String): Track { calls++; throw MusicSourceException(MusicSourceException.Kind.NETWORK) }
        }
        val repo = ImportRepository(source, db.library())
        val raw = a.copy(title = YouTubeLinks.fallbackTitle(a.url))
        val (ready, unresolved) = repo.prepare(listOf(selection(listOf(raw))), true) { _, _ -> }
        assertEquals(1, unresolved); assertEquals(1, calls)
        val summary = repo.save(ready, unresolved)
        assertEquals(1, summary.unresolved)
        assertEquals(raw.url, db.library().playlistTracks(db.library().findPlaylist("test")!!.id).first().single().url)
    }
    @Test fun versionOneDatabaseMigratesWithoutLosingExistingLibrary() = runTest {
        context.deleteDatabase("migration-import.db")
        context.openOrCreateDatabase("migration-import.db", 0, null).use { old ->
            old.execSQL("CREATE TABLE tracks (url TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, artist TEXT NOT NULL, artwork TEXT NOT NULL, durationSeconds INTEGER NOT NULL, favorite INTEGER NOT NULL, lastPlayedAt INTEGER, playCount INTEGER NOT NULL)")
            old.execSQL("INSERT INTO tracks VALUES (?, ?, ?, ?, ?, ?, ?, ?)", arrayOf(a.url, a.title, a.artist, "", 120, 1, 42, 3))
            old.version = 1
        }
        val upgraded = Room.databaseBuilder(context, LibraryDatabase::class.java, "migration-import.db")
            .addMigrations(DatabaseMigrations.FROM_1_TO_2, DatabaseMigrations.FROM_2_TO_3, DatabaseMigrations.FROM_3_TO_4, DatabaseMigrations.FROM_4_TO_5).allowMainThreadQueries().build()
        try {
            val record = upgraded.library().find(a.url)!!
            assertTrue(record.favorite); assertEquals(3, record.playCount); assertEquals(42L, record.lastPlayedAt)
            upgraded.library().importCollections(listOf(selection(listOf(a))), 100)
            assertEquals(1, upgraded.library().playlists().first().single().trackCount)
        } finally { upgraded.close() }
    }
}
