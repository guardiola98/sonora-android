package org.sonora.music

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.sonora.music.data.*
import org.sonora.music.domain.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LibraryFeaturesTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: LibraryDatabase
    private val a = Track("https://www.youtube.com/watch?v=abcdefghijk", "Primera 🎵", "Artista", "", 120)
    private val b = a.copy(url = "https://www.youtube.com/watch?v=lmnopqrstuv", title = "Segona")
    private val c = a.copy(url = "https://www.youtube.com/watch?v=12345678901", title = "Tercera")
    @Before fun open() { db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build() }
    @After fun close() { db.close(); context.deleteDatabase("features-migration.db") }
    @Test fun backupRoundTripRestoresEmptyListsFavoritesHistoryAndOrder() = runBlocking {
        val dao = db.library()
        val id = dao.createPlaylistWithTracks("Viatge", listOf(b, a))
        dao.createPlaylist("Empty"); dao.toggleFavorite(a); dao.recordPlay(a, 42)
        val backup = BackupCodec.read(BackupCodec.encode(dao.exportBackup()).byteInputStream())
        val restored = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        try {
            val first = restored.library().restoreBackup(backup)
            assertEquals(RestoreSummary(2, 1, 2, 2), first)
            assertEquals(RestoreSummary(0, 0, 0, 0), restored.library().restoreBackup(backup))
            val key = dao.playlist(id)!!.sourceKey
            val restoredId = restored.library().findPlaylist(key)!!.id
            assertEquals(listOf(b.url, a.url), restored.library().playlistTracks(restoredId).first().map { it.url })
            assertEquals(42L, restored.library().find(a.url)!!.lastPlayedAt)
            assertEquals(1, restored.library().find(a.url)!!.playCount)
            assertEquals(2, restored.library().playlists().first().size)
        } finally { restored.close() }
    }
    @Test fun restoringAnOlderBackupPreservesRecentHistoryAndLocalFavorites() = runBlocking {
        val dao = db.library(); dao.toggleFavorite(a); dao.recordPlay(a, 100)
        val old = dao.exportBackup()
        dao.recordPlay(a, 200)
        dao.restoreBackup(old.copy(tracks = old.tracks.map { it.copy(favorite = false) }))
        assertEquals(200L, dao.find(a.url)!!.lastPlayedAt)
        assertEquals(2, dao.find(a.url)!!.playCount)
        assertTrue(dao.find(a.url)!!.favorite)
    }
    @Test fun playlistEditingPersistsDeduplicatesAndProtectsOtherLibraryData() = runBlocking {
        val dao = db.library()
        val id = dao.createPlaylistWithTracks("  Original  ", listOf(a, b, a))
        dao.toggleFavorite(a); dao.recordPlay(b, 42)
        dao.renamePlaylist(id, "Renamed")
        dao.addToPlaylist(id, listOf(c, a))
        dao.reorderPlaylist(id, listOf(c.url, b.url, a.url))
        assertEquals("Renamed", dao.playlist(id)!!.name)
        assertEquals(listOf(c.url, b.url, a.url), dao.playlistTracks(id).first().map { it.url })
        dao.removeFromPlaylist(id, a.url)
        assertTrue(dao.find(a.url)!!.favorite)
        dao.deletePlaylist(id)
        assertEquals(42L, dao.find(b.url)!!.lastPlayedAt)
        assertNull(dao.find(c.url))
    }
    @Test fun reorderingKeepsConcurrentAdditionsAndReimportKeepsCustomName() = runBlocking {
        val dao = db.library(); val id = dao.createPlaylistWithTracks("Mine", listOf(a, b))
        val list = dao.playlist(id)!!
        dao.addToPlaylist(id, listOf(c))
        dao.reorderPlaylist(id, listOf(b.url, a.url))
        dao.importCollections(listOf(ImportSelection(ImportCollection(list.sourceKey, "Old name", listOf(a)), true, false)), 1)
        assertEquals("Mine", dao.playlist(id)!!.name)
        assertEquals(listOf(b.url, a.url, c.url), dao.playlistTracks(id).first().map { it.url })
    }
    @Test fun failedRestoreRollsBackEvenPreviouslyWrittenTracks() = runBlocking {
        val record = LibraryTrack(a.url, a.title, a.artist, "", 120, true)
        val invalid = LibraryBackup(1, listOf(record), listOf(BackupPlaylist("missing", "Bad", listOf(b.url))))
        try { db.library().restoreBackup(invalid); fail("Expected rollback") } catch (_: NoSuchElementException) { }
        assertNull(db.library().find(a.url))
        assertTrue(db.library().playlists().first().isEmpty())
    }
    @Test fun backupRejectsUnsupportedVersionsMissingReferencesAndUnsafeUrls() = runBlocking {
        db.library().createPlaylistWithTracks("Test", listOf(a))
        val text = BackupCodec.encode(db.library().exportBackup())
        fun rejects(value: String) { try { BackupCodec.decode(value); fail("Accepted invalid backup") } catch (_: IllegalArgumentException) { } }
        rejects(JSONObject(text).put("version", 99).toString())
        rejects(JSONObject(text).apply { getJSONArray("tracks").getJSONObject(0).put("url", "https://attacker.invalid/watch?v=abcdefghijk") }.toString())
        val missing = JSONObject(text).apply { getJSONArray("playlists").getJSONObject(0).getJSONArray("tracks").put(0, b.url) }
        rejects(missing.toString())
        val unrelatedImage = JSONObject(text).apply { getJSONArray("tracks").getJSONObject(0).put("artwork", "https://attacker.invalid/pixel") }.toString()
        assertEquals("", BackupCodec.decode(unrelatedImage).tracks.first().artwork)
    }
    @Test fun exportDoesNotProduceABackupThatExceedsRestoreLimits() {
        val record = LibraryTrack(a.url, a.title, a.artist, "", 120, true)
        try { BackupCodec.encode(LibraryBackup(1, List(50_001) { record }, emptyList())); fail("Accepted oversized library") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun importedMaximumPlayCountCannotOverflowOnPlayback() = runBlocking {
        db.library().restoreBackup(LibraryBackup(1, listOf(LibraryTrack(a.url, a.title, a.artist, "", 120, true, 1, Int.MAX_VALUE)), emptyList()))
        db.library().recordPlay(a, 2)
        assertEquals(Int.MAX_VALUE, db.library().find(a.url)!!.playCount)
    }
    @Test fun excessiveJsonNestingFailsBeforeThePlatformParserRecurses() {
        val malicious = "{\"extra\":" + "[".repeat(40) + "0" + "]".repeat(40) + "}"
        try { BackupCodec.decode(malicious); fail("Accepted excessive nesting") } catch (_: IllegalArgumentException) { }
        val record = LibraryTrack(a.url, "Literal [brackets] {braces}", a.artist, "", 120, true)
        val roundTrip = BackupCodec.decode(BackupCodec.encode(LibraryBackup(1, listOf(record), emptyList())))
        assertEquals(record.title, roundTrip.tracks.single().title)
    }
    @Test fun backupSizeIsBoundedBeforeParsing() {
        val input = object : java.io.InputStream() {
            var remaining = BackupCodec.MAX_BYTES + 1
            override fun read() = if (remaining-- > 0) 65 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (remaining <= 0) return -1
                val count = minOf(len, remaining); java.util.Arrays.fill(b, off, off + count, 65.toByte()); remaining -= count; return count
            }
        }
        try { BackupCodec.read(input); fail("Accepted oversized backup") } catch (_: IllegalArgumentException) { }
    }
    @Test fun queueRoundTripDropsEphemeralOrLocalUrisAndRetainsSelectedTrack() {
        val queue = SavedQueue(listOf(a.copy(url = "file:///private/fixture.wav"), b, a), 2, 12_345).canonical()
        assertEquals(listOf(b, a), queue.tracks); assertEquals(1, queue.index)
        assertEquals(queue, PlaybackQueueStore.decode(PlaybackQueueStore.encode(queue)))
        assertEquals(0L, SavedQueue(listOf(a), 99, 123).canonical().positionMs)
    }
    @Test fun queueWriterPersistsLatestPositionAndClearingWithoutResurrectingOldData() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PlaybackQueueStore(db.library(), scope) { fail("Queue write failed") }
            store.save(SavedQueue(listOf(a, b), 1, 1000))
            store.save(SavedQueue(listOf(a, b), 1, 9000))
            withTimeout(10_000) { while (db.library().checkpoint()?.positionMs != 9000L) delay(30) }
            assertEquals(SavedQueue(listOf(a, b), 1, 9000), PlaybackQueueStore.decode(db.library().checkpoint()!!))
            store.save(SavedQueue())
            assertTrue(store.read().tracks.isEmpty())
            withTimeout(10_000) { while (PlaybackQueueStore.decode(db.library().checkpoint()!!).tracks.isNotEmpty()) delay(30) }
        } finally { scope.cancel() }
    }
    @Test fun versionTwoMigratesWithPlaylistOrderAndFavoritesIntact() = runBlocking {
        context.openOrCreateDatabase("features-migration.db", 0, null).use { old ->
            old.execSQL("CREATE TABLE tracks (url TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, artist TEXT NOT NULL, artwork TEXT NOT NULL, durationSeconds INTEGER NOT NULL, favorite INTEGER NOT NULL, lastPlayedAt INTEGER, playCount INTEGER NOT NULL)")
            old.execSQL("CREATE TABLE playlists (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sourceKey TEXT NOT NULL, name TEXT NOT NULL, importedAt INTEGER NOT NULL)")
            old.execSQL("CREATE UNIQUE INDEX index_playlists_sourceKey ON playlists (sourceKey)")
            old.execSQL("CREATE TABLE playlist_entries (playlistId INTEGER NOT NULL, trackUrl TEXT NOT NULL, position INTEGER NOT NULL, PRIMARY KEY(playlistId, trackUrl), FOREIGN KEY(playlistId) REFERENCES playlists(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(trackUrl) REFERENCES tracks(url) ON UPDATE NO ACTION ON DELETE CASCADE)")
            old.execSQL("CREATE INDEX index_playlist_entries_trackUrl ON playlist_entries (trackUrl)")
            old.execSQL("CREATE INDEX index_playlist_entries_playlistId_position ON playlist_entries (playlistId, position)")
            old.execSQL("INSERT INTO tracks VALUES (?, ?, ?, '', 120, 1, 42, 3)", arrayOf(a.url, a.title, a.artist))
            old.execSQL("INSERT INTO playlists VALUES (1, 'old', 'Existing', 42)")
            old.execSQL("INSERT INTO playlist_entries VALUES (1, ?, 0)", arrayOf(a.url)); old.version = 2
        }
        val upgraded = Room.databaseBuilder(context, LibraryDatabase::class.java, "features-migration.db").addMigrations(DatabaseMigrations.FROM_2_TO_3, DatabaseMigrations.FROM_3_TO_4).allowMainThreadQueries().build()
        try {
            assertTrue(upgraded.library().find(a.url)!!.favorite)
            assertEquals(a.url, upgraded.library().playlistTracks(1).first().single().url)
            upgraded.library().saveCheckpoint(PlaybackQueueStore.encode(SavedQueue(listOf(a), 0, 500)))
            assertEquals(500L, upgraded.library().checkpoint()!!.positionMs)
        } finally { upgraded.close() }
    }
}
