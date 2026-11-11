package org.sonora.music

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.sonora.music.data.*
import org.sonora.music.discovery.*
import org.sonora.music.domain.Track
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DiscoveryPersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: LibraryDatabase
    private val track = Track("https://www.youtube.com/watch?v=00000000001", "Test recording", "Test credit", "", 180)
    @Before fun open() { db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build() }
    @After fun close() { db.close() }
    @Test fun historyDeduplicatesNormalizedQueriesAndKeepsOnlyFifty() = runTest {
        db.discovery().rememberSearch("Óbrint Pàs", 1); db.discovery().rememberSearch("OBRINT PAS", 2)
        assertEquals(1, db.discovery().searches().first().size); assertEquals(2, db.discovery().searches().first().single().uses)
        repeat(70) { db.discovery().rememberSearch("query $it", it + 3L) }
        assertEquals(50, db.discovery().searches().first().size)
        db.discovery().clearSearches(); assertTrue(db.discovery().searches().first().isEmpty())
    }
    @Test fun feedbackMergesTransactionallyAndResetDoesNotEraseTheLibrary() = runTest {
        db.library().toggleFavorite(track); db.library().recordPlay(track, 5)
        coroutineScope { repeat(10) { launch { db.discovery().feedback(track.url, "complete", 1000) } } }
        db.discovery().feedback(track.url, "hide"); db.discovery().feedback(track.url, "skip")
        val row = db.discovery().preference(track.url)!!
        assertEquals(10, row.completions); assertEquals(10000L, row.listenedMs); assertTrue(row.hidden); assertEquals(1, row.earlySkips)
        db.discovery().resetPreferences(); assertNull(db.discovery().preference(track.url))
        assertTrue(db.library().find(track.url)!!.favorite); assertEquals(1, db.library().find(track.url)!!.playCount)
    }
    @Test fun expiredMetadataAndNegativeLookupsNeverBecomeArtistFacts() = runTest {
        val now = System.currentTimeMillis()
        db.discovery().saveCache(DiscoveryCacheRow("metadata:a", "null", "MusicBrainz", now, now + 60000))
        db.discovery().saveCache(DiscoveryCacheRow("metadata:b", "{}", "MusicBrainz", now - 60000, now - 1))
        assertEquals(listOf("metadata:a"), db.discovery().metadataSnapshot(now).map { it.key })
    }
    @Test fun radioCheckpointRoundTripsAndLegacyArrayStillRestores() {
        val queue = SavedQueue(listOf(track, track.copy(url = "https://www.youtube.com/watch?v=00000000002")), 1, 32000, track, mapOf("https://www.youtube.com/watch?v=00000000002" to "Related by the source"), listOf(track))
        assertEquals(queue, PlaybackQueueStore.decode(PlaybackQueueStore.encode(queue)))
        val old = PlaybackCheckpoint(queueJson = "[${BackupCodec.trackJson(track)}]", currentIndex = 0, positionMs = 1234)
        val restored = PlaybackQueueStore.decode(old)
        assertEquals(track, restored.tracks.single()); assertEquals(1234L, restored.positionMs); assertNull(restored.radioSeed)
    }
    @Test fun migrationFourToFivePreservesMusicPodcastsAndPausedCheckpoint() = runTest {
        val name = "discovery-migration.db"; context.deleteDatabase(name)
        val schemaFile = listOf(File("schemas/org.sonora.music.data.LibraryDatabase/4.json"), File("app/schemas/org.sonora.music.data.LibraryDatabase/4.json")).first { it.exists() }
        val schema = JSONObject(schemaFile.readText()).getJSONObject("database").getJSONArray("entities")
        val old = context.openOrCreateDatabase(name, 0, null)
        for (i in 0 until schema.length()) { val entity = schema.getJSONObject(i); old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            val indices = entity.optJSONArray("indices") ?: org.json.JSONArray(); for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName"))) }
        old.execSQL("INSERT INTO tracks VALUES (?, ?, ?, '', 180, 1, 100, 3)", arrayOf(track.url, track.title, track.artist))
        old.execSQL("INSERT INTO podcast_feeds VALUES ('https://example.org/feed', 'Show', 'Publisher', '', '', 1, 100)")
        old.execSQL("INSERT INTO playback_checkpoint VALUES (1, ?, 0, 4321)", arrayOf("[${BackupCodec.trackJson(track)}]"))
        old.version = 4; old.close()
        val upgraded = Room.databaseBuilder(context, LibraryDatabase::class.java, name).addMigrations(DatabaseMigrations.FROM_4_TO_5).allowMainThreadQueries().build()
        try {
            assertTrue(upgraded.library().find(track.url)!!.favorite); assertEquals(3, upgraded.library().find(track.url)!!.playCount)
            assertEquals(4321L, upgraded.library().checkpoint()!!.positionMs)
            upgraded.discovery().rememberSearch("music"); assertEquals("music", upgraded.discovery().searches().first().single().query)
            assertEquals(1, upgraded.library().exportBackup().podcasts.size)
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }
    @Test fun ambiguousRecordingsNeverInventArtistAlbumLanguageOrScene() {
        fun recording(id: String) = JSONObject("""{"id":"$id","score":100,"title":"Test recording","length":180000,"artist-credit":[{"artist":{"id":"artist-id","name":"Test credit"}}]}""")
        val root = JSONObject().put("recordings", org.json.JSONArray().put(recording("one")).put(recording("two")))
        val ambiguous = MusicBrainzMetadataProvider.match(track, "Test credit", "Test recording", root, 1)!!
        assertEquals("Test credit", ambiguous.artist!!.value); assertNull(ambiguous.recordingId); assertNull(ambiguous.album)
        root.put("recordings", org.json.JSONArray().put(recording("one")))
        val match = MusicBrainzMetadataProvider.match(track, "Test credit", "Test recording", root, 1)!!
        assertEquals("Test credit", match.artist!!.value); assertNull(match.album); assertNull(match.language); assertNull(match.scene)
        assertEquals(match, MetadataCodec.decode(MetadataCodec.encode(match)))
    }
    @Test fun publicArtistSimilarityResponseUsesOnlyActualIdsAndNames() {
        val parsed = ListenBrainzArtistSimilarityProvider.parse("""[{"artist_mbid":"83d91898-7763-47d7-b03b-b92132375c47","name":"Pink Floyd","score":11455},{"artist_mbid":"invalid","name":"Wrong"}]""", 123)
        assertEquals(1, parsed.size); assertEquals("Pink Floyd", parsed.single().name); assertEquals(123L, parsed.single().evidence.fetchedAt)
    }
    @Test fun offlineDiscoveryStillRanksFavoritesWithoutMutatingHistory() = runTest {
        val other = track.copy(url = "https://www.youtube.com/watch?v=00000000002", title = "Other song", artist = "Other credit")
        db.library().toggleFavorite(other)
        val settings = DiscoverySettings(context.getSharedPreferences("offline-test", 0)).also { it.update(DiscoveryOptions(enrichment = false)) }
        val source = object : MusicCatalogSource {
            override suspend fun searchPage(query: String, kind: CatalogKind, cursor: String?): CatalogPage = throw java.io.IOException("offline")
            override suspend fun suggestions(query: String) = emptyList<String>()
            override suspend fun related(track: Track): List<Track> = throw java.io.IOException("offline")
            override suspend fun collection(url: String, cursor: String?) = CatalogPage(emptyList())
        }
        val repo = DiscoveryRepository(source, RoomLocalHistoryProvider(db.library(), db.discovery()), db.discovery(), settings,
            object : MusicMetadataProvider { override suspend fun metadata(track: Track): MusicMetadata? = error("Enrichment disabled") },
            object : ArtistSimilarityProvider { override suspend fun similar(artistId: String): List<SimilarArtist> = error("Enrichment disabled") })
        val result = repo.recommend(track, setOf(track.url), listOf(track), 5)
        assertEquals(other.url, result.recommendations.single().track.url); assertNotNull(result.notice)
        assertTrue(db.library().history().first().isEmpty())
    }
}
