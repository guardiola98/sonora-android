package org.sonora.music

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import org.junit.Assert.*
import org.junit.Test
import org.sonora.music.accountimport.*
import org.sonora.music.discovery.*
import org.sonora.music.domain.*

@OptIn(ExperimentalCoroutinesApi::class)
class AccountImportTest {
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
    private fun source(failure: Boolean = false) = object : AccountPlaylistSource {
        override suspend fun playlists(access: TemporaryAccess, cursor: String?): AccountPage<RemotePlaylist> {
            if (failure) throw ImportAccessException("Access denied")
            return AccountPage(listOf(list))
        }
        override suspend fun songs(access: TemporaryAccess, playlist: RemotePlaylist, cursor: String?) = AccountPage(listOf(song, song.copy(id = "two", title = "Unknown song")))
    }
    @Test fun tokensExpireAreRedactedAndAreDiscardedExactlyOnce() {
        var time = 0L; var discarded = 0
        val access = TemporaryAccess("secret-value", 1, { time }) { discarded++ }
        assertFalse(access.toString().contains("secret-value"))
        assertEquals("Bearer secret-value", access.header())
        time = 1000
        try { access.header(); fail() } catch (_: ImportAccessException) { }
        access.close(); assertEquals(1, discarded); assertTrue(access.isClosed())
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
    @Test fun emptyAccountPlaylistsCanBeCreatedWithoutInventingTracks() = runTest {
        var preview: ImportPreview? = null
        val empty = object : AccountPlaylistSource {
            override suspend fun playlists(access: TemporaryAccess, cursor: String?) = AccountPage(listOf(list))
            override suspend fun songs(access: TemporaryAccess, playlist: RemotePlaylist, cursor: String?) = AccountPage<RemoteSong>(emptyList())
        }
        val presenter = AccountImportPresenter(backgroundScope, catalog(), lookup, { empty }, { preview = it })
        presenter.connect(ImportProvider.YOUTUBE, false) { TemporaryAccess("empty-fixture", 3600) }; runCurrent()
        presenter.select(list.id); presenter.readSelected(); runCurrent(); presenter.confirm()
        assertTrue(preview!!.allowEmptyCollections); assertTrue(preview!!.collections.single().tracks.isEmpty())
    }
    @Test fun cancellationAndDefinitiveErrorsDiscardTheConnection() = runTest {
        val token = TemporaryAccess("never-persist", 3600)
        val presenter = AccountImportPresenter(backgroundScope, catalog(), lookup, { source() }, {})
        presenter.connect(ImportProvider.SPOTIFY, false) { token }; runCurrent()
        assertFalse(token.isClosed()); assertEquals(1, presenter.state.value.lists.size)
        presenter.cancel(); assertTrue(token.isClosed()); assertNull(presenter.state.value.provider)
        val denied = TemporaryAccess("denied", 3600)
        val failing = AccountImportPresenter(backgroundScope, catalog(), lookup, { source(true) }, {})
        failing.connect(ImportProvider.SPOTIFY, false) { denied }; runCurrent()
        assertTrue(denied.isClosed()); assertEquals("Access denied", failing.state.value.error)
    }
    @Test fun lateAuthorizationAfterCancelCannotReconnectOrOverwriteANewImport() = runTest {
        val old = TemporaryAccess("late", 3600); val newer = TemporaryAccess("newer", 3600)
        val presenter = AccountImportPresenter(backgroundScope, catalog(), lookup, { source() }, {})
        presenter.connect(ImportProvider.SPOTIFY, false) { suspendCoroutine { continuation -> backgroundScope.launch { delay(100); continuation.resume(old) } } }
        runCurrent(); presenter.cancel()
        presenter.connect(ImportProvider.YOUTUBE, false) { newer }; runCurrent()
        advanceTimeBy(101); runCurrent()
        assertTrue(old.isClosed()); assertFalse(newer.isClosed()); assertEquals(ImportProvider.YOUTUBE, presenter.state.value.provider)
        presenter.cancel()
    }
    @Test fun tokenIsDestroyedBeforeReviewAndPendingEntriesRequireExplicitAcknowledgement() = runTest {
        var preview: ImportPreview? = null
        val token = TemporaryAccess("only-memory", 3600)
        val presenter = AccountImportPresenter(backgroundScope, catalog(), lookup, { source() }, { preview = it })
        presenter.connect(ImportProvider.SPOTIFY, false) { token }; runCurrent()
        presenter.select(list.id); presenter.readSelected(); runCurrent()
        assertTrue(token.isClosed())
        advanceTimeBy(1000); runCurrent()
        assertEquals(1, presenter.state.value.pending)
        presenter.confirm(); assertNull(preview)
        presenter.skipPending(true); presenter.confirm()
        assertEquals("spotify:${list.id}", preview!!.collections.single().key)
        assertEquals(list.name, preview!!.collections.single().name)
        assertEquals(listOf(track), preview!!.collections.single().tracks)
        assertEquals(1, preview!!.collections.single().skipped)
        assertTrue(presenter.state.value.transferred)
    }
    @Test fun abandoningReadCancelsRequestsAndANewPresenterStartsDisconnected() = runTest {
        val token = TemporaryAccess("temporary", 3600)
        var cancelled = false
        val blocking = object : AccountPlaylistSource {
            override suspend fun playlists(access: TemporaryAccess, cursor: String?) = AccountPage(listOf(list))
            override suspend fun songs(access: TemporaryAccess, playlist: RemotePlaylist, cursor: String?): AccountPage<RemoteSong> = try { awaitCancellation() } finally { cancelled = true }
        }
        val presenter = AccountImportPresenter(backgroundScope, catalog(), lookup, { blocking }, {})
        presenter.connect(ImportProvider.SPOTIFY, false) { token }; runCurrent()
        presenter.select(list.id); presenter.readSelected(); runCurrent(); presenter.cancel(); runCurrent()
        assertTrue(cancelled); assertTrue(token.isClosed())
        assertEquals(AccountImportState(), AccountImportPresenter(backgroundScope, catalog(), lookup, { source() }, {}).state.value)
    }
    @Test fun paginatedImportsKeepOrderRejectCyclesAndDoNotSilentlyTruncate() = runTest {
        val cursors = mutableListOf<String?>()
        assertEquals(listOf(1, 2, 3), collectAccountPages(3) { cursor -> cursors += cursor; if (cursor == null) AccountPage(listOf(1, 2), "next") else AccountPage(listOf(3)) })
        assertEquals(listOf(null, "next"), cursors)
        try { collectAccountPages(10) { AccountPage(listOf(1), "repeat") }; fail() } catch (_: ImportAccessException) { }
        try { collectAccountPages(2) { AccountPage(listOf(1, 2, 3)) }; fail() } catch (_: ImportAccessException) { }
    }
    @Test fun authorizationAndIdleSelectionTimeoutsCleanUp() = runTest {
        val token = TemporaryAccess("idle", 3600)
        val presenter = AccountImportPresenter(backgroundScope, catalog(), lookup, { source() }, {})
        presenter.connect(ImportProvider.SPOTIFY, false) { token }; runCurrent()
        advanceTimeBy(600_001); runCurrent()
        assertTrue(token.isClosed()); assertNotNull(presenter.state.value.error)
        presenter.connect(ImportProvider.SPOTIFY, false) { withTimeout(10) { awaitCancellation() } }; runCurrent()
        advanceTimeBy(11); runCurrent()
        assertFalse(presenter.state.value.busy); assertNotNull(presenter.state.value.error)
    }
    @Test fun fileSelectionDoesNotAuthorizeOrSearchUntilTheUserChoosesAndConfirms() = runTest {
        var queries = 0; var output: ImportPreview? = null
        val counted = object : MusicCatalogSource by catalog() {
            override suspend fun searchPage(query: String, kind: CatalogKind, cursor: String?): CatalogPage { queries++; return catalog().searchPage(query, kind, cursor) }
        }
        val presenter = AccountImportPresenter(backgroundScope, counted, lookup, { error("File import must not access an account") }, { output = it })
        val row = ReviewedPlaylist(list.copy(id = "export:fixture"), listOf(ReviewedSong(song.copy(durationSeconds = 0))))
        presenter.loadSpotifyExport { listOf(row) }; runCurrent()
        assertEquals(0, queries); assertTrue(presenter.state.value.disconnected); assertTrue(presenter.state.value.fromFile)
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
        val presenter = AccountImportPresenter(backgroundScope, counted, lookup, { error("unused") }, {})
        presenter.loadSpotifyExport { listOf(ReviewedPlaylist(list, listOf(ReviewedSong(song)))) }; runCurrent()
        presenter.searchOnline(false); presenter.select(list.id); presenter.readSelected(); runCurrent()
        assertEquals(0, queries); assertEquals(1, presenter.state.value.pending)
        presenter.cancel(); assertEquals(AccountImportState(), presenter.state.value)
        var cancelled = false
        presenter.loadSpotifyExport { try { awaitCancellation() } finally { cancelled = true } }; runCurrent()
        presenter.cancel(); runCurrent(); assertTrue(cancelled)
        presenter.readSelected(); runCurrent(); assertEquals(AccountImportState(), presenter.state.value)
    }
    @Test fun fileImportReplacesAndDiscardsAnExistingAccountSession() = runTest {
        val credential = TemporaryAccess("previous-account", 3600)
        val presenter = AccountImportPresenter(backgroundScope, catalog(), lookup, { source() }, {})
        presenter.connect(ImportProvider.SPOTIFY, false) { credential }; runCurrent()
        presenter.loadSpotifyExport { throw ImportAccessException("Bad export") }; runCurrent()
        assertTrue(credential.isClosed()); assertTrue(presenter.state.value.disconnected)
        assertEquals("Bad export", presenter.state.value.error); assertTrue(presenter.state.value.lists.isEmpty())
    }

}
