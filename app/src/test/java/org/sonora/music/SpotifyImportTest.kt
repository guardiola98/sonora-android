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
    private val lookup = object : LibraryImportSource {
        override suspend fun track(url: String) = track.copy(url = url)
        override suspend fun playlist(url: String, progress: (Int) -> Unit): ImportCollection = error("unused")
    }
    private fun catalog(tracks: List<Track> = listOf(track)) = object : MusicCatalogSource {
        override suspend fun searchPage(query: String, kind: CatalogKind, cursor: String?) = CatalogPage(tracks.map { CatalogEntry(it.url, it.title, it.artist, "", CatalogKind.SONGS, it, true) })
        override suspend fun collection(url: String, cursor: String?) = CatalogPage(emptyList())
        override suspend fun suggestions(query: String) = emptyList<String>()
        override suspend fun related(track: Track) = emptyList<Track>()
    }
    @Test fun strictMatchingRejectsAmbiguousVersionsDurationAndUnrelatedUploaders() {
        assertEquals(track, SpotifyMatcher.match(song, listOf(track)).chosen)
        assertNull(SpotifyMatcher.match(song, listOf(track, track.copy(url = "https://www.youtube.com/watch?v=lmnopqrstuv"))).chosen)
        assertNull(SpotifyMatcher.match(song, listOf(track.copy(title = "La flama live"))).chosen)
        assertNull(SpotifyMatcher.match(song, listOf(track.copy(durationSeconds = 220))).chosen)
        assertNull(SpotifyMatcher.match(song, listOf(track.copy(artist = "Music fan channel"))).chosen)
        assertNull(SpotifyMatcher.match(song.copy(durationSeconds = 0), listOf(track)).chosen)
        assertNull(SpotifyMatcher.match(song.copy(artists = listOf("Obrint")), listOf(track)).chosen)
        assertEquals(track, SpotifyMatcher.match(song.copy(title = "LA FLÀMA"), listOf(track)).chosen)
    }
    @Test fun everyCandidateIsCheckedForAmbiguityBeforeTheDisplayLimit() {
        val candidates = (0..7).map { track.copy(url = "https://www.youtube.com/watch?v=abcdefghij$it", durationSeconds = if (it == 0 || it == 7) 180 else 250) }
        assertNull(SpotifyMatcher.match(song, candidates).chosen)
    }
    @Test fun fileSelectionDoesNotAuthorizeOrSearchUntilTheUserChoosesAndConfirms() = runTest {
        var queries = 0; var output: ImportPreview? = null
        val counted = object : MusicCatalogSource by catalog() {
            override suspend fun searchPage(query: String, kind: CatalogKind, cursor: String?): CatalogPage { queries++; return catalog().searchPage(query, kind, cursor) }
        }
        val presenter = SpotifyImportPresenter(backgroundScope, counted, lookup, { output = it })
        val row = ReviewedPlaylist(list.copy(id = "export:fixture"), listOf(ReviewedSong(song.copy(durationSeconds = 0))))
        presenter.loadSpotifyExport { listOf(row) }; runCurrent()
        assertEquals(0, queries); assertTrue(presenter.state.value.active); assertFalse(presenter.state.value.publicLink)
        presenter.select(row.source.id); presenter.readSelected(); advanceTimeBy(1000); runCurrent()
        assertEquals(1, queries); assertEquals(1, presenter.state.value.pending)
        presenter.confirm(); assertNull(output)
        presenter.choose(row.source.id, 0, track); presenter.confirm()
        assertEquals("spotify:export:fixture", output!!.collections.single().key)
        assertEquals(listOf(track), output!!.collections.single().tracks)
    }
    @Test fun offlineFileReviewAndCancellationNeverSendQueriesOrLeaveOldLists() = runTest {
        var queries = 0
        val counted = object : MusicCatalogSource by catalog() {
            override suspend fun searchPage(query: String, kind: CatalogKind, cursor: String?): CatalogPage { queries++; return catalog().searchPage(query, kind, cursor) }
        }
        val presenter = SpotifyImportPresenter(backgroundScope, counted, lookup, {})
        presenter.loadSpotifyExport { listOf(ReviewedPlaylist(list, listOf(ReviewedSong(song)))) }; runCurrent()
        presenter.searchOnline(false); presenter.select(list.id); presenter.readSelected(); runCurrent()
        assertEquals(0, queries); assertEquals(1, presenter.state.value.pending)
        presenter.cancel(); assertEquals(SpotifyImportState(), presenter.state.value)
        var cancelled = false
        presenter.loadSpotifyExport { try { awaitCancellation() } finally { cancelled = true } }; runCurrent()
        presenter.cancel(); runCurrent(); assertTrue(cancelled)
        presenter.readSelected(); runCurrent(); assertEquals(SpotifyImportState(), presenter.state.value)
    }
    @Test fun cancellationDropsLoadingMetadataAndErrorsDoNotKeepOldSelections() = runTest {
        val presenter = SpotifyImportPresenter(backgroundScope, catalog(), lookup, {})
        var cancelled = false
        presenter.loadSpotifyExport { try { awaitCancellation() } finally { cancelled = true } }; runCurrent()
        presenter.cancel(); runCurrent()
        assertTrue(cancelled); assertEquals(SpotifyImportState(), presenter.state.value)
        presenter.loadSpotifyExport { listOf(ReviewedPlaylist(list, listOf(ReviewedSong(song)))) }; runCurrent()
        presenter.select(list.id)
        presenter.loadSpotifyExport { throw ImportAccessException("Bad export") }; runCurrent()
        assertEquals("Bad export", presenter.state.value.error)
        assertTrue(presenter.state.value.lists.isEmpty()); assertTrue(presenter.state.value.selected.isEmpty())
    }
    @Test fun emptyPlaylistAndPendingSongsNeedExplicitConfirmation() = runTest {
        var output: ImportPreview? = null
        val presenter = SpotifyImportPresenter(backgroundScope, catalog(), lookup, { output = it })
        presenter.loadSpotifyExport { listOf(ReviewedPlaylist(list, emptyList())) }; runCurrent()
        presenter.select(list.id); presenter.readSelected(); runCurrent(); presenter.confirm()
        assertTrue(output!!.allowEmptyCollections); assertTrue(output!!.collections.single().tracks.isEmpty())
        output = null
        presenter.loadSpotifyExport { listOf(ReviewedPlaylist(list, listOf(ReviewedSong(song)))) }; runCurrent()
        presenter.searchOnline(false); presenter.select(list.id); presenter.readSelected(); runCurrent()
        presenter.confirm(); assertNull(output)
        presenter.skipPending(true); presenter.confirm()
        assertEquals("spotify:${list.id}", output!!.collections.single().key)
        assertEquals(1, output!!.collections.single().skipped)
    }
}
