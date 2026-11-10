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
import org.sonora.music.data.LibraryDatabase
import org.sonora.music.domain.Track

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LibraryDatabaseTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: LibraryDatabase
    private val track = Track("https://www.youtube.com/watch?v=test", "Test", "Artist", "", 90)
    @Before fun open() {
        context.deleteDatabase("library-test.db")
        db = Room.databaseBuilder(context, LibraryDatabase::class.java, "library-test.db").allowMainThreadQueries().build()
    }
    @After fun close() { db.close(); context.deleteDatabase("library-test.db") }
    @Test fun favoritePersistsAcrossDatabaseReopenAndPlayingPreservesIt() = runTest {
        db.library().toggleFavorite(track)
        db.close()
        db = Room.databaseBuilder(context, LibraryDatabase::class.java, "library-test.db").allowMainThreadQueries().build()
        assertEquals(track.url, db.library().favorites().first().single().url)
        db.library().recordPlay(track, 100)
        db.library().recordPlay(track, 200)
        val record = db.library().history().first().single()
        assertTrue(record.favorite)
        assertEquals(2, record.playCount)
        db.library().toggleFavorite(track)
        assertTrue(db.library().favorites().first().isEmpty())
        assertEquals(2, db.library().history().first().single().playCount)
    }
    @Test fun clearHistoryKeepsFavoritesAndRemovesUnneededRecords() = runTest {
        db.library().toggleFavorite(track)
        db.library().recordPlay(track, 100)
        val other = track.copy(url = "other")
        db.library().recordPlay(other, 200)
        assertEquals("other", db.library().history().first().first().url)
        db.library().clearHistory()
        assertTrue(db.library().history().first().isEmpty())
        assertTrue(db.library().favorites().first().single().favorite)
        assertNull(db.library().find("other"))
    }
}
