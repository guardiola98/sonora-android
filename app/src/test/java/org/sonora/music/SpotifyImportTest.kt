package org.sonora.music

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.sonora.music.importing.*
import org.sonora.music.discovery.*
import org.sonora.music.domain.*

@OptIn(ExperimentalCoroutinesApi::class)
class SpotifyImportTest {
    private val track = Track("https://www.youtube.com/watch?v=abcdefghijk", "La flama", "Obrint Pas - Topic", "", 180)
    private val song = RemoteSong("one", "La flama", listOf("Obrint Pas"), 180, "https://open.spotify.com/track/one")
    private val list = RemotePlaylist("abcdefghijklmno", "My ordered playlist", 2, "https://open.spotify.com/playlist/abcdefghijklmno")
    private fun catalog(search: suspend (String) -> List<Track> = { listOf(track) }) = object : MusicCatalogSource {
        override suspend fun searchPage(query: String, kind: CatalogKind, cursor: String?) = CatalogPage(search(query).map { CatalogEntry(it.url, it.title, it.artist, "", CatalogKind.SONGS, it, true) })
        override suspend fun collection(url: String, cursor: String?) = CatalogPage(emptyList())
        override suspend fun suggestions(query: String) = emptyList<String>()
        override suspend fun related(track: Track) = emptyList<Track>()
    }
    @Test fun bestVersionWinsButApproximateAmbiguousAndMissingDurationResultsAreAutomatic() {
        val approximate = track.copy(url = "https://www.youtube.com/watch?v=lmnopqrstuv", durationSeconds = 220, artist = "Music fan channel")
        assertEquals(track, SpotifyMatcher.match(song, listOf(approximate, track)).chosen)
        assertEquals(approximate, SpotifyMatcher.match(song, listOf(approximate)).chosen)
        assertEquals(track, SpotifyMatcher.match(song, listOf(track, track.copy(url = "https://www.youtube.com/watch?v=lmnopqrstuv"))).chosen)
        assertEquals(track, SpotifyMatcher.match(song.copy(durationSeconds = 0), listOf(track)).chosen)
        assertEquals(track, SpotifyMatcher.match(song.copy(title = "LA FLÀMA"), listOf(track)).chosen)
        val live = track.copy(url = "https://www.youtube.com/watch?v=live123test", title = "La flama live")
        assertEquals(track, SpotifyMatcher.match(song, listOf(live, track)).chosen)
        assertEquals(live, SpotifyMatcher.match(song, listOf(live)).chosen)
        assertEquals(live, SpotifyMatcher.match(song.copy(title = "La flama live"), listOf(track, live)).chosen)
    }
    @Test fun considersAllResultsAndRejectsNonCanonicalOrUnavailableReferences() {
        val candidates = (0..7).map { track.copy(url = "https://www.youtube.com/watch?v=abcdefghij$it", durationSeconds = if (it == 7) 180 else 250) }
        assertEquals(candidates.last(), SpotifyMatcher.match(song, candidates).chosen)
        assertNull(SpotifyMatcher.match(song, listOf(track.copy(url = "https://example.com/not-youtube"))).chosen)
        assertNull(SpotifyMatcher.match(song.copy(unavailable = "Local file"), listOf(track)).chosen)
        assertNull(SpotifyMatcher.match(song, emptyList()).chosen)
    }
    @Test fun publicLinkAndAllExportListsSaveDirectlyIncludingLikesAndApproximateVersions() = runTest {
        val output = mutableListOf<List<ImportSelection>>()
        val approximate = track.copy(durationSeconds = 220)
        val presenter = SpotifyImportPresenter(this, catalog { listOf(approximate) }) { output += it; ImportSummary(newPlaylists = it.count { it.playlist }) }
        val row = ReviewedPlaylist(list, listOf(ReviewedSong(song)))
        presenter.loadSpotifyPublicPlaylist { row }; advanceUntilIdle()
        assertTrue(presenter.state.value.publicLink)
        assertEquals(approximate, output.single().single().collection.tracks.single())
        assertNotNull(presenter.state.value.summary)
        presenter.loadSpotifyExport { listOf(row, row.copy(source = list.copy(id = "likes", likes = true))) }; advanceUntilIdle()
        assertFalse(presenter.state.value.publicLink)
        assertEquals(listOf("spotify:${list.id}", "spotify:likes"), output.last().map { it.collection.key })
        assertTrue(output.last()[0].playlist); assertTrue(output.last()[1].favorites); assertFalse(output.last()[1].playlist)
    }
    @Test fun threeConcurrentSearchesCacheRepeatedSongsAndPreserveOrderBeyondTwoHundred() = runTest {
        var active = 0; var peak = 0; var queries = 0
        var saved: List<ImportSelection>? = null
        val source = catalog { query ->
            queries++; active++; peak = maxOf(peak, active)
            val index = query.substringAfterLast(' ').toInt()
            delay(if (index % 3 == 0) 90 else 10)
            active--
            listOf(track.copy(url = "https://www.youtube.com/watch?v=" + index.toString().padStart(11, '0'), title = "Song $index"))
        }
        val rows = (0..202).map { ReviewedSong(song.copy(id = "$it", title = "Song $it")) }
        val presenter = SpotifyImportPresenter(this, source) { saved = it; ImportSummary() }
        presenter.loadSpotifyExport { listOf(ReviewedPlaylist(list, rows + rows.first()), ReviewedPlaylist(list.copy(id = "second"), rows.take(3))) }
        advanceUntilIdle()
        assertEquals(203, queries); assertEquals(3, peak)
        assertEquals((0..202).map { "Song $it" }, saved!![0].collection.tracks.map { it.title })
        assertEquals(1, presenter.state.value.duplicates)
        assertEquals(207, presenter.state.value.matched)
        assertTrue("Workers should keep three requests in flight", testScheduler.currentTime < 2_600)
        println("203 distinct searches, 207 entries: ${testScheduler.currentTime} ms virtual time; 7470 ms sequential service time; peak $peak requests")
    }
    @Test fun emptyResultsErrorsAndTimeoutsSkipOnlyThoseEntriesAndDoNotStopLaterSongs() = runTest {
        var saved: List<ImportSelection>? = null
        val source = catalog { query -> when (query.substringAfterLast(' ')) {
            "0", "1", "2" -> emptyList()
            "3" -> throw java.io.IOException("offline")
            "4" -> { awaitCancellation() }
            else -> listOf(track)
        } }
        val presenter = SpotifyImportPresenter(this, source) { saved = it; ImportSummary(newTracks = 1) }
        presenter.loadSpotifyPublicPlaylist { ReviewedPlaylist(list, (0..5).map { ReviewedSong(song.copy(title = "Song $it")) }) }
        advanceUntilIdle()
        assertEquals(listOf(track), saved!!.single().collection.tracks)
        assertEquals(5, presenter.state.value.skipped); assertEquals(2, presenter.state.value.failedSearches)
        assertEquals(1, presenter.state.value.matched); assertNotNull(presenter.state.value.summary)
    }
    @Test fun cancellationDuringMetadataOrParallelLookupNeverSavesAndNextImportStartsFresh() = runTest {
        var saves = 0; var cancelled = 0
        val presenter = SpotifyImportPresenter(this, catalog { try { awaitCancellation() } finally { cancelled++ } }) { saves++; ImportSummary() }
        presenter.loadSpotifyExport { awaitCancellation() }; runCurrent(); presenter.cancel(); runCurrent()
        assertEquals(SpotifyImportState(), presenter.state.value)
        presenter.loadSpotifyPublicPlaylist { ReviewedPlaylist(list, (0..2).map { ReviewedSong(song.copy(title = "Song $it")) }) }; runCurrent()
        presenter.cancel(); runCurrent()
        assertEquals(3, cancelled); assertEquals(0, saves); assertEquals(SpotifyImportState(), presenter.state.value)
        presenter.loadSpotifyExport { listOf(ReviewedPlaylist(list, emptyList())) }; advanceUntilIdle()
        assertEquals(1, saves); assertNotNull(presenter.state.value.summary)
    }
    @Test fun noMatchesOrAllNetworkFailuresAreAnErrorAndDoNotCreateEmptySuccess() = runTest {
        for (source in listOf(catalog { emptyList() }, catalog { throw java.io.IOException() })) {
            var saves = 0
            val presenter = SpotifyImportPresenter(this, source) { saves++; ImportSummary() }
            presenter.loadSpotifyPublicPlaylist { ReviewedPlaylist(list, listOf(ReviewedSong(song))) }; advanceUntilIdle()
            assertEquals(0, saves); assertNull(presenter.state.value.summary); assertNotNull(presenter.state.value.error)
            assertFalse(presenter.state.value.busy)
        }
    }
    @Test fun existingPlayableReferencesAvoidSearchAndUnavailableRowsAreSkipped() = runTest {
        var queries = 0
        val presenter = SpotifyImportPresenter(this, catalog { queries++; listOf(track) }) { ImportSummary(newTracks = it.single().collection.tracks.size) }
        presenter.loadSpotifyExport { listOf(ReviewedPlaylist(list, listOf(ReviewedSong(song.copy(playable = track)), ReviewedSong(song.copy(unavailable = "Local file"))))) }
        advanceUntilIdle()
        assertEquals(0, queries); assertEquals(1, presenter.state.value.skipped); assertEquals(1, presenter.state.value.summary!!.newTracks)
    }
    @Test fun duplicateStartAndCancelDuringCommitDoNotInterruptOrRepeatTheTransaction() = runTest {
        val finish = CompletableDeferred<Unit>(); var saves = 0
        val presenter = SpotifyImportPresenter(this, catalog()) { saves++; finish.await(); ImportSummary(newPlaylists = 1) }
        presenter.loadSpotifyPublicPlaylist { ReviewedPlaylist(list, listOf(ReviewedSong(song))) }; runCurrent()
        assertTrue(presenter.state.value.committing)
        presenter.cancel()
        presenter.loadSpotifyExport { error("Should not start twice") }; runCurrent()
        assertEquals(1, saves); assertTrue(presenter.state.value.committing)
        finish.complete(Unit); advanceUntilIdle()
        assertFalse(presenter.state.value.committing); assertEquals(1, presenter.state.value.summary!!.newPlaylists)
    }
    @Test fun loadAndTransactionFailuresAreReportedWithoutStaleSuccess() = runTest {
        val presenter = SpotifyImportPresenter(this, catalog()) { throw IllegalStateException("DB failed") }
        presenter.loadSpotifyExport { throw ImportAccessException("Bad export") }; advanceUntilIdle()
        assertEquals("Bad export", presenter.state.value.error)
        presenter.loadSpotifyPublicPlaylist { ReviewedPlaylist(list, listOf(ReviewedSong(song))) }; advanceUntilIdle()
        assertNotNull(presenter.state.value.error); assertNull(presenter.state.value.summary)
        assertFalse(presenter.state.value.committing); assertFalse(presenter.state.value.busy)
    }
}
